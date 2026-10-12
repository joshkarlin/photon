package main

import (
	"context"
	"time"

	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

// cachedGroupName is a groupNames cache entry; see Bridge.groupEntry.
type cachedGroupName struct {
	name    string
	admins  map[string]bool // user parts (phone and LID) of admins; nil = not fetched
	fetched time.Time
}

// BackfillParticipantNames names group members from whatsmeow's contact
// store — the group counterpart to BackfillContactNames, run by the same
// reconcile. Returns the groups whose member names changed.
func (b *Bridge) BackfillParticipantNames() []string {
	rows, err := b.msgDB.Query(`SELECT jid FROM conversations WHERE is_group = 1`)
	if err != nil {
		return nil
	}
	var groups []string
	for rows.Next() {
		var j string
		if rows.Scan(&j) == nil && j != "" {
			groups = append(groups, j)
		}
	}
	rows.Close()
	var changed []string
	for _, g := range groups {
		if b.resolveGroupParticipants(g) {
			changed = append(changed, g)
		}
	}
	return changed
}

func (b *Bridge) handleGroupInfo(evt *events.GroupInfo) {
	jid := evt.JID.String()
	if evt.Name != nil {
		b.setGroupName(jid, evt.Name.Name)
		if b.SetConversationName(jid, rankedName{name: cleanName(evt.Name.Name), rank: rankSubject}) {
			b.namesChanged([]string{jid})
		}
	}
	if len(evt.Promote) > 0 || len(evt.Demote) > 0 {
		b.forgetGroupAdmins(jid)
	}
	if evt.Ephemeral != nil {
		seconds := uint32(0)
		if evt.Ephemeral.IsEphemeral {
			seconds = evt.Ephemeral.DisappearingTimer
		}
		at := evt.Timestamp.Unix()
		b.applyTimer(jid, timerReport{timerSetting: timerSetting{seconds: seconds, setAt: at}, at: at})
	}
	b.BroadcastEvent("conversation_updated", ConversationUpdatedEvent{JID: jid})
}

// groupName returns the group's subject, hitting the server at most once per
// hour per group — GetGroupInfo is a network round-trip and groups rarely
// rename. Rename events (handleGroupInfo) refresh the cache immediately.
func (b *Bridge) groupName(jid types.JID) string {
	return b.groupEntry(jid, false).name
}

// isGroupAdmin reports whether a person (any of their JID forms) is an admin
// of the group, per the server's group info, cached like the name.
func (b *Bridge) isGroupAdmin(group types.JID, who ...types.JID) bool {
	admins := b.groupEntry(group, true).admins
	for _, w := range who {
		if w.IsEmpty() {
			continue
		}
		if admins[w.User] || admins[b.resolveLIDJID(w.ToNonAD()).User] {
			return true
		}
	}
	return false
}

// groupEntry returns the cached group info, fetching it when it's over an
// hour old or (needAdmins) its admin list isn't known. Failures aren't
// cached — the next call retries.
func (b *Bridge) groupEntry(jid types.JID, needAdmins bool) cachedGroupName {
	key := jid.String()
	b.groupNameMu.Lock()
	cached, ok := b.groupNames[key]
	b.groupNameMu.Unlock()
	if ok && time.Since(cached.fetched) < time.Hour && (!needAdmins || cached.admins != nil) {
		return cached
	}

	fetchedAt := time.Now().Unix()
	groupInfo, err := b.client.GetGroupInfo(context.Background(), jid)
	if err != nil {
		b.log.Warnf("GetGroupInfo failed for %s: %v", key, err)
		return cached
	}
	if groupInfo.Name == "" {
		b.log.Warnf("GetGroupInfo returned empty name for %s", key)
	}
	b.applyGroupTimer(jid, groupInfo, fetchedAt)
	entry := cachedGroupName{name: groupInfo.Name, admins: map[string]bool{}, fetched: time.Now()}
	for _, p := range groupInfo.Participants {
		if p.IsAdmin || p.IsSuperAdmin {
			for _, j := range []types.JID{p.JID, p.PhoneNumber, p.LID} {
				if !j.IsEmpty() {
					entry.admins[j.User] = true
				}
			}
		}
	}
	b.groupNameMu.Lock()
	b.groupNames[key] = entry
	b.groupNameMu.Unlock()
	return entry
}

// setGroupName records a rename; the admin list is kept.
func (b *Bridge) setGroupName(jid, name string) {
	b.groupNameMu.Lock()
	entry := b.groupNames[jid]
	entry.name, entry.fetched = name, time.Now()
	b.groupNames[jid] = entry
	b.groupNameMu.Unlock()
}

// forgetGroupAdmins makes the next admin check refetch the group's info.
func (b *Bridge) forgetGroupAdmins(jid string) {
	b.groupNameMu.Lock()
	if entry, ok := b.groupNames[jid]; ok {
		entry.admins = nil
		b.groupNames[jid] = entry
	}
	b.groupNameMu.Unlock()
}

// memberName is the name to store for a group member, ranked as personName
// ranks it: their address-book name, verified business name or push name
// (verifiedName and pushName being those their message carried, if any).
// Live messages, history sync and ResolveGroupParticipants all name members
// with it, and a DM is named the same way.
func (b *Bridge) memberName(sender types.JID, verifiedName, pushName string) rankedName {
	return personName(b.lookupContact(sender), verifiedName, pushName)
}

// ResolveGroupParticipants names every distinct sender in a group's stored
// messages from whatsmeow's contact store, so senders stored before the
// store knew them (history, a contact added later) get a name without a
// re-pair. The rank rules make it upgrade names and never downgrade them.
// Cheap and idempotent, so it runs on every group open.
func (b *Bridge) ResolveGroupParticipants(groupJID string) {
	if b.resolveGroupParticipants(groupJID) {
		b.namesChanged([]string{groupJID})
		b.BroadcastEvent("conversation_updated", ConversationUpdatedEvent{JID: groupJID})
	}
}

func (b *Bridge) resolveGroupParticipants(groupJID string) (changed bool) {
	rows, err := b.msgDB.Query(
		`SELECT DISTINCT sender_jid FROM messages WHERE conversation_jid = ? AND is_from_me = 0`,
		groupJID,
	)
	if err != nil {
		b.log.Errorf("ResolveGroupParticipants query failed: %v", err)
		return false
	}
	var senders []string
	for rows.Next() {
		var sjid string
		if scanErr := rows.Scan(&sjid); scanErr == nil && sjid != "" {
			senders = append(senders, sjid)
		}
	}
	rows.Close()

	for _, sjid := range senders {
		parsed, perr := types.ParseJID(sjid)
		if perr != nil {
			continue
		}
		// No name: nothing is written, keeping any push name a message
		// taught us (members outside the address book have no contact-store
		// name), and Kotlin falls back to the number on its own.
		if b.UpsertParticipant(groupJID, sjid, b.memberName(parsed, "", "")) {
			changed = true
		}
	}
	return changed
}

// handleJoinedGroup records the timer of a group the user joined (or was
// added to): its info is the server's current state.
func (b *Bridge) handleJoinedGroup(evt *events.JoinedGroup) {
	b.applyGroupTimer(evt.JID, &evt.GroupInfo, time.Now().Unix())
}
