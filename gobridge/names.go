package main

import (
	"context"
	"strings"
	"unicode"

	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

// The bridge stores, for every conversation and group member, the best name
// WhatsApp itself gives them, or "". Kotlin puts the user's LP3 phone contacts
// above it and falls back to the formatted number, so a stored name is never
// a number. Each stored name keeps the rank of its source, and a write only
// replaces a name of lower or equal rank.
const (
	rankNone     = 0           // no name, or one stored before ranks were kept
	rankPush     = 1           // the person's own push name, or history sync's name for a chat
	rankBusiness = 2           // a business's verified name
	rankContact  = 3           // the user's address-book name for them
	rankSubject  = rankContact // a group's subject, the only name a group has
)

// rankedName is a name to store and how strongly its source backs it.
type rankedName struct {
	name string
	rank int
	// stale marks a name that may be older than the stored one (history
	// sync, which can land long after live messages): it fills a missing or
	// weaker name but never replaces one of equal rank.
	stale bool
}

// cleanName is a name as it may be stored: trimmed, and "" when it is really
// a phone number, a JID or WhatsApp's "-" placeholder.
func cleanName(s string) string {
	s = strings.TrimSpace(s)
	if s == "-" || isNumberLike(s) || isJIDLike(s) {
		return ""
	}
	return s
}

// isNumberLike reports text made only of digits and what numbers are written
// with: + - ( ) . spaces, and the invisible direction marks WhatsApp wraps
// numbers in.
func isNumberLike(s string) bool {
	digits := false
	for _, r := range s {
		switch {
		case unicode.IsDigit(r):
			digits = true
		case strings.ContainsRune("+-().", r), unicode.IsSpace(r), unicode.Is(unicode.Cf, r):
		default:
			return false
		}
	}
	return digits
}

// isJIDLike reports text that is a WhatsApp JID.
func isJIDLike(s string) bool {
	at := strings.LastIndexByte(s, '@')
	if at < 0 {
		return false
	}
	switch s[at+1:] {
	case types.DefaultUserServer, types.HiddenUserServer, types.GroupServer, types.LegacyUserServer,
		types.BroadcastServer, types.NewsletterServer:
		return true
	}
	return false
}

// personName is the best name WhatsApp gives a person: the user's address
// book entry (synced from the primary), then a business's verified name,
// then their push name. verifiedName and pushName are those a live message
// carried ("" if none, or if the user sent it): they're newer than the
// contact store's copies, which whatsmeow writes asynchronously.
func personName(contact types.ContactInfo, verifiedName, pushName string) rankedName {
	if n := firstNonEmpty(cleanName(contact.FullName), cleanName(contact.FirstName)); n != "" {
		return rankedName{name: n, rank: rankContact}
	}
	if n := firstNonEmpty(cleanName(verifiedName), cleanName(contact.BusinessName)); n != "" {
		return rankedName{name: n, rank: rankBusiness}
	}
	if n := firstNonEmpty(cleanName(pushName), cleanName(contact.PushName)); n != "" {
		return rankedName{name: n, rank: rankPush}
	}
	return rankedName{}
}

// better returns whichever name ranks higher, a on a tie.
func better(a, b rankedName) rankedName {
	if b.name != "" && (a.name == "" || b.rank > a.rank) {
		return b
	}
	return a
}

// handlePushName applies a changed push name to the person's DM. The rank
// rules keep an address-book or business name.
func (b *Bridge) handlePushName(evt *events.PushName) {
	user := evt.JID.ToNonAD()
	resolved := b.resolveLIDJID(user)
	b.SetConversationName(resolved.String(), personName(b.lookupContact(resolved, user), "", evt.NewPushName))
}

// BackfillContactNames re-applies every DM's name from whatsmeow's contact
// store, as the reconcile after connect and after a full app-state sync
// (which stores contacts without an event per contact). The rank rules make
// it upgrade names and never downgrade them; at equal rank the store, which
// holds the current value, wins.
func (b *Bridge) BackfillContactNames() {
	// @lid rows are DMs whose phone number isn't known yet; whatsmeow keys
	// their push/business names by the LID. Read everything before writing
	// (a cursor held open across the updates deadlocks a single connection).
	var jids []string
	rows, err := b.msgDB.Query(`SELECT jid FROM conversations WHERE jid LIKE '%@s.whatsapp.net' OR jid LIKE '%@lid'`)
	if err != nil {
		return
	}
	for rows.Next() {
		var jid string
		if rows.Scan(&jid) == nil {
			jids = append(jids, jid)
		}
	}
	rows.Close()

	updated := 0
	for _, jid := range jids {
		parsed, err := types.ParseJID(jid)
		if err != nil {
			continue
		}
		if b.SetConversationName(jid, personName(b.lookupContact(b.resolveLIDJID(parsed), parsed), "", "")) {
			updated++
		}
	}
	b.log.Infof("Backfill: updated %d contact names", updated)
	if updated > 0 {
		b.BroadcastEvent("conversation_updated", struct{}{})
	}
}

// handleBusinessName applies a business's (changed) verified name to its DM.
// The rank rules keep an address-book name.
func (b *Bridge) handleBusinessName(evt *events.BusinessName) {
	user := evt.JID.ToNonAD()
	resolved := b.resolveLIDJID(user)
	jid := resolved.String()
	if b.SetConversationName(jid, personName(b.lookupContact(resolved, user), evt.NewBusinessName, "")) {
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
		return personName(b.lookupContact(resolved, jid), "", "").name
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
