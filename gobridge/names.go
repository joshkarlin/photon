package main

import (
	"context"
	"strings"

	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

func (b *Bridge) handlePushName(evt *events.PushName) {
	// evt.JID.String() already carries the server suffix and may be a LID —
	// resolve it so the key matches the conversation row.
	jid := b.resolveLIDJID(evt.JID).String()
	name := evt.NewPushName
	if name == "" {
		return
	}
	// Push names are the lowest-priority source: only fill in a name if the
	// conversation currently has none (don't overwrite contact names).
	b.UpdateConversationNameIfEmpty(jid, name)
	b.log.Debugf("Push name update: %s -> %s", jid, name)
}

// BackfillContactNames reconciles DM conversation names with whatsmeow's
// contact store. Runs when app state sync patches land (contacts arrive via
// app state) and once as a delayed fallback after connect.
//
// Two passes in one loop:
//   - Conversations whose contact has an address book FullName are upgraded
//     to it even if they already carry a (push) name — the address book is
//     authoritative and this repairs rows named before contacts synced.
//   - Unnamed conversations are filled from the best remaining source
//     (PushName, then BusinessName).
func (b *Bridge) BackfillContactNames() {
	contacts, err := b.client.Store.Contacts.GetAllContacts(context.Background())
	if err != nil {
		contacts = make(map[types.JID]types.ContactInfo)
	}
	b.log.Infof("Backfill: %d contacts in store", len(contacts))

	// @lid rows are DMs whose phone number isn't known yet; whatsmeow keys
	// their push/business names by the LID. Read everything before writing
	// (a cursor held open across the updates deadlocks a single connection).
	type convName struct{ jid, name string }
	var convs []convName
	rows, err := b.msgDB.Query(`SELECT jid, COALESCE(name, '') FROM conversations WHERE jid LIKE '%@s.whatsapp.net' OR jid LIKE '%@lid'`)
	if err != nil {
		return
	}
	for rows.Next() {
		var c convName
		if rows.Scan(&c.jid, &c.name) == nil {
			convs = append(convs, c)
		}
	}
	rows.Close()

	updated := 0
	for _, c := range convs {
		parsedJID, parseErr := types.ParseJID(c.jid)
		if parseErr != nil {
			continue
		}
		contact, ok := contacts[parsedJID]
		if !ok {
			// Fallback: single lookup under the phone and LID keys (covers
			// stores where GetAllContacts lags behind individual sync entries).
			contact = b.lookupContact(b.resolveLIDJID(parsedJID), parsedJID)
		}

		name, authoritative := bestContactName(contact, "")
		if !authoritative && c.name != "" {
			continue // already named and nothing better than a push name — leave it
		}

		if name != "" && name != c.name {
			b.UpdateConversationName(c.jid, name)
			updated++
		}
	}

	b.log.Infof("Backfill: updated %d contact names", updated)
	if updated > 0 {
		b.BroadcastEvent("conversation_updated", struct{}{})
	}
}

// bestContactName picks a DM display name. The user's address book FullName
// (synced from the primary device) wins, then a business's verified name
// (WhatsApp shows it in place of the push name); both are authoritative —
// they may overwrite an existing name. Push names (self-chosen, mutable) only
// fill in when there's nothing better; eventPushName is the push name carried
// by a live message event ("" when not applicable, e.g. our own messages).
func bestContactName(contact types.ContactInfo, eventPushName string) (name string, authoritative bool) {
	switch {
	case contact.FullName != "":
		return contact.FullName, true
	case contact.BusinessName != "":
		return contact.BusinessName, true
	case eventPushName != "":
		return eventPushName, false
	case contact.PushName != "":
		return contact.PushName, false
	}
	return "", false
}

// dmName is bestContactName for a live DM, with the verified business name
// the message itself carries — whatsmeow stores it in the contact store
// asynchronously, so the first message from a business can predate it there.
func dmName(contact types.ContactInfo, verifiedName, eventPushName string) (name string, authoritative bool) {
	if contact.FullName == "" && verifiedName != "" {
		return verifiedName, true
	}
	return bestContactName(contact, eventPushName)
}

// handleBusinessName applies a business's (changed) verified name to its DM,
// unless the user's address book names it.
func (b *Bridge) handleBusinessName(evt *events.BusinessName) {
	if evt.NewBusinessName == "" {
		return
	}
	user := evt.JID.ToNonAD()
	resolved := b.resolveLIDJID(user)
	if b.lookupContact(resolved, user).FullName != "" {
		return
	}
	jid := resolved.String()
	if b.UpdateConversationName(jid, evt.NewBusinessName) {
		b.BroadcastEvent("conversation_updated", ConversationUpdatedEvent{JID: jid})
	}
}

// lookupContact fetches a contact record, checking under the resolved phone
// JID first and falling back to the original LID — whatsmeow's contact store
// may be keyed by either. Returns a zero ContactInfo when neither is found.
func (b *Bridge) lookupContact(resolved, original types.JID) types.ContactInfo {
	if c, err := b.client.Store.Contacts.GetContact(context.Background(), resolved); err == nil && c.Found {
		return c
	}
	if original != resolved {
		if c, err := b.client.Store.Contacts.GetContact(context.Background(), original); err == nil && c.Found {
			return c
		}
	}
	return types.ContactInfo{}
}

// resolveMentions replaces "@<number>" mention tokens in WhatsApp text with the
// mentioned contact's display name. WhatsApp stores the bare number in the body
// and ships the mentioned JIDs in contextInfo; official clients substitute the
// name on display. Resolution uses the contact store (address-book name, then
// observed push name); unresolvable mentions are left as the number.
func (b *Bridge) resolveMentions(msg *waE2E.Message, convJID, text string) string {
	ctx := getContextInfo(msg)
	if ctx == nil {
		return text
	}
	return applyMentions(text, ctx.GetMentionedJID(), func(jid types.JID) string {
		resolved := b.resolveLIDJID(jid)
		// Same precedence the sender labels use: the per-conversation participant
		// name first (so a mention reads identically to that person's label),
		// then the contact store (address book → observed push name).
		if name := b.ParticipantName(convJID, resolved.String()); name != "" {
			return name
		}
		name, _ := bestContactName(b.lookupContact(resolved, jid), "")
		return name
	})
}

// applyMentions replaces each "@<user part>" token with "@<name>" using nameFor
// to resolve each mentioned JID. Unresolvable mentions (nameFor returns "") are
// left as the raw number. Pure, so it's unit-testable without a client.
func applyMentions(text string, mentionedJIDs []string, nameFor func(types.JID) string) string {
	if text == "" {
		return text
	}
	for _, mj := range mentionedJIDs {
		parsed, err := types.ParseJID(mj)
		if err != nil {
			continue
		}
		name := nameFor(parsed)
		if name == "" {
			continue
		}
		text = strings.ReplaceAll(text, "@"+parsed.User, "@"+name)
	}
	return text
}
