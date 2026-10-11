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

// isNumberLike reports a phone number or LID: at least minNumberDigits
// digits and only what numbers are written with besides — + - ( ) . spaces,
// and the invisible direction marks WhatsApp wraps numbers in. Fewer digits
// make a name, like a group called "2024".
func isNumberLike(s string) bool {
	digits := 0
	for _, r := range s {
		switch {
		case unicode.IsDigit(r):
			digits++
		case strings.ContainsRune("+-().", r), unicode.IsSpace(r), unicode.Is(unicode.Cf, r):
		default:
			return false
		}
	}
	return digits >= minNumberDigits
}

// minNumberDigits is the fewest digits a phone number (or LID) has.
const minNumberDigits = 7

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

// handleContact applies an address-book entry the user added or changed on
// their phone. whatsmeow emits one per incremental app-state change, having
// just stored it under the mutation's JID; the action's own name is laid over
// the merged lookup so a stale entry under the person's other JID form can't
// win. A full sync stores contacts without events — the reconcile after
// AppStateSyncComplete covers those. whatsmeow drops removals, so a contact
// deleted on the phone keeps its name here.
func (b *Bridge) handleContact(evt *events.Contact) {
	forms := []types.JID{evt.JID}
	for _, alt := range []string{evt.Action.GetPnJID(), evt.Action.GetLidJID()} {
		if j, err := types.ParseJID(alt); alt != "" && err == nil {
			forms = append(forms, j)
		}
	}
	contact := b.lookupContact(forms...)
	if evt.Action.GetFullName() != "" || evt.Action.GetFirstName() != "" {
		contact.FullName, contact.FirstName = evt.Action.GetFullName(), evt.Action.GetFirstName()
	}
	b.renamePerson(personName(contact, "", ""), forms...)
}

// handlePushName applies a person's changed push name, which whatsmeow
// reports once per change, from any message.
func (b *Bridge) handlePushName(evt *events.PushName) {
	b.renamePerson(personName(b.lookupContact(evt.JID, evt.JIDAlt), "", evt.NewPushName), evt.JID, evt.JIDAlt)
}

// handleBusinessName applies a business's changed verified name.
func (b *Bridge) handleBusinessName(evt *events.BusinessName) {
	b.renamePerson(personName(b.lookupContact(evt.JID), evt.NewBusinessName, ""), evt.JID)
}

// renamePerson stores a person's (re-worked-out) name on their DM and on
// their member row in every group they've written in, under the rank rules:
// a new push name doesn't displace an address-book or business name. Rows
// are keyed by the number when the LID mapping is known and by the LID
// otherwise, and may still exist under both, so every form is renamed.
func (b *Bridge) renamePerson(name rankedName, forms ...types.JID) {
	if name.name == "" {
		return
	}
	var keys []string
	for _, j := range b.personForms(forms...) {
		keys = append(keys, j.String())
	}
	if len(keys) == 0 {
		return
	}
	var changed []string
	for _, k := range keys {
		if b.SetConversationName(k, name) {
			changed = append(changed, k)
		}
	}
	changed = append(changed, b.renameMember(keys, name)...)
	b.namesChanged(changed)
}

// renameMember names a person in every group where they have a member row
// or a stored message, under any of the given JIDs. Returns the groups
// whose stored names changed.
func (b *Bridge) renameMember(keys []string, name rankedName) []string {
	args := make([]interface{}, 0, 2*len(keys))
	for _, k := range keys {
		args = append(args, k)
	}
	args = append(args, args...)
	rows, err := b.msgDB.Query(`
		SELECT DISTINCT conversation_jid, sender_jid FROM messages
			WHERE is_from_me = 0 AND sender_jid IN (`+sqlInList(len(keys))+`) AND conversation_jid LIKE '%@g.us'
		UNION SELECT conversation_jid, jid FROM participants WHERE jid IN (`+sqlInList(len(keys))+`)`, args...)
	if err != nil {
		b.log.Errorf("Failed to find groups of %v: %v", keys, err)
		return nil
	}
	type member struct{ group, jid string }
	var members []member
	for rows.Next() {
		var m member
		if rows.Scan(&m.group, &m.jid) == nil {
			members = append(members, m)
		}
	}
	rows.Close()

	var changed []string
	for _, m := range members {
		if b.UpsertParticipant(m.group, m.jid, name) {
			changed = append(changed, m.group)
		}
	}
	return changed
}

// namesChanged tells Kotlin which conversations' stored names (their own,
// or their members') changed outside a message, so it can refresh them.
func (b *Bridge) namesChanged(jids []string) {
	if len(jids) == 0 {
		return
	}
	seen := map[string]bool{}
	var unique []string
	for _, j := range jids {
		if !seen[j] {
			seen[j] = true
			unique = append(unique, j)
		}
	}
	b.BroadcastEvent("names_changed", NamesChangedEvent{JIDs: unique})
}

// BackfillContactNames re-applies every DM's name from whatsmeow's contact
// store, for the reconcile. The rank rules make it upgrade names and never
// downgrade them; at equal rank the store, which holds the current value,
// wins. Returns the conversations renamed.
func (b *Bridge) BackfillContactNames() []string {
	// @lid rows are DMs whose phone number isn't known yet; whatsmeow keys
	// their push/business names by the LID. Read everything before writing
	// (a cursor held open across the updates deadlocks a single connection).
	var jids []string
	rows, err := b.msgDB.Query(`SELECT jid FROM conversations WHERE jid LIKE '%@s.whatsapp.net' OR jid LIKE '%@lid'`)
	if err != nil {
		return nil
	}
	for rows.Next() {
		var jid string
		if rows.Scan(&jid) == nil {
			jids = append(jids, jid)
		}
	}
	rows.Close()

	var changed []string
	for _, jid := range jids {
		parsed, err := types.ParseJID(jid)
		if err != nil {
			continue
		}
		if b.SetConversationName(jid, personName(b.lookupContact(parsed), "", "")) {
			changed = append(changed, jid)
		}
	}
	b.log.Infof("Backfill: updated %d contact names", len(changed))
	return changed
}

// lookupContact merges what whatsmeow's contact store holds for a person
// under each of their JID forms. The store keys an entry by the exact JID it
// came with — an address-book entry by its app-state mutation's index, which
// may be the LID or the number — so a phone-keyed chat must look under the
// LID too, and the other way round. Where both forms hold a name, the LID's
// wins: whatsmeow ignores app-state removals, so a contact WhatsApp moved
// from its number's index to its LID's leaves a stale entry under the
// number, never the reverse.
func (b *Bridge) lookupContact(forms ...types.JID) types.ContactInfo {
	var out types.ContactInfo
	for _, jid := range b.personForms(forms...) {
		c, err := b.client.Store.Contacts.GetContact(context.Background(), jid)
		if err != nil || !c.Found {
			continue
		}
		out.Found = true
		out.FirstName = firstNonEmpty(out.FirstName, c.FirstName)
		out.FullName = firstNonEmpty(out.FullName, c.FullName)
		out.PushName = firstNonEmpty(out.PushName, c.PushName)
		out.BusinessName = firstNonEmpty(out.BusinessName, c.BusinessName)
	}
	return out
}

// personForms is every JID form of the people given: each without its device
// suffix, plus its phone or LID counterpart where whatsmeow knows the
// mapping. LID forms come first (see lookupContact).
func (b *Bridge) personForms(jids ...types.JID) []types.JID {
	seen := map[types.JID]bool{}
	var phones, lids []types.JID
	add := func(j types.JID) {
		if j.IsEmpty() || seen[j] {
			return
		}
		seen[j] = true
		if j.Server == types.HiddenUserServer {
			lids = append(lids, j)
		} else {
			phones = append(phones, j)
		}
	}
	for _, j := range jids {
		if j.IsEmpty() {
			continue
		}
		j = j.ToNonAD()
		add(j)
		if alt, err := b.client.Store.GetAltJID(context.Background(), j); err == nil {
			add(alt.ToNonAD())
		}
	}
	return append(lids, phones...)
}

// resolveMentions replaces "@<user>" mention tokens in WhatsApp text with the
// mentioned person's name. WhatsApp puts the bare user part of their JID in
// the body — the number, or in a LID-addressed group the LID — and ships the
// mentioned JIDs in contextInfo; official clients substitute the name on
// display. A mention with no name known reads as the number: a LID one is
// rewritten to the number it stands for, so the text never shows a raw LID
// whose number is known.
func (b *Bridge) resolveMentions(msg *waE2E.Message, convJID, text string) string {
	ctx := getContextInfo(msg)
	if ctx == nil {
		return text
	}
	return applyMentions(text, ctx.GetMentionedJID(), func(jid types.JID) string {
		resolved := b.resolveLIDJID(jid)
		// Same precedence the sender labels use: the per-conversation participant
		// name first (so a mention reads identically to that person's label),
		// then the contact store.
		if name := b.ParticipantName(convJID, resolved.String()); name != "" {
			return name
		}
		if name := personName(b.lookupContact(jid), "", "").name; name != "" {
			return name
		}
		if jid.Server == types.HiddenUserServer && resolved.Server == types.DefaultUserServer {
			return resolved.User
		}
		return ""
	})
}

// applyMentions replaces each "@<user part>" token with "@<label>" using
// labelFor to label each mentioned JID. Mentions it can't label (labelFor
// returns "") are left as they are. Pure, so it's unit-testable without a
// client.
func applyMentions(text string, mentionedJIDs []string, labelFor func(types.JID) string) string {
	if text == "" {
		return text
	}
	for _, mj := range mentionedJIDs {
		parsed, err := types.ParseJID(mj)
		if err != nil {
			continue
		}
		label := labelFor(parsed)
		if label == "" {
			continue
		}
		text = strings.ReplaceAll(text, "@"+parsed.User, "@"+label)
	}
	return text
}
