package main

import (
	"fmt"
	"strings"

	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"google.golang.org/protobuf/reflect/protoreflect"
)

// Placeholder bodies for messages Photon can't render. They're stored as text
// so every layout shows them without UI changes.
const (
	unsupportedText = "[ unsupported message ]"
	businessText    = "[ business message ]"
	phoneOnlyText   = "[ message only visible on your phone ]"
)

// maxWrapDepth bounds wrapper recursion; real messages nest two or three deep.
const maxWrapDepth = 8

// contentFreeFields are waE2E.Message fields that carry nothing to show on
// their own: key distribution, protocol/app-state traffic, poll votes,
// reactions (handled separately), pins and other metadata. A message made only
// of these is dropped; any other field Photon doesn't understand gets a
// placeholder instead of silently vanishing.
var contentFreeFields = map[protoreflect.Name]bool{
	"messageContextInfo":                         true,
	"senderKeyDistributionMessage":               true,
	"fastRatchetKeySenderKeyDistributionMessage": true,
	"protocolMessage":                            true,
	"pollUpdateMessage":                          true,
	"pollAddOptionMessage":                       true,
	"reactionMessage":                            true,
	"encReactionMessage":                         true,
	"encEventResponseMessage":                    true,
	"encCommentMessage":                          true,
	"secretEncryptedMessage":                     true,
	"keepInChatMessage":                          true,
	"pinInChatMessage":                           true,
	"stickerSyncRmrMessage":                      true,
	"messageHistoryBundle":                       true,
	"messageHistoryNotice":                       true,
	"groupRootKeyShare":                          true,
	"rootSecretDistributeMessage":                true,
	// An album header only announces how many images follow; the images
	// arrive as their own messages.
	"albumMessage": true,
	// Call/chat metadata stanzas, not the calls themselves.
	"call": true,
	"chat": true,
	// Only present when UnwrapRaw found no inner message.
	"deviceSentMessage": true,
}

// messageKind is what a message event does to the store.
type messageKind int

const (
	kindDrop     messageKind = iota // nothing to show (see contentFreeFields)
	kindContent                     // a message to store
	kindReaction                    // a reaction to targetID
	kindRevoke                      // "delete for everyone" of targetID
	kindEdit                        // an edit of targetID
	kindTimer                       // a chat's disappearing timer was set
)

// classifyMessageEvent decides what a (UnwrapRaw-ed) message does. Shared by
// the live handler and history sync so both drop and store the same things.
func classifyMessageEvent(msg *waE2E.Message) (kind messageKind, targetID string) {
	if r := msg.GetReactionMessage(); r != nil {
		return kindReaction, r.GetKey().GetID()
	}
	if msg.GetProtocolMessage().GetType() == waE2E.ProtocolMessage_EPHEMERAL_SETTING {
		return kindTimer, ""
	}
	if pm := msg.GetProtocolMessage(); pm != nil && pm.GetKey().GetID() != "" {
		switch pm.GetType() {
		case waE2E.ProtocolMessage_REVOKE:
			return kindRevoke, pm.GetKey().GetID()
		case waE2E.ProtocolMessage_MESSAGE_EDIT:
			// UnwrapRaw strips an EditedMessage wrapper but leaves this
			// protocol message underneath; the new content is inside it.
			return kindEdit, pm.GetKey().GetID()
		}
	}
	if _, _, ok := extractContent(msg); !ok {
		return kindDrop, ""
	}
	return kindContent, ""
}

var futureProofName = (&waE2E.FutureProofMessage{}).ProtoReflect().Descriptor().FullName()

// extractContent maps a message to its stored content_type and display text.
// ok is false for content-free messages (see contentFreeFields), which are
// dropped. Wrappers WhatsApp nests a real message in (group mentions,
// spoilers, forwarded bot replies, …) are looked through; UnwrapRaw already
// strips the common ones (ephemeral, view-once, edited, document-with-caption).
func extractContent(msg *waE2E.Message) (contentType, text string, ok bool) {
	msg = contentMessage(msg)
	if msg == nil {
		return "", "", false
	}
	if ct, txt, found := directContent(msg); found {
		return ct, txt, true
	}
	if hasUnhandledContent(msg) {
		return "text", unsupportedText, true
	}
	return "", "", false
}

// contentMessage returns the message that actually carries the content: msg
// itself, or the one nested in its FutureProofMessage wrapper(s). Media
// download, reply context and the stored raw proto all read from it.
func contentMessage(msg *waE2E.Message) *waE2E.Message {
	for depth := 0; msg != nil && depth < maxWrapDepth; depth++ {
		if _, _, found := directContent(msg); found {
			return msg
		}
		inner := wrappedMessage(msg)
		if inner == nil {
			return msg
		}
		msg = inner
	}
	return msg
}

// wrappedMessage returns the message inside msg's FutureProofMessage field,
// or nil if it has none.
func wrappedMessage(msg *waE2E.Message) *waE2E.Message {
	if msg == nil {
		return nil
	}
	var inner *waE2E.Message
	msg.ProtoReflect().Range(func(fd protoreflect.FieldDescriptor, v protoreflect.Value) bool {
		if fd.Message() != nil && fd.Message().FullName() == futureProofName {
			inner = v.Message().Interface().(*waE2E.FutureProofMessage).GetMessage()
		}
		return inner == nil
	})
	return inner
}

// hasUnhandledContent reports whether msg sets any field that isn't
// content-free — i.e. something real that directContent doesn't know yet.
// Wrappers are content-free here: contentMessage already looked inside any
// that hold a message.
func hasUnhandledContent(msg *waE2E.Message) bool {
	found := false
	msg.ProtoReflect().Range(func(fd protoreflect.FieldDescriptor, v protoreflect.Value) bool {
		switch {
		case contentFreeFields[fd.Name()]:
		case fd.Message() != nil && fd.Message().FullName() == futureProofName:
		case fd.Name() == "conversation" && v.String() == "":
		default:
			found = true
		}
		return !found
	})
	return found
}

// directContent classifies the content types Photon understands. found is
// false when msg holds none of them (it may still be a wrapper or unknown).
func directContent(msg *waE2E.Message) (contentType, text string, found bool) {
	switch {
	case msg.GetConversation() != "":
		return "text", msg.GetConversation(), true
	case msg.GetExtendedTextMessage() != nil:
		return "text", msg.GetExtendedTextMessage().GetText(), true
	case msg.GetImageMessage() != nil:
		return "image", msg.GetImageMessage().GetCaption(), true
	case msg.GetVideoMessage() != nil:
		return "video", msg.GetVideoMessage().GetCaption(), true
	case msg.GetPtvMessage() != nil: // round "video note"
		return "video", msg.GetPtvMessage().GetCaption(), true
	case msg.GetAudioMessage() != nil:
		return "audio", "", true
	case msg.GetDocumentMessage() != nil:
		return "document", msg.GetDocumentMessage().GetFileName(), true
	case msg.GetStickerMessage() != nil:
		return "sticker", "", true
	case msg.GetLocationMessage() != nil:
		loc := msg.GetLocationMessage()
		return "location", formatCoords(loc.GetDegreesLatitude(), loc.GetDegreesLongitude()), true
	case msg.GetLiveLocationMessage() != nil:
		loc := msg.GetLiveLocationMessage()
		return "location", formatCoords(loc.GetDegreesLatitude(), loc.GetDegreesLongitude()), true
	case msg.GetContactMessage() != nil:
		return "contact", msg.GetContactMessage().GetDisplayName(), true
	case msg.GetContactsArrayMessage() != nil:
		return "contact", contactsArrayText(msg.GetContactsArrayMessage()), true

	// Business messages: templates, buttons, lists, interactive.
	case msg.GetTemplateMessage() != nil:
		return "text", orBusiness(templateText(msg.GetTemplateMessage())), true
	case msg.GetInteractiveMessage() != nil:
		return "text", orBusiness(interactiveText(msg.GetInteractiveMessage())), true
	case msg.GetButtonsMessage() != nil:
		bm := msg.GetButtonsMessage()
		return "text", orBusiness(joinLines(bm.GetText(), bm.GetContentText(), bm.GetFooterText())), true
	case msg.GetListMessage() != nil:
		lm := msg.GetListMessage()
		return "text", orBusiness(joinLines(lm.GetTitle(), lm.GetDescription(), lm.GetFooterText())), true
	case msg.GetHighlyStructuredMessage() != nil:
		return "text", orBusiness(templateText(msg.GetHighlyStructuredMessage().GetHydratedHsm())), true

	// Replies to business messages: the button/row the user picked.
	case msg.GetButtonsResponseMessage() != nil:
		return "text", orReply(msg.GetButtonsResponseMessage().GetSelectedDisplayText()), true
	case msg.GetListResponseMessage() != nil:
		lr := msg.GetListResponseMessage()
		return "text", orReply(joinLines(lr.GetTitle(), lr.GetDescription())), true
	case msg.GetTemplateButtonReplyMessage() != nil:
		return "text", orReply(msg.GetTemplateButtonReplyMessage().GetSelectedDisplayText()), true
	case msg.GetInteractiveResponseMessage() != nil:
		ir := msg.GetInteractiveResponseMessage()
		return "text", orReply(firstNonEmpty(ir.GetBody().GetText(), ir.GetNativeFlowResponseMessage().GetName())), true

	case pollCreation(msg) != nil:
		return "text", pollText(pollCreation(msg)), true
	case msg.GetPollResultSnapshotMessage() != nil:
		return "text", "Poll results: " + msg.GetPollResultSnapshotMessage().GetName(), true
	case msg.GetPollResultSnapshotMessageV3() != nil:
		return "text", "Poll results: " + msg.GetPollResultSnapshotMessageV3().GetName(), true
	case msg.GetEventMessage() != nil:
		ev := msg.GetEventMessage()
		title := "Event: " + ev.GetName()
		if ev.GetIsCanceled() {
			title += " (cancelled)"
		}
		return "text", joinLines(title, ev.GetDescription()), true
	case msg.GetGroupInviteMessage() != nil:
		gi := msg.GetGroupInviteMessage()
		return "text", joinLines("Group invite: "+gi.GetGroupName(), gi.GetCaption()), true
	case msg.GetNewsletterAdminInviteMessage() != nil:
		return "text", "Channel invite: " + msg.GetNewsletterAdminInviteMessage().GetNewsletterName(), true
	case msg.GetNewsletterFollowerInviteMessageV2() != nil:
		return "text", "Channel invite: " + msg.GetNewsletterFollowerInviteMessageV2().GetNewsletterName(), true
	case msg.GetScheduledCallCreationMessage() != nil:
		return "text", "Scheduled call: " + msg.GetScheduledCallCreationMessage().GetTitle(), true
	case msg.GetStickerPackMessage() != nil:
		return "text", "Sticker pack: " + msg.GetStickerPackMessage().GetName(), true
	case msg.GetRequestPhoneNumberMessage() != nil:
		return "text", "[ asked for your phone number ]", true

	// Commerce and payments: a readable stand-in, not the full card.
	case msg.GetProductMessage() != nil:
		pm := msg.GetProductMessage()
		return "text", joinLines("[ product ] "+pm.GetProduct().GetTitle(), pm.GetBody()), true
	case msg.GetOrderMessage() != nil:
		om := msg.GetOrderMessage()
		return "text", joinLines("[ order ] "+om.GetOrderTitle(), om.GetMessage()), true
	case msg.GetInvoiceMessage() != nil:
		return "text", joinLines("[ invoice ]", msg.GetInvoiceMessage().GetNote()), true
	case msg.GetSendPaymentMessage() != nil:
		return "text", joinLines("[ payment ]", noteText(msg.GetSendPaymentMessage().GetNoteMessage())), true
	case msg.GetRequestPaymentMessage() != nil:
		return "text", joinLines("[ payment request ]", noteText(msg.GetRequestPaymentMessage().GetNoteMessage())), true

	case msg.GetCallLogMesssage() != nil:
		cl := msg.GetCallLogMesssage()
		return "call", callLogText(cl.GetIsVideo(), cl.GetCallOutcome()), true
	case msg.GetPlaceholderMessage() != nil:
		return "text", phoneOnlyText, true
	}
	return "", "", false
}

// pollCreation returns the poll in any of its protocol versions (V4 is a
// FutureProofMessage and reaches here through contentMessage).
func pollCreation(msg *waE2E.Message) *waE2E.PollCreationMessage {
	for _, p := range []*waE2E.PollCreationMessage{
		msg.GetPollCreationMessage(), msg.GetPollCreationMessageV2(), msg.GetPollCreationMessageV3(),
		msg.GetPollCreationMessageV5(), msg.GetPollCreationMessageV6(),
	} {
		if p != nil {
			return p
		}
	}
	return nil
}

func pollText(p *waE2E.PollCreationMessage) string {
	lines := []string{"Poll: " + p.GetName()}
	for _, opt := range p.GetOptions() {
		if name := opt.GetOptionName(); name != "" {
			lines = append(lines, "- "+name)
		}
	}
	return strings.Join(lines, "\n")
}

// templateText renders a template's visible text from whichever format it
// uses: hydrated four-row (the common one), raw four-row, or interactive.
func templateText(tm *waE2E.TemplateMessage) string {
	if tm == nil {
		return ""
	}
	hydrated := tm.GetHydratedFourRowTemplate()
	if hydrated == nil {
		hydrated = tm.GetHydratedTemplate()
	}
	if hydrated != nil {
		if txt := joinLines(hydrated.GetHydratedTitleText(), hydrated.GetHydratedContentText(), hydrated.GetHydratedFooterText()); txt != "" {
			return txt
		}
	}
	if four := tm.GetFourRowTemplate(); four != nil {
		if txt := joinLines(hsmText(four.GetHighlyStructuredMessage()), hsmText(four.GetContent()), hsmText(four.GetFooter())); txt != "" {
			return txt
		}
	}
	return interactiveText(tm.GetInteractiveMessageTemplate())
}

// hsmText renders a highly-structured message: its hydrated template if
// present, else the fallback parameters.
func hsmText(hsm *waE2E.HighlyStructuredMessage) string {
	if hsm == nil {
		return ""
	}
	if txt := templateText(hsm.GetHydratedHsm()); txt != "" {
		return txt
	}
	var params []string
	for _, p := range hsm.GetLocalizableParams() {
		if d := p.GetDefault(); d != "" {
			params = append(params, d)
		}
	}
	if len(params) == 0 {
		params = hsm.GetParams()
	}
	return strings.Join(params, " ")
}

func interactiveText(im *waE2E.InteractiveMessage) string {
	if im == nil {
		return ""
	}
	txt := joinLines(im.GetHeader().GetTitle(), im.GetBody().GetText(), im.GetFooter().GetText())
	if txt != "" {
		return txt
	}
	// A carousel has no body of its own; show its cards'.
	var cards []string
	for _, card := range im.GetCarouselMessage().GetCards() {
		if c := interactiveText(card); c != "" {
			cards = append(cards, c)
		}
	}
	return strings.Join(cards, "\n\n")
}

func contactsArrayText(ca *waE2E.ContactsArrayMessage) string {
	if ca.GetDisplayName() != "" {
		return ca.GetDisplayName()
	}
	var names []string
	for _, c := range ca.GetContacts() {
		if n := c.GetDisplayName(); n != "" {
			names = append(names, n)
		}
	}
	return strings.Join(names, ", ")
}

// noteText is the text of a payment's attached note message.
func noteText(note *waE2E.Message) string {
	if note == nil {
		return ""
	}
	_, txt, _ := directContent(note)
	return txt
}

func callLogText(isVideo bool, outcome waE2E.CallLogMessage_CallOutcome) string {
	kind := "voice call"
	if isVideo {
		kind = "video call"
	}
	switch outcome {
	case waE2E.CallLogMessage_MISSED, waE2E.CallLogMessage_SILENCED_BY_DND, waE2E.CallLogMessage_SILENCED_UNKNOWN_CALLER:
		return "Missed " + kind
	case waE2E.CallLogMessage_REJECTED:
		return "Declined " + kind
	case waE2E.CallLogMessage_FAILED:
		return "Failed " + kind
	}
	return strings.ToUpper(kind[:1]) + kind[1:]
}

func formatCoords(lat, lng float64) string {
	return fmt.Sprintf("%.6f, %.6f", lat, lng)
}

func orBusiness(s string) string {
	if s == "" {
		return businessText
	}
	return s
}

func orReply(s string) string {
	if s == "" {
		return "[ reply ]"
	}
	return s
}

// joinLines joins the non-empty parts with newlines.
func joinLines(parts ...string) string {
	var kept []string
	for _, p := range parts {
		if p = strings.TrimSpace(p); p != "" {
			kept = append(kept, p)
		}
	}
	return strings.Join(kept, "\n")
}

func firstNonEmpty(parts ...string) string {
	for _, p := range parts {
		if p != "" {
			return p
		}
	}
	return ""
}

// getContextInfo returns the reply/mention context of a message's content,
// whatever its type — every content type carries it in a "contextInfo" field.
func getContextInfo(msg *waE2E.Message) *waE2E.ContextInfo {
	if msg == nil {
		return nil
	}
	var ctx *waE2E.ContextInfo
	msg.ProtoReflect().Range(func(fd protoreflect.FieldDescriptor, v protoreflect.Value) bool {
		if fd.Message() == nil || fd.IsList() || fd.IsMap() {
			return true
		}
		field := fd.Message().Fields().ByName("contextInfo")
		if field == nil || field.Message() == nil || !v.Message().Has(field) {
			return true
		}
		ctx, _ = v.Message().Get(field).Message().Interface().(*waE2E.ContextInfo)
		return ctx == nil
	})
	return ctx
}
