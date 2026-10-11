package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"

	"go.mau.fi/whatsmeow/types"
)

// Reachability for New message: which phone numbers can be messaged on
// WhatsApp, and the chat to open for each.

// errNotConnected answers a check that needs WhatsApp while it's offline:
// whether a number is on WhatsApp is then unknown, not "no".
var errNotConnected = errors.New("WhatsApp disconnected")

type CheckOnWhatsAppPayload struct {
	Phones []string `json:"phones"`
}

// PhoneReach is one number's answer. JID is the chat to open: always a
// phone JID once WhatsApp has said which, since that's what sends key by.
type PhoneReach struct {
	Phone      string `json:"phone"`
	JID        string `json:"jid,omitempty"`
	OnWhatsApp bool   `json:"on_whatsapp"`
	// Existing: the chat already has messages here.
	Existing bool `json:"existing"`
}

type CheckOnWhatsAppResponse struct {
	Results []PhoneReach `json:"results"`
}

func (b *Bridge) handleCheckOnWhatsApp(ctx context.Context, raw json.RawMessage) (json.RawMessage, error) {
	var p CheckOnWhatsAppPayload
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, err
	}
	results, err := b.CheckOnWhatsApp(ctx, p.Phones)
	if err != nil {
		return nil, err
	}
	return mustMarshal(CheckOnWhatsAppResponse{Results: results}), nil
}

// CheckOnWhatsApp answers, for each of [phones] (international format,
// "+447700900123"), whether it's on WhatsApp and which chat to open. A
// number with a chat here is answered from the database; only the rest are
// asked about, in one query, which needs WhatsApp connected. The user asks
// this about one person at a time, as the official app does when you open a
// chat: never in bulk, which WhatsApp treats as contact scraping.
func (b *Bridge) CheckOnWhatsApp(ctx context.Context, phones []string) ([]PhoneReach, error) {
	results := make([]PhoneReach, len(phones))
	// Numbers to ask about, by their digits, with the answers they fill.
	waiting := map[string][]int{}
	var ask []string
	for i, phone := range phones {
		results[i].Phone = phone
		digits := phoneDigits(phone)
		if digits == "" {
			continue // not a number, so not on WhatsApp
		}
		if jid, ok := b.existingChatFor(ctx, digits); ok {
			results[i] = PhoneReach{Phone: phone, JID: jid, OnWhatsApp: true, Existing: true}
			continue
		}
		if _, asked := waiting[digits]; !asked {
			ask = append(ask, "+"+digits)
		}
		waiting[digits] = append(waiting[digits], i)
	}
	if len(ask) == 0 {
		return results, nil
	}

	found, err := b.lookupPhones(ctx, ask)
	if err != nil {
		if len(found) == 0 {
			return nil, err
		}
		// The answers came, but storing the LID mappings they carry failed.
		b.log.Warnf("Checking numbers on WhatsApp: %v", err)
	}
	for _, r := range found {
		digits := phoneDigits(r.Query)
		indices := waiting[digits]
		if len(indices) == 0 {
			continue
		}
		delete(waiting, digits)
		reach := PhoneReach{OnWhatsApp: r.IsIn}
		if r.IsIn {
			jid := phoneJIDOf(r, digits)
			reach.JID = jid.String()
			// WhatsApp's own form of the number may differ from the one
			// typed, and may already have a chat.
			if existing, ok := b.existingChatFor(ctx, jid.User); ok {
				reach.JID, reach.Existing = existing, true
			}
		}
		for _, i := range indices {
			reach.Phone = results[i].Phone
			results[i] = reach
		}
	}
	// Any number WhatsApp didn't answer for isn't on it.
	return results, nil
}

func (b *Bridge) lookupPhones(ctx context.Context, phones []string) ([]types.IsOnWhatsAppResponse, error) {
	if b.isOnWhatsApp != nil {
		return b.isOnWhatsApp(ctx, phones)
	}
	if !b.client.IsConnected() || !b.client.IsLoggedIn() {
		return nil, errNotConnected
	}
	return b.client.IsOnWhatsApp(ctx, phones)
}

// existingChatFor finds the chat with messages for a number, by its digits:
// its phone JID's, or one still keyed by the person's LID, which is merged
// into the phone JID's now (as reconcile would later) so sends and the chat
// agree.
func (b *Bridge) existingChatFor(ctx context.Context, digits string) (string, bool) {
	pn := types.NewJID(digits, types.DefaultUserServer)
	if b.chatHasMessages(pn.String()) {
		return pn.String(), true
	}
	lid, err := b.client.Store.LIDs.GetLIDForPN(ctx, pn)
	if err != nil || lid.IsEmpty() || !b.chatHasMessages(lid.String()) {
		return "", false
	}
	merged, err := b.mergeConversation(lid.String(), pn.String())
	if err != nil {
		b.log.Errorf("Failed to merge LID conversation %s into %s: %v", lid, pn, err)
		return lid.String(), true
	}
	if merged {
		b.log.Infof("Merged LID conversation %s into %s", lid, pn)
		b.BroadcastEvent("conversation_updated", struct{}{})
	}
	return pn.String(), true
}

// chatHasMessages reports whether a chat is one Photon lists: it has had a
// message (or call) here.
func (b *Bridge) chatHasMessages(jid string) bool {
	var ts int64
	err := b.msgDB.QueryRow(`SELECT last_timestamp FROM conversations WHERE jid = ?`, jid).Scan(&ts)
	if err != nil && err != sql.ErrNoRows {
		b.log.Warnf("Reading conversation %s: %v", jid, err)
	}
	return ts > 0
}

// phoneJIDOf is the phone JID WhatsApp gave for a number it knows: its
// phone_number, else its JID when that's a phone JID (not a LID), else the
// number as asked.
func phoneJIDOf(r types.IsOnWhatsAppResponse, digits string) types.JID {
	switch {
	case r.PhoneNumber.Server == types.DefaultUserServer && r.PhoneNumber.User != "":
		return r.PhoneNumber.ToNonAD()
	case r.JID.Server == types.DefaultUserServer && r.JID.User != "":
		return r.JID.ToNonAD()
	default:
		return types.NewJID(digits, types.DefaultUserServer)
	}
}

// phoneDigits is a phone number's digits, or "" when it can't be one
// (E.164 allows at most 15).
func phoneDigits(phone string) string {
	digits := strings.Map(func(r rune) rune {
		if r >= '0' && r <= '9' {
			return r
		}
		return -1
	}, phone)
	if len(digits) < 6 || len(digits) > 15 {
		return ""
	}
	return digits
}
