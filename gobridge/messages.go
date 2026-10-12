package main

import (
	"context"
	"database/sql"
	"os"
	"time"

	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

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
	if kind != kindContent {
		// Nothing to show under this ID: a placeholder stored for it when it
		// couldn't be decrypted has nothing to become.
		b.dropPlaceholder(chatJID, evt.Info)
	}
	switch kind {
	case kindReaction:
		b.UpsertReaction(targetID, senderJID, msg.GetReactionMessage().GetText(), ts)
		b.BroadcastEvent("message_updated", MessageUpdatedEvent{
			ConversationJID: chatJID,
			MessageID:       targetID,
		})
		return
	case kindRevoke:
		b.applyRevoke(chatJID, evt.Info, targetID)
		return
	case kindEdit:
		b.applyEdit(chatJID, targetID, msg.GetProtocolMessage().GetEditedMessage(), b.authorOf(evt.Info))
		return
	case kindTimer:
		// Only a DM's two people set its timer this way; a group's comes
		// from the server (group info), not from any member's say-so.
		if !isGroupJID(chatJID) {
			t := settingMessageTimer(msg.GetProtocolMessage(), ts)
			b.applyTimer(chatJID, timerReport{timerSetting: t, at: ts})
		}
		return
	case kindDrop:
		b.log.Debugf("Skipping content-free message %s in %s", msgID, chatJID)
		return
	}

	content := contentMessage(msg)
	// The chat's timer as this message has it. A change it reveals goes in
	// the chat before the message itself.
	if t, ok := contextTimer(content); ok {
		b.applyTimer(chatJID, timerReport{timerSetting: t, at: ts})
	}
	row := b.buildMessageRow(content, msgID, chatJID, senderJID, ts, isFromMe, "sent")
	viewOnce := applyViewOnce(row, evt.IsViewOnce, content)
	if row.ExpiresAt > 0 && row.ExpiresAt <= time.Now().Unix() {
		// Delivered after it was due to disappear (Photon was offline).
		b.log.Debugf("Skipping message %s in %s: already disappeared", msgID, chatJID)
		b.dropPlaceholder(chatJID, evt.Info)
		return
	}
	if row.ContentType == "call" {
		// The phone's log entry for a call, which Photon may already hold as
		// a row from the call's own events. Keyed by call ID like those
		// (and history-sync call stubs, on the same premise that the entry
		// carries the call's ID), whichever arrives second is a redelivery.
		// Call events own a call's unread count, so the entry is stored read.
		b.dropPlaceholder(chatJID, evt.Info)
		row.ID, row.Status = callRowID(msgID), "read"
		msgID = row.ID
	} else if b.replacePlaceholder(row, evt.Info, evt.UnavailableRequestID != "") {
		// The message a placeholder stood for (resent by its sender after a
		// decryption failure, or by the phone): the row becomes it in place.
		// It was counted and announced as the placeholder.
		if !viewOnce {
			b.fetchMediaExtras(msgID, row.ContentType, content)
		}
		b.BroadcastEvent("message_updated", MessageUpdatedEvent{ConversationJID: chatJID, MessageID: msgID})
		b.BroadcastEvent("message_replaced", b.messageReplacedEvent(row))
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

	// View-once media is never downloaded or previewed.
	if !viewOnce {
		b.fetchMediaExtras(msgID, contentType, content)
	}

	if content.GetPlaceholderMessage() != nil && !isFromMe {
		go b.requestFromPhone(evt.Info)
	}

	b.upsertMessageConversation(evt.Info, chatJID, senderJID, msgID, ts)

	// A call-log entry in the chat. Live calls are tracked from call events,
	// which own the unread count and notification, so this only shows the
	// entry.
	if contentType == "call" {
		b.log.Infof("Call log message %s in %s: %s", msgID, chatJID, textBody)
		b.BroadcastEvent("message_updated", MessageUpdatedEvent{ConversationJID: chatJID, MessageID: msgID})
		return
	}
	b.announceNewMessage(chatJID, msgID, senderJID, textBody, contentType, isFromMe, ts)
}

// fetchMediaExtras saves a stored media message's inline preview and
// downloads the small media Photon keeps at hand (stickers, audio).
func (b *Bridge) fetchMediaExtras(msgID, contentType string, content *waE2E.Message) {
	b.saveInlineThumbnail(msgID, content)
	if contentType == "sticker" || contentType == "audio" {
		go b.autoDownloadMedia(msgID, content)
	}
}

// upsertMessageConversation names and bumps the chat of a message just
// stored. A verified or push name the message carries is the other
// person's, never the user's own.
func (b *Bridge) upsertMessageConversation(info types.MessageInfo, chatJID, senderJID, msgID string, ts int64) {
	verifiedName, pushName := "", ""
	if !info.IsFromMe {
		pushName = info.PushName
		if info.VerifiedName != nil {
			verifiedName = info.VerifiedName.Details.GetVerifiedName()
		}
	}
	var name rankedName
	if info.Chat.Server == types.GroupServer {
		// The group's subject from its info (cached — one network fetch per
		// group per hour, not per message).
		name = rankedName{name: cleanName(b.groupName(info.Chat)), rank: rankSubject}
		if !info.IsFromMe {
			b.UpsertParticipant(chatJID, senderJID, b.memberName(info.Sender, verifiedName, pushName))
		}
	} else {
		name = personName(b.lookupContact(info.Chat), verifiedName, pushName)
	}
	b.UpsertConversation(chatJID, name, msgID, ts)
}

// announceNewMessage counts a message just stored as unread, unless the
// user sent it, unarchives its chat if the account wants that, and tells
// Kotlin (new_message).
func (b *Bridge) announceNewMessage(chatJID, msgID, senderJID, text, contentType string, isFromMe bool, ts int64) {
	if !isFromMe {
		b.IncrementUnread(chatJID)
		b.unarchiveForMessage(chatJID, ts)
	}
	b.BroadcastEvent("new_message", b.newMessageEvent(chatJID, msgID, senderJID, text, contentType, isFromMe))
}

// newMessageEvent is new_message's payload for a stored message, with the
// names as stored just now: the chat's, and for a message the user didn't
// send, the sender's — their member name in a group, the chat's in a DM.
func (b *Bridge) newMessageEvent(chatJID, msgID, senderJID, text, contentType string, isFromMe bool) NewMessageEvent {
	evt := NewMessageEvent{
		ConversationJID:  chatJID,
		MessageID:        msgID,
		IsGroup:          isGroupJID(chatJID),
		ConversationName: b.storedConversationName(chatJID).name,
		SenderJID:        senderJID,
		TextBody:         text,
		ContentType:      contentType,
		IsFromMe:         isFromMe,
	}
	switch {
	case isFromMe:
	case evt.IsGroup:
		evt.SenderName = b.ParticipantName(chatJID, senderJID)
	default:
		evt.SenderName = evt.ConversationName
	}
	return evt
}

// applyRevoke blanks a message deleted for everyone. If it was the last unread
// message in its chat, Kotlin uses message_revoked's unread_count to withdraw
// the notification that announced it. WhatsApp never sends a revoke again, so
// one that fails on the database (busy, say) is retried in the background
// rather than dropped.
func (b *Bridge) applyRevoke(chatJID string, info types.MessageInfo, targetID string) {
	res, found, err := b.revoke(chatJID, info, targetID)
	if err == nil {
		b.revoked(chatJID, info, targetID, res, found)
		return
	}
	b.log.Warnf("Revoke of %s in %s failed, retrying: %v", targetID, chatJID, err)
	go func() {
		err := retry(revokeAttempts, b.retryDelay, func() error {
			var err error
			res, found, err = b.revoke(chatJID, info, targetID)
			return err
		})
		if err != nil {
			b.log.Errorf("Gave up on revoke of %s in %s: %v", targetID, chatJID, err)
			return
		}
		b.revoked(chatJID, info, targetID, res, found)
	}()
}

// revokeAttempts bounds the tries at a revoke that fails on the database.
const revokeAttempts = 5

// revoked finishes a revoke that reached the database: removes the media it
// orphaned and tells Kotlin.
func (b *Bridge) revoked(chatJID string, info types.MessageInfo, targetID string, res RevokeResult, found bool) {
	if !found {
		b.log.Debugf("Ignoring revoke of %s in %s by %s: no such message of theirs", targetID, chatJID, info.Sender)
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

// revoke applies a "delete for everyone" only from someone entitled to it:
// the message's sender, or — in a group — an admin (or the user's own
// devices). The server can't check, as the revoke is end-to-end encrypted,
// and message IDs are visible to every member of a group.
func (b *Bridge) revoke(chatJID string, info types.MessageInfo, targetID string) (RevokeResult, bool, error) {
	by := b.authorOf(info)
	res, found, err := b.RevokeMessage(targetID, chatJID, &by)
	if found || err != nil {
		return res, found, err
	}
	if info.Chat.Server == types.GroupServer && (info.IsFromMe || b.isGroupAdmin(info.Chat, info.Sender, info.SenderAlt)) {
		return b.RevokeMessage(targetID, chatJID, nil)
	}
	return RevokeResult{}, false, nil
}

// author identifies who sent an edit or revoke, to match it against the
// stored message it targets.
type author struct {
	isMe bool     // one of the user's own devices
	keys []string // sender_jid forms the person's stored messages may carry
}

func (b *Bridge) authorOf(info types.MessageInfo) author {
	if info.IsFromMe {
		return author{isMe: true}
	}
	keys := []string{b.canonicalSenderJID(info.Sender), info.Sender.ToNonAD().String()}
	if !info.SenderAlt.IsEmpty() {
		keys = append(keys, b.canonicalSenderJID(info.SenderAlt), info.SenderAlt.ToNonAD().String())
	}
	return author{keys: keys}
}

// sqlClause restricts a messages query to rows this author sent.
func (a author) sqlClause() (string, []interface{}) {
	if a.isMe {
		return "is_from_me = 1", nil
	}
	args := make([]interface{}, len(a.keys))
	for i, k := range a.keys {
		args[i] = k
	}
	return "is_from_me = 0 AND sender_jid IN (" + sqlInList(len(a.keys)) + ")", args
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
	// A place's map link (see location).
	var mediaURL sql.NullString
	if _, geo, ok := location(msg); ok {
		mediaURL = sql.NullString{String: geo, Valid: true}
	}

	return &MessageRow{
		ID:              id,
		ConversationJID: convJID,
		SenderJID:       senderJID,
		Timestamp:       ts,
		ContentType:     contentType,
		TextBody:        sql.NullString{String: textBody, Valid: textBody != ""},
		MediaURL:        mediaURL,
		MediaMime:       mediaMime,
		ReplyToID:       replyToID,
		IsFromMe:        isFromMe,
		Status:          status,
		RawProto:        rawProto,
		ExpiresAt:       messageExpiry(msg, ts),
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

// applyEdit replaces a stored message's text with its edited version — only
// if the editor sent it, in this chat (the server can't check; message IDs are
// visible to every group member). An edit whose new text can't be extracted
// is ignored rather than blanking the row.
func (b *Bridge) applyEdit(chatJID, targetID string, edited *waE2E.Message, by author) {
	_, newText, _ := extractContent(edited)
	if newText == "" {
		b.log.Warnf("Ignoring edit of %s in %s: no text in edited content", targetID, chatJID)
		return
	}
	newText = b.resolveMentions(edited, chatJID, newText)
	applied, err := b.UpdateMessageEdit(targetID, chatJID, newText, &by)
	if err != nil {
		b.log.Errorf("Failed to apply edit of %s: %v", targetID, err)
		return
	}
	if !applied {
		b.log.Debugf("Ignoring edit of %s in %s: no such message from the editor", targetID, chatJID)
		return
	}
	b.BroadcastEvent("message_updated", MessageUpdatedEvent{
		ConversationJID: chatJID,
		MessageID:       targetID,
	})
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
