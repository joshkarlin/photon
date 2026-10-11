package main

import (
	"time"

	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/proto/waWeb"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

func (b *Bridge) handleHistorySync(evt *events.HistorySync) {
	data := evt.Data
	convs := data.GetConversations()
	totalConvs := len(convs)
	totalMsgs := 0

	// Only sync messages within the retention window
	b.mu.RLock()
	cfg := b.retention
	b.mu.RUnlock()
	cutoffTS := int64(0)
	if cfg.MaxDays > 0 {
		cutoffTS = time.Now().Unix() - int64(cfg.MaxDays*86400)
	}

	b.log.Infof("History sync: %d conversations (retention: %d days, %d msgs)", totalConvs, cfg.MaxDays, cfg.MaxMessages)

	for i, conv := range convs {
		rawJID := conv.GetID()
		if rawJID == "" {
			continue
		}
		// Resolve @lid history conversations to phone JIDs so they merge
		// with rows created by live messages instead of duplicating them.
		jid := b.resolveLIDString(rawJID)
		isGroup := isGroupJID(jid)

		msgs := conv.GetMessages()
		b.log.Debugf("  Conv %s: %d messages in history", jid, len(msgs))
		chat, _ := types.ParseJID(rawJID)
		convMsgCount := 0
		skippedNil := 0
		skippedOld := 0
		skippedEmpty := 0
		var pending []*MessageRow
		// Edits and revokes in the payload apply to rows in this batch, so
		// they run after the insert.
		var edits []historyEdit
		var revokes []historyRevoke
		// Collect each distinct group sender's best name from history push
		// names so historical messages render the contact/profile name instead
		// of a bare number. Upserted once per sender after the loop.
		histParticipantNames := map[string]string{}
		for _, histMsg := range msgs {
			// Respect retention limits
			if cfg.MaxMessages > 0 && convMsgCount >= cfg.MaxMessages {
				break
			}

			wm := histMsg.GetMessage()
			if wm == nil || wm.GetKey() == nil {
				skippedNil++
				continue
			}

			ts := int64(wm.GetMessageTimestamp())
			// Skip messages older than retention window
			if cutoffTS > 0 && ts < cutoffTS {
				skippedOld++
				continue
			}

			if wm.GetMessage() == nil {
				// Missed calls are content-less stubs; other stubs (group
				// changes, security notices) aren't shown.
				sender := jid
				if p := firstNonEmpty(wm.GetParticipant(), wm.GetKey().GetParticipant()); isGroup && p != "" {
					sender = b.canonicalSenderString(p)
				}
				if row, ok := historyCallRow(wm, jid, sender); ok {
					pending = append(pending, row)
					totalMsgs++
					convMsgCount++
				} else {
					skippedNil++
				}
				continue
			}

			// Parse exactly like a live message: unwrap ephemeral/view-once/
			// edited wrappers and work out the sender. History stores the
			// group sender on WebMessageInfo.Participant (not the key), which
			// ParseWebMessage reads.
			evt := b.historyEvent(chat, wm)
			if targetID, edited := historyEditTarget(evt, wm); targetID != "" {
				edits = append(edits, historyEdit{targetID, edited, b.authorOf(evt.Info)})
				continue
			}
			kind, targetID := classifyMessageEvent(evt.Message)
			switch kind {
			case kindRevoke:
				revokes = append(revokes, historyRevoke{targetID, evt.Info})
				continue
			case kindReaction, kindDrop:
				skippedEmpty++
				continue
			}

			isFromMe := evt.Info.IsFromMe
			senderJID := b.canonicalSenderJID(evt.Info.Sender)

			// Remember the sender's push name for participant backfill.
			if isGroup && !isFromMe {
				if pn := wm.GetPushName(); pn != "" {
					if _, seen := histParticipantNames[senderJID]; !seen {
						histParticipantNames[senderJID] = pn
					}
				}
			}

			pending = append(pending, b.buildMessageRow(contentMessage(evt.Message), evt.Info.ID, jid, senderJID, ts, isFromMe, "read"))
			totalMsgs++
			convMsgCount++
		}
		// One transaction per conversation — per-row implicit transactions
		// mean thousands of WAL commits during initial pairing.
		if err := b.InsertMessages(pending); err != nil {
			b.log.Errorf("Failed to store history for %s: %v", jid, err)
		}
		for _, e := range edits {
			b.applyEdit(jid, e.targetID, e.edited, e.by)
		}
		for _, r := range revokes {
			b.applyRevoke(jid, r.info, r.targetID)
		}

		// Name each distinct group sender once, as live messages do, but
		// from the contact store first: it holds their current push name,
		// the payload one from whenever they sent the message.
		for sjid, pushName := range histParticipantNames {
			if parsed, err := types.ParseJID(sjid); err == nil {
				member := better(b.memberName(parsed, "", ""), rankedName{name: cleanName(pushName), rank: rankPush})
				member.stale = true
				b.UpsertParticipant(jid, sjid, member)
			}
		}

		if skippedNil > 0 || skippedOld > 0 || skippedEmpty > 0 {
			b.log.Debugf("  Conv %s: inserted %d, skipped %d nil, %d old, %d content-free", jid, convMsgCount, skippedNil, skippedOld, skippedEmpty)
		}

		name := b.historyChatName(conv, jid, rawJID, isGroup)

		// Upsert conversation after processing messages. Derive the preview
		// (last message + timestamp) from the newest message we actually
		// INSERTED, not the raw payload tail — WhatsApp's history array isn't
		// guaranteed newest-last, and the tail may have been skipped (retention/
		// nil), so using it pinned the conversation to an older message's date.
		lastTS := int64(0)
		lastMsgID := ""
		for _, r := range pending {
			if r.Timestamp >= lastTS {
				lastTS = r.Timestamp
				lastMsgID = r.ID
			}
		}
		b.UpsertConversation(jid, name, lastMsgID, lastTS)

		// Broadcast progress
		b.BroadcastEvent("history_sync_progress", HistorySyncProgressEvent{
			ConversationsDone:  i + 1,
			ConversationsTotal: totalConvs,
			MessagesTotal:      totalMsgs,
		})
	}

	b.BroadcastEvent("history_sync_complete", struct{}{})
	b.log.Infof("History sync complete: %d conversations, %d messages", totalConvs, totalMsgs)
}

// historyChatName names a chat from a history payload. A group's is its
// subject. A DM's is the contact store's address-book or business name,
// else the payload's name for the chat, else a push name — the store's
// current one, then any the payload's received messages carried. The
// payload's chat name ranks as a push name: what WhatsApp puts there for a
// DM isn't documented and may be stale, and an address-book contact is named
// by the store anyway. Every history name is stale, as a chunk can land well
// after live messages named the chat.
func (b *Bridge) historyChatName(conv *waHistorySync.Conversation, jid, rawJID string, isGroup bool) rankedName {
	name := rankedName{name: cleanName(conv.GetName()), rank: rankSubject, stale: true}
	if isGroup {
		return name
	}
	name.rank = rankPush
	var forms []types.JID
	for _, j := range []string{jid, rawJID} {
		if parsed, err := types.ParseJID(j); err == nil {
			forms = append(forms, parsed)
		}
	}
	if best := personName(b.lookupContact(forms...), "", ""); best.rank > rankPush {
		name = best
	} else if name.name == "" {
		name = best
	}
	if name.name == "" {
		for _, histMsg := range conv.GetMessages() {
			wm := histMsg.GetMessage()
			if wm == nil || wm.GetKey().GetFromMe() {
				continue
			}
			if pn := cleanName(wm.GetPushName()); pn != "" {
				name = rankedName{name: pn, rank: rankPush}
				break
			}
		}
	}
	name.stale = true
	return name
}

// historyEdit and historyRevoke are edits and revokes found in a history
// payload, applied after its messages are stored.
type historyEdit struct {
	targetID string
	edited   *waE2E.Message
	by       author
}

type historyRevoke struct {
	targetID string
	info     types.MessageInfo
}

// historyEvent parses a history-sync message into the same event a live one
// would be. If no sender can be worked out (rare, for groups) the message is
// kept and attributed to the chat, as before ParseWebMessage was used.
func (b *Bridge) historyEvent(chat types.JID, wm *waWeb.WebMessageInfo) *events.Message {
	evt, err := b.client.ParseWebMessage(chat, wm)
	if err == nil {
		return evt
	}
	b.log.Debugf("History message %s: %v", wm.GetKey().GetID(), err)
	evt = &events.Message{
		Info: types.MessageInfo{
			MessageSource: types.MessageSource{
				Chat:     chat,
				Sender:   chat,
				IsFromMe: wm.GetKey().GetFromMe(),
				IsGroup:  chat.Server == types.GroupServer,
			},
			ID:        wm.GetKey().GetID(),
			PushName:  wm.GetPushName(),
			Timestamp: time.Unix(int64(wm.GetMessageTimestamp()), 0),
		},
		RawMessage:   wm.GetMessage(),
		SourceWebMsg: wm,
	}
	return evt.UnwrapRaw()
}

// historyEditTarget reports an edit in a history payload. ParseWebMessage
// already rewrites one into its target's ID and new content; the fallback
// parse leaves the MESSAGE_EDIT protocol message as a live event would.
func historyEditTarget(evt *events.Message, wm *waWeb.WebMessageInfo) (targetID string, edited *waE2E.Message) {
	if evt.Info.ID != wm.GetKey().GetID() {
		return evt.Info.ID, evt.Message
	}
	if kind, id := classifyMessageEvent(evt.Message); kind == kindEdit {
		return id, evt.Message.GetProtocolMessage().GetEditedMessage()
	}
	return "", nil
}
