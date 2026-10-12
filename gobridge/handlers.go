package main

import (
	"context"
	"fmt"
	"sync"
	"time"

	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

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
		// Reconcile once the connection has settled: LID mappings learned
		// since the last run, and names that changed while the bridge was
		// down. A session already synced gets no AppStateSyncComplete.
		go func() {
			time.Sleep(60 * time.Second)
			b.reconcile()
		}()
	case *events.AppStateSyncComplete:
		// Only a full sync (pairing, or recovery from a broken app state)
		// ends with this, and it stores contacts without an event for each,
		// so reconcile to pick them up. Incremental changes arrive one by one
		// as events.Contact.
		b.log.Infof("App state sync complete: %s", v.Name)
		go b.reconcile()
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
	case *events.Contact:
		b.handleContact(v)
	case *events.CallOffer, *events.CallOfferNotice, *events.CallPreAccept, *events.CallAccept,
		*events.CallTerminate, *events.CallReject, *events.UnknownCallEvent:
		b.handleCallEvent(v)
	case *events.GroupInfo:
		b.handleGroupInfo(v)
	case *events.JoinedGroup:
		b.handleJoinedGroup(v)
	case *events.Mute:
		b.handleMute(v)
	case *events.MarkChatAsRead:
		b.handleMarkChatAsRead(v)
	case *events.BusinessName:
		b.handleBusinessName(v)
	}
}

// reconcile runs the LID merge and name repair passes. A sync burst fires
// one request per app-state patch; the coalescer keeps runs from overlapping
// (two concurrent merges of one row used to add its unread count twice) and
// folds requests that arrive mid-run into a single follow-up run.
func (b *Bridge) reconcile() {
	b.reconciler.run(func() {
		b.MergeLIDConversations()
		b.CanonicalizeSenderJIDs()
		changed := append(b.BackfillContactNames(), b.BackfillParticipantNames()...)
		if len(changed) > 0 {
			b.namesChanged(changed)
			b.BroadcastEvent("conversation_updated", struct{}{})
		}
	})
}

// coalescer runs a function without overlap: calls made while it runs
// return at once and collapse into one more run afterwards.
type coalescer struct {
	mu      sync.Mutex
	running bool
	again   bool
}

func (c *coalescer) run(fn func()) {
	c.mu.Lock()
	if c.running {
		c.again = true
		c.mu.Unlock()
		return
	}
	c.running = true
	c.mu.Unlock()
	for {
		fn()
		c.mu.Lock()
		if !c.again {
			c.running = false
			c.mu.Unlock()
			return
		}
		c.again = false
		c.mu.Unlock()
	}
}
