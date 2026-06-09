package main

import (
	"context"
	"database/sql"
	"fmt"
	"time"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"google.golang.org/protobuf/proto"
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
		b.BroadcastEvent("connection_state", ConnectionStateEvent{State: "connected"})
		b.client.SendPresence(context.Background(), types.PresenceAvailable)
		// Fallback reconcile: AppStateSyncComplete normally triggers the
		// merge+backfill, but if the event never fires (already-synced
		// session) this single delayed run still converges names.
		go func() {
			time.Sleep(60 * time.Second)
			b.MergeLIDConversations()
			b.BackfillContactNames()
		}()
	case *events.AppStateSyncComplete:
		// Contact names and LID mappings arrive via app state sync — run the
		// reconcile as soon as a patch lands instead of polling on a timer.
		// Both operations are idempotent, so running per-patch is safe.
		b.log.Infof("App state sync complete: %s", v.Name)
		go func() {
			b.MergeLIDConversations()
			b.BackfillContactNames()
		}()
	case *events.Disconnected:
		b.log.Infof("Disconnected from WhatsApp")
		b.BroadcastEvent("connection_state", ConnectionStateEvent{State: "disconnected"})
	case *events.LoggedOut:
		b.log.Infof("Logged out: %v", v.Reason)
		b.BroadcastEvent("connection_state", ConnectionStateEvent{State: "logged_out"})
	case *events.PairSuccess:
		b.log.Infof("Pair success: %s on %s", v.ID, v.Platform)
		b.BroadcastEvent("pair_success", PairSuccessEvent{
			JID:      v.ID.String(),
			Platform: v.Platform,
		})
	case *events.PushName:
		b.handlePushName(v)
	case *events.CallOffer:
		b.handleCallOffer(v)
	case *events.GroupInfo:
		b.handleGroupInfo(v)
	case *events.Mute:
		b.handleMute(v)
	}
}

// handleMute syncs WhatsApp mute state from the primary device.
// MuteEndTimestamp==0 means "mute until unmuted"; a future value means
// "mute until that time"; a past value means the mute has expired.
func (b *Bridge) handleMute(evt *events.Mute) {
	// Resolve LID to phone JID so keys match our conversations table.
	jid := b.resolveLIDJID(evt.JID).String()

	muted := false
	if evt.Action != nil && evt.Action.GetMuted() {
		endTs := evt.Action.GetMuteEndTimestamp()
		if endTs == 0 || time.Now().Unix() < endTs {
			muted = true
		}
	}
	b.UpdateMute(jid, muted)
	b.BroadcastEvent("conversation_updated", ConversationUpdatedEvent{JID: jid})
	b.log.Infof("Mute update: %s muted=%v", jid, muted)
}

func (b *Bridge) handleMessage(evt *events.Message) {
	msg := evt.Message
	if msg == nil {
		return
	}

	// Resolve LID JIDs to phone-based JIDs for consistent conversation keys
	resolvedChat := b.resolveLIDJID(evt.Info.Chat)
	chatJID := resolvedChat.String()
	senderJID := evt.Info.Sender.String()
	isFromMe := evt.Info.IsFromMe
	msgID := evt.Info.ID
	ts := evt.Info.Timestamp.Unix()

	// Check for reaction
	if reaction := msg.GetReactionMessage(); reaction != nil {
		targetID := reaction.GetKey().GetID()
		emoji := reaction.GetText()
		b.UpsertReaction(targetID, senderJID, emoji, ts)
		b.BroadcastEvent("message_updated", MessageUpdatedEvent{
			ConversationJID: chatJID,
			MessageID:       targetID,
		})
		return
	}

	// Check for edit
	if evt.IsEdit {
		editedID := getEditTargetID(msg)
		if editedID != "" {
			_, newText := classifyMessage(msg)
			b.UpdateMessageEdit(editedID, newText, 1) // simplified: just set version to 1
			b.BroadcastEvent("message_updated", MessageUpdatedEvent{
				ConversationJID: chatJID,
				MessageID:       editedID,
			})
			return
		}
	}

	// Skip empty messages (e.g., sender key distribution in groups)
	if msg.GetConversation() == "" &&
		msg.GetExtendedTextMessage() == nil &&
		msg.GetImageMessage() == nil &&
		msg.GetVideoMessage() == nil &&
		msg.GetAudioMessage() == nil &&
		msg.GetDocumentMessage() == nil &&
		msg.GetStickerMessage() == nil &&
		msg.GetLocationMessage() == nil &&
		msg.GetContactMessage() == nil {
		b.log.Debugf("Skipping empty message %s in %s (sender key distribution or protocol)", msgID, chatJID)
		return
	}

	row := buildMessageRow(msg, msgID, chatJID, senderJID, ts, isFromMe, "sent")
	b.InsertMessage(row)
	contentType, textBody := row.ContentType, row.TextBody.String

	// Extract and save inline thumbnail for media messages
	if thumb := getInlineThumbnail(msg); thumb != nil && len(thumb) > 0 {
		thumbPath := b.saveThumbnail(msgID, thumb)
		if thumbPath != "" {
			b.UpdateThumbnailPath(msgID, thumbPath)
		}
	}

	// Auto-download small media (stickers, audio)
	if contentType == "sticker" || contentType == "audio" {
		go b.autoDownloadMedia(msgID, msg)
	}

	// Update conversation
	name := ""
	nameIsAuthoritative := false
	isGroup := evt.Info.Chat.Server == types.GroupServer
	if isGroup {
		// For groups, get the group name from group info (cached — one
		// network fetch per group per hour, not per message)
		name = b.groupName(evt.Info.Chat)

		// Store sender as participant with their display name
		senderDisplayName := evt.Info.PushName
		if senderDisplayName == "" && evt.Info.Sender.Server == types.HiddenUserServer {
			if pn, err := b.client.Store.LIDs.GetPNForLID(context.Background(), evt.Info.Sender); err == nil && !pn.IsEmpty() {
				senderDisplayName = "+" + pn.User
			}
		}
		if senderDisplayName != "" {
			b.UpsertParticipant(chatJID, senderJID, senderDisplayName, "member")
		}
	} else {
		// Only use the event push name from the OTHER person, not ourselves.
		eventPushName := ""
		if !isFromMe {
			eventPushName = evt.Info.PushName
		}
		contact := b.lookupContact(resolvedChat, evt.Info.Chat)
		name, nameIsAuthoritative = bestContactName(contact, eventPushName)
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

func (b *Bridge) handleReceipt(evt *events.Receipt) {
	// Resolve LID → PN so the JID matches the conversation row written by
	// handleMessage (which stores conversations under the phone-based JID).
	// Without this, ReadSelf receipts for LID chats fail to clear unread.
	chatJID := b.resolveLIDJID(evt.Chat).String()

	status := ""
	switch evt.Type {
	case types.ReceiptTypeRead, types.ReceiptTypeReadSelf:
		status = "read"
		// Reset unread count when messages are read (including on other devices).
		b.ResetUnread(chatJID)
	case types.ReceiptTypeDelivered:
		status = "delivered"
	default:
		return
	}

	ids := make([]string, len(evt.MessageIDs))
	for i, id := range evt.MessageIDs {
		ids[i] = string(id)
	}
	b.UpdateMessageStatusBatch(ids, status)

	b.BroadcastEvent("receipt", ReceiptEvent{
		ConversationJID: chatJID,
		MessageIDs:      ids,
		Type:            status,
	})
}

func (b *Bridge) handleChatPresence(evt *events.ChatPresence) {
	composing := evt.State == types.ChatPresenceComposing
	b.BroadcastEvent("typing", TypingEvent{
		JID:       evt.Chat.String(),
		SenderJID: evt.Sender.String(),
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
		isGroup := conv.GetIsDefaultSubgroup() || (len(conv.GetParticipant()) > 0)

		msgs := conv.GetMessages()
		b.log.Debugf("  Conv %s: %d messages in history", jid, len(msgs))
		convMsgCount := 0
		skippedNil := 0
		skippedOld := 0
		var pending []*MessageRow
		for _, histMsg := range msgs {
			// Respect retention limits
			if cfg.MaxMessages > 0 && convMsgCount >= cfg.MaxMessages {
				break
			}

			wm := histMsg.GetMessage()
			if wm == nil {
				skippedNil++
				continue
			}
			if wm.GetMessage() == nil {
				skippedNil++
				continue
			}

			msg := wm.GetMessage()
			info := wm.GetKey()
			if info == nil {
				skippedNil++
				continue
			}

			ts := int64(wm.GetMessageTimestamp())
			// Skip messages older than retention window
			if cutoffTS > 0 && ts < cutoffTS {
				skippedOld++
				continue
			}

			msgID := info.GetID()
			isFromMe := info.GetFromMe()
			// ts already declared above for cutoff check
			senderJID := jid
			if !isFromMe && info.GetParticipant() != "" {
				senderJID = info.GetParticipant()
			}

			pending = append(pending, buildMessageRow(msg, msgID, jid, senderJID, ts, isFromMe, "read"))
			totalMsgs++
			convMsgCount++
		}
		// One transaction per conversation — per-row implicit transactions
		// mean thousands of WAL commits during initial pairing.
		b.InsertMessages(pending)

		if skippedNil > 0 || skippedOld > 0 {
			b.log.Debugf("  Conv %s: inserted %d, skipped %d nil, %d old", jid, convMsgCount, skippedNil, skippedOld)
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

		// Upsert conversation after processing messages
		lastTS := int64(0)
		lastMsgID := ""
		if len(msgs) > 0 {
			last := msgs[len(msgs)-1].GetMessage()
			if last != nil {
				lastTS = int64(last.GetMessageTimestamp())
				if last.GetKey() != nil {
					lastMsgID = last.GetKey().GetID()
				}
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

	rows, err := b.msgDB.Query(`SELECT jid, COALESCE(name, '') FROM conversations WHERE jid LIKE '%@s.whatsapp.net'`)
	if err != nil {
		return
	}
	defer rows.Close()

	updated := 0
	for rows.Next() {
		var jid, currentName string
		rows.Scan(&jid, &currentName)

		parsedJID, parseErr := types.ParseJID(jid)
		if parseErr != nil {
			continue
		}
		contact, ok := contacts[parsedJID]
		if !ok {
			// Fallback: single lookup (covers stores where GetAllContacts
			// lags behind individual sync entries).
			if c, err := b.client.Store.Contacts.GetContact(context.Background(), parsedJID); err == nil && c.Found {
				contact = c
			}
		}

		name, authoritative := bestContactName(contact, "")
		if !authoritative && currentName != "" {
			continue // already named and no address book entry — leave it
		}

		if name != "" && name != currentName {
			b.UpdateConversationName(jid, name)
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

func (b *Bridge) handleCallOffer(evt *events.CallOffer) {
	b.BroadcastEvent("call_offer", CallOfferEvent{
		FromJID: evt.CallCreator.String(),
		CallID:  evt.CallID,
		IsVideo: false, // CallOffer doesn't expose IsVideo directly
	})
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
// (synced from the primary device) is authoritative — it may overwrite an
// existing name. Push names (self-chosen, mutable) and business names only
// fill in when there's nothing better; eventPushName is the push name carried
// by a live message event ("" when not applicable, e.g. our own messages).
func bestContactName(contact types.ContactInfo, eventPushName string) (name string, authoritative bool) {
	switch {
	case contact.FullName != "":
		return contact.FullName, true
	case eventPushName != "":
		return eventPushName, false
	case contact.PushName != "":
		return contact.PushName, false
	case contact.BusinessName != "":
		return contact.BusinessName, false
	}
	return "", false
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

// buildMessageRow assembles the MessageRow fields shared by the live message
// handler and history sync: content classification, raw proto (kept for
// later media download), reply context, and media mime type.
func buildMessageRow(msg *waE2E.Message, id, convJID, senderJID string, ts int64, isFromMe bool, status string) *MessageRow {
	contentType, textBody := classifyMessage(msg)
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

func classifyMessage(msg *waE2E.Message) (contentType string, textBody string) {
	switch {
	case msg.GetConversation() != "":
		return "text", msg.GetConversation()
	case msg.GetExtendedTextMessage() != nil:
		return "text", msg.GetExtendedTextMessage().GetText()
	case msg.GetImageMessage() != nil:
		return "image", msg.GetImageMessage().GetCaption()
	case msg.GetVideoMessage() != nil:
		return "video", msg.GetVideoMessage().GetCaption()
	case msg.GetAudioMessage() != nil:
		return "audio", ""
	case msg.GetDocumentMessage() != nil:
		return "document", msg.GetDocumentMessage().GetFileName()
	case msg.GetStickerMessage() != nil:
		return "sticker", ""
	case msg.GetLocationMessage() != nil:
		return "location", fmt.Sprintf("%.6f, %.6f", msg.GetLocationMessage().GetDegreesLatitude(), msg.GetLocationMessage().GetDegreesLongitude())
	case msg.GetContactMessage() != nil:
		return "contact", msg.GetContactMessage().GetDisplayName()
	default:
		return "text", ""
	}
}

func getContextInfo(msg *waE2E.Message) *waE2E.ContextInfo {
	if ext := msg.GetExtendedTextMessage(); ext != nil {
		return ext.GetContextInfo()
	}
	if img := msg.GetImageMessage(); img != nil {
		return img.GetContextInfo()
	}
	if vid := msg.GetVideoMessage(); vid != nil {
		return vid.GetContextInfo()
	}
	if aud := msg.GetAudioMessage(); aud != nil {
		return aud.GetContextInfo()
	}
	if doc := msg.GetDocumentMessage(); doc != nil {
		return doc.GetContextInfo()
	}
	if stk := msg.GetStickerMessage(); stk != nil {
		return stk.GetContextInfo()
	}
	return nil
}

func getMediaMime(msg *waE2E.Message) string {
	if img := msg.GetImageMessage(); img != nil {
		return img.GetMimetype()
	}
	if vid := msg.GetVideoMessage(); vid != nil {
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
	if vid := msg.GetVideoMessage(); vid != nil {
		return vid.GetJPEGThumbnail()
	}
	if doc := msg.GetDocumentMessage(); doc != nil {
		return doc.GetJPEGThumbnail()
	}
	return nil
}

func getEditTargetID(msg *waE2E.Message) string {
	if edit := msg.GetProtocolMessage(); edit != nil {
		if edit.GetKey() != nil {
			return edit.GetKey().GetID()
		}
	}
	return ""
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

// autoDownloadMedia downloads small media (stickers, audio) automatically.
func (b *Bridge) autoDownloadMedia(msgID string, msg *waE2E.Message) {
	path, err := b.DownloadMedia(msgID, msg)
	if err != nil {
		b.log.Warnf("Auto-download failed for %s: %v", msgID, err)
	} else {
		b.log.Debugf("Auto-downloaded %s to %s", msgID, path)
	}
}
