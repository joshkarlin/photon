package main

import (
	"context"
	"database/sql"
	"fmt"
	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waSyncAction"
	"go.mau.fi/whatsmeow/proto/waWeb"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
	"os"
	"strings"
	"time"
)

// resolveLIDJID resolves an @lid JID to its phone-based JID when the mapping
// is known. Returns the input unchanged for non-LID JIDs or unknown mappings.
// All conversation keys must go through this so LID and phone events land in
// the same row.
func (b *Bridge) resolveLIDJID(jid types.JID) types.JID {
	if jid.Server != types.HiddenUserServer {
		return jid
	}
	if pn, err := b.client.Store.LIDs.GetPNForLID(context.Background(), jid); err == nil && !pn.IsEmpty() {
		return pn
	}
	return jid
}

// resolveLIDString is resolveLIDJID for raw JID strings (WS API params,
// history sync payloads).
func (b *Bridge) resolveLIDString(jidStr string) string {
	parsed, err := types.ParseJID(jidStr)
	if err != nil {
		return jidStr
	}
	return b.resolveLIDJID(parsed).String()
}

// canonicalSenderJID returns the stable key for a message sender: LID resolved
// to the phone JID and the device/agent suffix (":N") stripped. The same person
// must key identically across history sync and live messages, and the contact
// store is keyed without the device suffix — without ToNonAD a live sender like
// "447...:4@s.whatsapp.net" never matches its contact.
func (b *Bridge) canonicalSenderJID(jid types.JID) string {
	return b.resolveLIDJID(jid).ToNonAD().String()
}

// canonicalSenderString is canonicalSenderJID for a raw JID string.
func (b *Bridge) canonicalSenderString(jidStr string) string {
	parsed, err := types.ParseJID(jidStr)
	if err != nil {
		return jidStr
	}
	return b.canonicalSenderJID(parsed)
}

// HandleEvent is the master event handler for whatsmeow events.
func (b *Bridge) HandleEvent(evt interface{}) {
	switch v := evt.(type) {
	case *events.Message:
		b.handleMessage(v)
	case *events.Receipt:
		b.handleReceipt(v)
	case *events.ChatPresence:
		b.handleChatPresence(v)
	case *events.HistorySync:
		b.handleHistorySync(v)
	case *events.Connected:
		b.log.Infof("Connected to WhatsApp")
		b.markConnectionConnected()
		b.client.SendPresence(context.Background(), types.PresenceAvailable)
		// Fallback reconcile: AppStateSyncComplete normally triggers the
		// merge+backfill, but if the event never fires (already-synced
		// session) this single delayed run still converges names.
		go func() {
			time.Sleep(60 * time.Second)
			b.MergeLIDConversations()
			b.CanonicalizeSenderJIDs()
			b.RepairConversationMetadata()
			b.BackfillContactNames()
			b.BackfillParticipantNames()
		}()
	case *events.AppStateSyncComplete:
		// Contact names and LID mappings arrive via app state sync — run the
		// reconcile as soon as a patch lands instead of polling on a timer.
		// Both operations are idempotent, so running per-patch is safe.
		b.log.Infof("App state sync complete: %s", v.Name)
		go func() {
			b.MergeLIDConversations()
			b.CanonicalizeSenderJIDs()
			b.RepairConversationMetadata()
			b.BackfillContactNames()
			b.BackfillParticipantNames()
		}()
	case *events.Disconnected:
		b.log.Infof("Disconnected from WhatsApp")
		state, _ := b.currentConnectionState()
		if state != "logged_out" {
			b.setConnectionState("connecting", "WhatsApp socket disconnected; reconnecting")
		}
	case *events.LoggedOut:
		reason := v.PermanentDisconnectDescription()
		b.log.Infof("Logged out: %s", reason)
		b.pauseReconnect(reason)
		b.setConnectionState("logged_out", reason)
	case *events.KeepAliveTimeout:
		b.log.Warnf("WhatsApp keepalive timeout (errors=%d)", v.ErrorCount)
		b.setConnectionState("connecting", fmt.Sprintf("WhatsApp keepalive timeout (%d)", v.ErrorCount))
	case *events.KeepAliveRestored:
		b.log.Infof("WhatsApp keepalive restored")
		b.markConnectionConnected()
	case events.PermanentDisconnect:
		reason := v.PermanentDisconnectDescription()
		b.log.Errorf("WhatsApp connection requires attention: %s", reason)
		b.pauseReconnect(reason)
		b.setConnectionState("disconnected", reason)
	case *events.PairSuccess:
		b.log.Infof("Pair success: %s on %s", v.ID, v.Platform)
		b.BroadcastEvent("pair_success", PairSuccessEvent{
			JID:      v.ID.String(),
			Platform: v.Platform,
		})
	case *events.PushName:
		b.handlePushName(v)
	case *events.CallOffer, *events.CallOfferNotice, *events.CallPreAccept, *events.CallAccept,
		*events.CallTerminate, *events.CallReject, *events.UnknownCallEvent:
		b.handleCallEvent(v)
	case *events.GroupInfo:
		b.handleGroupInfo(v)
	case *events.Mute:
		b.handleMute(v)
	case *events.MarkChatAsRead:
		b.handleMarkChatAsRead(v)
	case *events.BusinessName:
		b.handleBusinessName(v)
	}
}

// handleMarkChatAsRead applies "mark as read/unread" done on another device.
// The action carries the newest message the user had seen, so messages that
// arrived after it stay unread.
func (b *Bridge) handleMarkChatAsRead(evt *events.MarkChatAsRead) {
	jid := b.resolveLIDJID(evt.JID).String()
	readUpTo := evt.Action.GetMessageRange().GetLastMessageTimestamp()
	if readUpTo == 0 && !evt.Timestamp.IsZero() {
		readUpTo = evt.Timestamp.Unix()
	}
	if readUpTo == 0 {
		readUpTo = time.Now().Unix()
	}
	b.applyChatReadState(jid, evt.Action.GetRead(), readUpTo)
}

func (b *Bridge) applyChatReadState(jid string, read bool, readUpTo int64) {
	if !read {
		b.MarkChatUnread(jid)
		b.BroadcastEvent("conversation_updated", ConversationUpdatedEvent{JID: jid})
		return
	}
	if b.MarkChatReadUpTo(jid, readUpTo) == 0 {
		b.BroadcastEvent("read_self", ReadSelfEvent{ConversationJID: jid})
	} else {
		b.BroadcastEvent("conversation_updated", ConversationUpdatedEvent{JID: jid})
	}
}

// handleMute syncs WhatsApp mute state from the primary device. The end time
// is stored rather than a flag, so a timed mute lapses on its own.
func (b *Bridge) handleMute(evt *events.Mute) {
	// Resolve LID to phone JID so keys match our conversations table.
	jid := b.resolveLIDJID(evt.JID).String()
	until := mutedUntil(evt.Action)
	b.UpdateMute(jid, until)
	b.BroadcastEvent("conversation_updated", ConversationUpdatedEvent{JID: jid})
	b.log.Infof("Mute update: %s muted_until=%d", jid, until)
}

// mutedUntil converts a mute action to muted_until (0 = not muted, -1 =
// forever, else the unix second it ends). The action's end time is in
// milliseconds, and WhatsApp marks "always" with -1 (see appstate.BuildMute);
// a mute with no end at all is treated as "always" too.
func mutedUntil(act *waSyncAction.MuteAction) int64 {
	if !act.GetMuted() {
		return 0
	}
	endMs := act.GetMuteEndTimestamp()
	if endMs <= 0 {
		return -1
	}
	return (endMs + 999) / 1000
}

// BackfillMutedUntil copies each chat's mute end time from whatsmeow's chat
// settings, which recorded it all along. Repairs rows from builds that kept
// only a flag (timed mutes never lapsed, "always" mutes read as unmuted)
// without waiting for the next mute change. Runs at startup; idempotent.
func (b *Bridge) BackfillMutedUntil(ctx context.Context) {
	rows, err := b.msgDB.QueryContext(ctx, `SELECT jid, muted_until FROM conversations`)
	if err != nil {
		return
	}
	stored := map[string]int64{}
	for rows.Next() {
		var jid string
		var until int64
		if rows.Scan(&jid, &until) == nil {
			stored[jid] = until
		}
	}
	rows.Close()

	fixed := 0
	for jid, until := range stored {
		parsed, err := types.ParseJID(jid)
		if err != nil {
			continue
		}
		want, ok := b.storedMutedUntil(ctx, parsed)
		if ok && want != until {
			b.UpdateMute(jid, want)
			fixed++
		}
	}
	if fixed > 0 {
		b.log.Infof("Backfilled mute end time for %d chats", fixed)
		b.BroadcastEvent("conversation_updated", struct{}{})
	}
}

// storedMutedUntil reads whatsmeow's mute for a chat. A DM's settings may sit
// under its phone JID, its LID, or both (say a pin under one and the mute
// under the other), so the longer mute of the two wins.
func (b *Bridge) storedMutedUntil(ctx context.Context, jid types.JID) (until int64, found bool) {
	keys := []types.JID{jid}
	if jid.Server == types.DefaultUserServer {
		if lid, err := b.client.Store.LIDs.GetLIDForPN(ctx, jid); err == nil && !lid.IsEmpty() {
			keys = append(keys, lid)
		}
	}
	for _, key := range keys {
		s, err := b.client.Store.ChatSettings.GetChatSettings(ctx, key)
		if err != nil || !s.Found {
			continue
		}
		found = true
		until = longerMute(until, mutedUntilFromSettings(s.MutedUntil))
	}
	return until, found
}

// longerMute picks the mute that lasts longer (-1 = forever, 0 = none).
func longerMute(x, y int64) int64 {
	if x == -1 || y == -1 {
		return -1
	}
	if y > x {
		return y
	}
	return x
}

// mutedUntilFromSettings converts whatsmeow's stored mute end to muted_until.
func mutedUntilFromSettings(t time.Time) int64 {
	switch {
	case t.IsZero():
		return 0
	case !t.Before(store.MutedForever):
		return -1
	}
	return t.Unix()
}

func (b *Bridge) handleMessage(evt *events.Message) {
	msg := evt.Message
	if msg == nil {
		return
	}

	// Resolve LID JIDs to phone-based JIDs for consistent conversation keys.
	// The sender is resolved too so a group member has the same key in both
	// live messages and history sync — otherwise the same person's older
	// (history) and newer (live) messages key under different JIDs and the
	// participant name only ever attaches to one of them.
	resolvedChat := b.resolveLIDJID(evt.Info.Chat)
	chatJID := resolvedChat.String()
	senderJID := b.canonicalSenderJID(evt.Info.Sender)
	isFromMe := evt.Info.IsFromMe
	msgID := evt.Info.ID
	ts := evt.Info.Timestamp.Unix()

	kind, targetID := classifyMessageEvent(msg)
	switch kind {
	case kindReaction:
		b.UpsertReaction(targetID, senderJID, msg.GetReactionMessage().GetText(), ts)
		b.BroadcastEvent("message_updated", MessageUpdatedEvent{
			ConversationJID: chatJID,
			MessageID:       targetID,
		})
		return
	case kindRevoke:
		b.applyRevoke(targetID)
		return
	case kindEdit:
		b.applyEdit(chatJID, targetID, msg.GetProtocolMessage().GetEditedMessage())
		return
	case kindDrop:
		b.log.Debugf("Skipping content-free message %s in %s", msgID, chatJID)
		return
	}

	content := contentMessage(msg)
	row := b.buildMessageRow(content, msgID, chatJID, senderJID, ts, isFromMe, "sent")

	// The phone's copy of a message it had masked from linked devices:
	// upgrade the placeholder row in place (it was already counted).
	if evt.UnavailableRequestID != "" && b.ReplaceMessageContent(row) {
		b.saveInlineThumbnail(msgID, content)
		b.BroadcastEvent("message_updated", MessageUpdatedEvent{ConversationJID: chatJID, MessageID: msgID})
		return
	}

	inserted, err := b.InsertMessage(row)
	if err != nil {
		b.log.Errorf("Failed to store message %s in %s: %v", msgID, chatJID, err)
		return
	}
	if !inserted {
		// A redelivery (offline queue replay, retry after a decrypt failure)
		// of a message already stored. Counting or announcing it again would
		// double the unread badge and re-notify.
		b.log.Debugf("Ignoring redelivered message %s in %s", msgID, chatJID)
		return
	}
	contentType, textBody := row.ContentType, row.TextBody.String

	b.saveInlineThumbnail(msgID, content)

	// Auto-download small media (stickers, audio)
	if contentType == "sticker" || contentType == "audio" {
		go b.autoDownloadMedia(msgID, content)
	}

	if content.GetPlaceholderMessage() != nil && !isFromMe {
		go b.requestFromPhone(evt.Info)
	}

	// A call-log entry in the chat. Live calls are tracked from call events,
	// which own the unread count and notification, so this only shows the
	// entry.
	isCallLog := contentType == "call"
	if isCallLog {
		b.log.Infof("Call log message %s in %s: %s", msgID, chatJID, textBody)
	}

	// Update conversation
	name := ""
	nameIsAuthoritative := false
	isGroup := evt.Info.Chat.Server == types.GroupServer
	if isGroup {
		// For groups, get the group name from group info (cached — one
		// network fetch per group per hour, not per message)
		name = b.groupName(evt.Info.Chat)

		// Store sender as participant with their display name. Prefer the push
		// name, then the address-book contact name — but never the phone number.
		// A number written here is overwritten by the live push name later, but
		// the reverse (ResolveGroupParticipants) used to clobber good names with
		// numbers; keeping only real names in the table avoids that entirely and
		// lets the UI fall back to the number on its own when nothing is known.
		senderDisplayName := evt.Info.PushName
		if senderDisplayName == "" {
			senderDisplayName, _ = bestContactName(b.lookupContact(b.resolveLIDJID(evt.Info.Sender), evt.Info.Sender), "")
		}
		if senderDisplayName != "" {
			b.UpsertParticipant(chatJID, senderJID, senderDisplayName, "member")
		}
	} else {
		// Only use the event push/verified name from the OTHER person, not
		// ourselves.
		eventPushName, verifiedName := "", ""
		if !isFromMe {
			eventPushName = evt.Info.PushName
			if evt.Info.VerifiedName != nil {
				verifiedName = evt.Info.VerifiedName.Details.GetVerifiedName()
			}
		}
		contact := b.lookupContact(resolvedChat, evt.Info.Chat)
		name, nameIsAuthoritative = dmName(contact, verifiedName, eventPushName)
		// For LID JIDs, fall back to the resolved phone number
		if name == "" && resolvedChat != evt.Info.Chat {
			name = "+" + resolvedChat.User
			b.log.Infof("Resolved LID %s to %s", evt.Info.Chat, name)
		}
	}
	b.UpsertConversation(chatJID, name, isGroup, msgID, ts)
	// Address book names overwrite previously stored push names; everything
	// else only fills empty names (handled by UpsertConversation's COALESCE).
	if nameIsAuthoritative {
		b.UpdateConversationName(chatJID, name)
	}

	if isCallLog {
		b.BroadcastEvent("message_updated", MessageUpdatedEvent{ConversationJID: chatJID, MessageID: msgID})
		return
	}

	// Increment unread if not from me
	if !isFromMe {
		b.IncrementUnread(chatJID)
	}

	// Broadcast new message event
	b.BroadcastEvent("new_message", NewMessageEvent{
		ConversationJID: chatJID,
		MessageID:       msgID,
		SenderName:      name,
		TextBody:        textBody,
		ContentType:     contentType,
		IsFromMe:        isFromMe,
	})
}

// applyRevoke blanks a message deleted for everyone. If it was the last unread
// message in its chat, Kotlin uses message_revoked's unread_count to withdraw
// the notification that announced it.
func (b *Bridge) applyRevoke(targetID string) {
	res, ok := b.RevokeMessage(targetID)
	if !ok {
		b.log.Debugf("Revoke for unknown message %s", targetID)
		return
	}
	for _, f := range res.Files {
		if b.isBridgeMediaPath(f) {
			os.Remove(f)
		}
	}
	b.BroadcastEvent("message_updated", MessageUpdatedEvent{
		ConversationJID: res.ConversationJID,
		MessageID:       targetID,
	})
	b.BroadcastEvent("message_revoked", MessageRevokedEvent{
		ConversationJID: res.ConversationJID,
		MessageID:       targetID,
		WasUnread:       res.WasUnread,
		UnreadCount:     res.Unread,
	})
}

func (b *Bridge) handleReceipt(evt *events.Receipt) {
	// Resolve LID → PN so the JID matches the conversation row written by
	// handleMessage (which stores conversations under the phone-based JID).
	// Without this, ReadSelf receipts for LID chats fail to clear unread.
	chatJID := b.resolveLIDJID(evt.Chat).String()

	ids := make([]string, len(evt.MessageIDs))
	for i, id := range evt.MessageIDs {
		ids[i] = string(id)
	}
	b.applyReceipt(chatJID, ids, evt.Type, evt.IsFromMe)
}

// receiptEffect classifies a receipt. ownRead means the user read incoming
// messages on another of their devices: "read-self" when read receipts are
// off, or a plain "read" sent from our own JID when they're on. Every other
// read/delivered receipt is a contact acknowledging one of OUR messages, so it
// only advances the outgoing status — it must never clear our unread count
// (that once made a chat look read the moment the other person read our
// reply). Anything else (played, sender, retry, …) has no effect.
func receiptEffect(t types.ReceiptType, isFromMe bool) (ownRead bool, outgoingStatus string) {
	switch {
	case t == types.ReceiptTypeReadSelf, t == types.ReceiptTypeRead && isFromMe:
		return true, ""
	case isFromMe:
		return false, ""
	case t == types.ReceiptTypeRead:
		return false, "read"
	case t == types.ReceiptTypeDelivered:
		return false, "delivered"
	}
	return false, ""
}

func (b *Bridge) applyReceipt(chatJID string, ids []string, t types.ReceiptType, isFromMe bool) {
	ownRead, status := receiptEffect(t, isFromMe)
	switch {
	case ownRead:
		b.MarkIncomingRead(chatJID, ids)
		b.BroadcastEvent("read_self", ReadSelfEvent{
			ConversationJID: chatJID,
			MessageIDs:      ids,
		})
	case status != "":
		b.AdvanceOutgoingStatus(ids, status)
		b.BroadcastEvent("receipt", ReceiptEvent{
			ConversationJID: chatJID,
			MessageIDs:      ids,
			Type:            status,
		})
	}
}

func (b *Bridge) handleChatPresence(evt *events.ChatPresence) {
	composing := evt.State == types.ChatPresenceComposing
	// Keyed like messages, so the indicator matches the open conversation
	// (and the sender's participant name) in @lid chats too.
	b.BroadcastEvent("typing", TypingEvent{
		JID:       b.resolveLIDJID(evt.Chat).String(),
		SenderJID: b.canonicalSenderJID(evt.Sender),
		Composing: composing,
	})
}

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

		name := conv.GetName()
		// Detect groups solely by the JID server (g.us) — the authoritative
		// signal. The old participant-count heuristic fallback misfired both
		// ways (an empty participant list misclassified groups as DMs; a DM
		// payload that happened to carry a participant misclassified it as a
		// group), and a wrong is_group used to be sticky forever.
		isGroup := false
		if parsedConvJID, perr := types.ParseJID(jid); perr == nil {
			isGroup = parsedConvJID.Server == types.GroupServer
		}

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
		var revokes []string
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
				edits = append(edits, historyEdit{targetID, edited})
				continue
			}
			kind, targetID := classifyMessageEvent(evt.Message)
			switch kind {
			case kindRevoke:
				revokes = append(revokes, targetID)
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
		b.InsertMessages(pending)
		for _, e := range edits {
			if _, text, _ := extractContent(e.edited); text != "" {
				b.UpdateMessageEdit(e.targetID, b.resolveMentions(e.edited, jid, text))
			}
		}
		for _, id := range revokes {
			b.RevokeMessage(id)
		}

		// Backfill group participant names: prefer the address-book contact
		// name, fall back to the history push name. Done once per distinct
		// sender so old messages render names like live ones do.
		for sjid, pushName := range histParticipantNames {
			name := pushName
			if parsed, perr := types.ParseJID(sjid); perr == nil {
				contact := b.lookupContact(parsed, parsed)
				if cn, _ := bestContactName(contact, pushName); cn != "" {
					name = cn
				}
			}
			if name != "" {
				b.UpsertParticipant(jid, sjid, name, "member")
			}
		}

		if skippedNil > 0 || skippedOld > 0 || skippedEmpty > 0 {
			b.log.Debugf("  Conv %s: inserted %d, skipped %d nil, %d old, %d content-free", jid, convMsgCount, skippedNil, skippedOld, skippedEmpty)
		}

		// For DMs, resolve contact name from whatsmeow's contact store.
		if !isGroup && name == "" {
			var contact types.ContactInfo
			if parsedJID, parseErr := types.ParseJID(jid); parseErr == nil {
				parsedRaw, rawErr := types.ParseJID(rawJID)
				if rawErr != nil {
					parsedRaw = parsedJID
				}
				contact = b.lookupContact(parsedJID, parsedRaw)
			}
			name, _ = bestContactName(contact, "")
			// Fallback: check message push names (only from received messages, not sent)
			if name == "" {
				for _, histMsg := range msgs {
					wm := histMsg.GetMessage()
					if wm == nil {
						continue
					}
					// Skip messages sent by us
					if wm.GetKey() != nil && wm.GetKey().GetFromMe() {
						continue
					}
					pn := wm.GetPushName()
					if pn != "" {
						name = pn
						break
					}
				}
			}
		}

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
		b.UpsertConversation(jid, name, isGroup, lastMsgID, lastTS)

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

// historyEdit is an edit found in a history payload.
type historyEdit struct {
	targetID string
	edited   *waE2E.Message
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

// MergeLIDConversations folds conversation rows keyed by @lid JIDs into
// their phone-based rows. Duplicates appeared when an event path stored a
// LID key (history sync, the old push-name handler) while live messages
// used the resolved phone JID. Runs when app state sync lands the mappings
// (plus a post-connect fallback); unresolvable LIDs are left untouched and
// retried on the next run.
func (b *Bridge) MergeLIDConversations() {
	rows, err := b.msgDB.Query(`SELECT jid, COALESCE(name, ''), is_group, COALESCE(last_message_id, ''), last_timestamp, unread_count FROM conversations WHERE jid LIKE '%@lid'`)
	if err != nil {
		return
	}
	type lidRow struct {
		jid, name, lastMsgID string
		isGroup              bool
		lastTS               int64
		unread               int
	}
	var lids []lidRow
	for rows.Next() {
		var r lidRow
		var isGroupInt int
		if err := rows.Scan(&r.jid, &r.name, &isGroupInt, &r.lastMsgID, &r.lastTS, &r.unread); err != nil {
			continue
		}
		r.isGroup = isGroupInt == 1
		lids = append(lids, r)
	}
	rows.Close()

	merged := 0
	for _, r := range lids {
		pn := b.resolveLIDString(r.jid)
		if pn == r.jid {
			continue // mapping not known yet
		}
		// Re-key messages and participants, then merge the conversation row.
		// UpsertConversation fills the target's name/last-message only where
		// they're missing or older, which is exactly the merge we want.
		b.msgDB.Exec(`UPDATE messages SET conversation_jid = ? WHERE conversation_jid = ?`, pn, r.jid)
		b.msgDB.Exec(`UPDATE OR IGNORE participants SET conversation_jid = ? WHERE conversation_jid = ?`, pn, r.jid)
		b.msgDB.Exec(`DELETE FROM participants WHERE conversation_jid = ?`, r.jid)
		b.UpsertConversation(pn, r.name, r.isGroup, r.lastMsgID, r.lastTS)
		if r.unread > 0 {
			b.msgDB.Exec(`UPDATE conversations SET unread_count = unread_count + ? WHERE jid = ?`, r.unread, pn)
		}
		b.msgDB.Exec(`DELETE FROM conversations WHERE jid = ?`, r.jid)
		b.log.Infof("Merged LID conversation %s into %s", r.jid, pn)
		merged++
	}
	if merged > 0 {
		b.log.Infof("Merged %d LID conversations into phone-based rows", merged)
		b.BroadcastEvent("conversation_updated", struct{}{})
	}
}

// CanonicalizeSenderJIDs re-keys message, reaction, and participant sender JIDs
// from @lid to the resolved phone JID once the LID→PN mapping is known. This is
// the sender-side counterpart to MergeLIDConversations, which only re-keys
// conversation_jid — group chats live under @g.us, so a member stored under an
// @lid sender (history sync, older builds) was never reconciled and split from
// their phone-keyed live messages. Idempotent; runs in the same backfill cycle.
func (b *Bridge) CanonicalizeSenderJIDs() {
	rows, err := b.msgDB.Query(`SELECT DISTINCT sender_jid FROM messages WHERE sender_jid LIKE '%@lid'`)
	if err != nil {
		return
	}
	var lids []string
	for rows.Next() {
		var s string
		if rows.Scan(&s) == nil && s != "" {
			lids = append(lids, s)
		}
	}
	rows.Close()

	changed := 0
	for _, lid := range lids {
		pn := b.resolveLIDString(lid)
		if pn == lid {
			continue // mapping not known yet — retry on the next sync patch
		}
		// messages.sender_jid isn't unique, so a plain update is safe; the
		// PK-bearing tables use UPDATE OR IGNORE + delete to fold duplicates.
		b.msgDB.Exec(`UPDATE messages SET sender_jid = ? WHERE sender_jid = ?`, pn, lid)
		b.msgDB.Exec(`UPDATE OR IGNORE reactions SET sender_jid = ? WHERE sender_jid = ?`, pn, lid)
		b.msgDB.Exec(`DELETE FROM reactions WHERE sender_jid = ?`, lid)
		b.msgDB.Exec(`UPDATE OR IGNORE participants SET jid = ? WHERE jid = ?`, pn, lid)
		b.msgDB.Exec(`DELETE FROM participants WHERE jid = ?`, lid)
		changed++
	}
	if changed > 0 {
		b.log.Infof("Canonicalized %d LID sender(s) to phone JIDs", changed)
		b.BroadcastEvent("conversation_updated", struct{}{})
	}
}

// BackfillParticipantNames names group participants from whatsmeow's contact
// store — the group-chat counterpart to BackfillContactNames (DM conversation
// names). Runs on the same triggers so initial sync, live messages, and
// everyday app-state patches all converge group sender names the same way.
func (b *Bridge) BackfillParticipantNames() {
	rows, err := b.msgDB.Query(`SELECT jid FROM conversations WHERE is_group = 1`)
	if err != nil {
		return
	}
	var groups []string
	for rows.Next() {
		var j string
		if rows.Scan(&j) == nil && j != "" {
			groups = append(groups, j)
		}
	}
	rows.Close()
	for _, g := range groups {
		b.ResolveGroupParticipants(g)
	}
}

func (b *Bridge) handleGroupInfo(evt *events.GroupInfo) {
	jid := evt.JID.String()
	if evt.Name != nil {
		b.setGroupName(jid, evt.Name.Name)
		b.UpdateConversationName(jid, evt.Name.Name)
	}
	b.BroadcastEvent("conversation_updated", ConversationUpdatedEvent{JID: jid})
}

// groupName returns the group's subject, hitting the server at most once per
// hour per group — GetGroupInfo is a network round-trip and groups rarely
// rename. Rename events (handleGroupInfo) refresh the cache immediately.
func (b *Bridge) groupName(jid types.JID) string {
	key := jid.String()
	b.groupNameMu.Lock()
	cached, ok := b.groupNames[key]
	b.groupNameMu.Unlock()
	if ok && time.Since(cached.fetched) < time.Hour {
		return cached.name
	}

	groupInfo, err := b.client.GetGroupInfo(context.Background(), jid)
	if err != nil {
		// Don't cache failures — retry on the next message.
		b.log.Warnf("GetGroupInfo failed for %s: %v", key, err)
		return ""
	}
	if groupInfo.Name == "" {
		b.log.Warnf("GetGroupInfo returned empty name for %s", key)
	}
	b.setGroupName(key, groupInfo.Name)
	return groupInfo.Name
}

func (b *Bridge) setGroupName(jid, name string) {
	b.groupNameMu.Lock()
	b.groupNames[jid] = cachedGroupName{name: name, fetched: time.Now()}
	b.groupNameMu.Unlock()
}

// Helper functions

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

// ResolveGroupParticipants fills display names for every distinct sender in a
// group's stored messages from whatsmeow's contact store (address-book name
// preferred, observed push name as fallback). This lets historical senders —
// stored before any live message taught us their name, or before the contact
// store was populated — render names without re-pairing. Cheap and idempotent,
// so it's safe to call on every group open.
func (b *Bridge) ResolveGroupParticipants(groupJID string) {
	// Purge number-placeholder names an earlier build wrote (display_name that
	// is a bare "+<digits>"), which shadowed real push/contact names. Removing
	// them lets the real name repopulate below or via a future live message; the
	// UI falls back to the number on its own when a participant has no row.
	purged := b.purgeNumberPlaceholders(groupJID)

	rows, err := b.msgDB.Query(
		`SELECT DISTINCT sender_jid FROM messages WHERE conversation_jid = ? AND is_from_me = 0`,
		groupJID,
	)
	if err != nil {
		b.log.Errorf("ResolveGroupParticipants query failed: %v", err)
		return
	}
	var senders []string
	for rows.Next() {
		var sjid string
		if scanErr := rows.Scan(&sjid); scanErr == nil && sjid != "" {
			senders = append(senders, sjid)
		}
	}
	rows.Close()

	updated := purged > 0
	for _, sjid := range senders {
		parsed, perr := types.ParseJID(sjid)
		if perr != nil {
			continue
		}
		resolved := b.resolveLIDJID(parsed)
		name, _ := bestContactName(b.lookupContact(resolved, parsed), "")
		// Only write real names. Never fall back to the phone number: this runs
		// on every group open and overwrites existing rows, so a number fallback
		// would clobber a good push name the live handler captured (members not
		// in the address book have no contact-store name). When there's no real
		// name, leave the row alone — the UI already falls back to the number.
		if name != "" {
			if upErr := b.UpsertParticipant(groupJID, sjid, name, "member"); upErr == nil {
				updated = true
			}
		}
	}
	if updated {
		b.BroadcastEvent("conversation_updated", ConversationUpdatedEvent{JID: groupJID})
	}
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

// buildMessageRow assembles the MessageRow fields shared by the live message
// handler and history sync: content classification, raw proto (kept for
// later media download), reply context, and media mime type. msg is the
// content message (see contentMessage).
func (b *Bridge) buildMessageRow(msg *waE2E.Message, id, convJID, senderJID string, ts int64, isFromMe bool, status string) *MessageRow {
	contentType, textBody, _ := extractContent(msg)
	textBody = b.resolveMentions(msg, convJID, textBody)
	rawProto, _ := proto.Marshal(msg)

	var replyToID sql.NullString
	if ctx := getContextInfo(msg); ctx != nil && ctx.GetStanzaID() != "" {
		replyToID = sql.NullString{String: ctx.GetStanzaID(), Valid: true}
	}

	var mediaMime sql.NullString
	if mime := getMediaMime(msg); mime != "" {
		mediaMime = sql.NullString{String: mime, Valid: true}
	}

	return &MessageRow{
		ID:              id,
		ConversationJID: convJID,
		SenderJID:       senderJID,
		Timestamp:       ts,
		ContentType:     contentType,
		TextBody:        sql.NullString{String: textBody, Valid: textBody != ""},
		MediaMime:       mediaMime,
		ReplyToID:       replyToID,
		IsFromMe:        isFromMe,
		Status:          status,
		RawProto:        rawProto,
	}
}

func getMediaMime(msg *waE2E.Message) string {
	if img := msg.GetImageMessage(); img != nil {
		return img.GetMimetype()
	}
	if vid := videoOf(msg); vid != nil {
		return vid.GetMimetype()
	}
	if aud := msg.GetAudioMessage(); aud != nil {
		return aud.GetMimetype()
	}
	if doc := msg.GetDocumentMessage(); doc != nil {
		return doc.GetMimetype()
	}
	if stk := msg.GetStickerMessage(); stk != nil {
		return stk.GetMimetype()
	}
	return ""
}

func getInlineThumbnail(msg *waE2E.Message) []byte {
	if img := msg.GetImageMessage(); img != nil {
		return img.GetJPEGThumbnail()
	}
	if vid := videoOf(msg); vid != nil {
		return vid.GetJPEGThumbnail()
	}
	if doc := msg.GetDocumentMessage(); doc != nil {
		return doc.GetJPEGThumbnail()
	}
	return nil
}

// applyEdit replaces a stored message's text with its edited version. An edit
// whose new text can't be extracted is ignored rather than blanking the row.
func (b *Bridge) applyEdit(chatJID, targetID string, edited *waE2E.Message) {
	_, newText, _ := extractContent(edited)
	if newText == "" {
		b.log.Warnf("Ignoring edit of %s in %s: no text in edited content", targetID, chatJID)
		return
	}
	newText = b.resolveMentions(edited, chatJID, newText)
	if err := b.UpdateMessageEdit(targetID, newText); err != nil {
		b.log.Errorf("Failed to apply edit of %s: %v", targetID, err)
		return
	}
	b.BroadcastEvent("message_updated", MessageUpdatedEvent{
		ConversationJID: chatJID,
		MessageID:       targetID,
	})
}

func (b *Bridge) buildTextMessage(text string, replyToID string) *waE2E.Message {
	if replyToID == "" {
		return &waE2E.Message{
			Conversation: proto.String(text),
		}
	}

	return &waE2E.Message{
		ExtendedTextMessage: &waE2E.ExtendedTextMessage{
			Text: proto.String(text),
			ContextInfo: &waE2E.ContextInfo{
				StanzaID: proto.String(replyToID),
			},
		},
	}
}

// videoOf returns a message's video, including a round "video note" (PTV).
func videoOf(msg *waE2E.Message) *waE2E.VideoMessage {
	if vid := msg.GetVideoMessage(); vid != nil {
		return vid
	}
	return msg.GetPtvMessage()
}

// saveInlineThumbnail stores a media message's embedded JPEG preview.
func (b *Bridge) saveInlineThumbnail(msgID string, msg *waE2E.Message) {
	if thumb := getInlineThumbnail(msg); len(thumb) > 0 {
		if thumbPath := b.saveThumbnail(msgID, thumb); thumbPath != "" {
			b.UpdateThumbnailPath(msgID, thumbPath)
		}
	}
}

// requestFromPhone asks the primary device to resend a message it masked
// from linked devices (some business messages are). The copy arrives as a
// normal message event with UnavailableRequestID set.
func (b *Bridge) requestFromPhone(info types.MessageInfo) {
	req := b.client.BuildUnavailableMessageRequest(info.Chat, info.Sender, info.ID)
	if _, err := b.client.SendPeerMessage(context.Background(), req); err != nil {
		b.log.Warnf("Failed to request %s from phone: %v", info.ID, err)
	}
}

// autoDownloadMedia downloads small media (stickers, audio) automatically.
func (b *Bridge) autoDownloadMedia(msgID string, msg *waE2E.Message) {
	path, err := b.DownloadMedia(msgID, msg)
	if err != nil {
		b.log.Warnf("Auto-download failed for %s: %v", msgID, err)
	} else {
		b.log.Debugf("Auto-downloaded %s to %s", msgID, path)
	}
}
