package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"go.mau.fi/whatsmeow"
)

const (
	connectionWatchdogInterval = 15 * time.Second
	initialReconnectDelay      = 5 * time.Second
	maxReconnectDelay          = 5 * time.Minute
)

// setConnectionState stores and publishes the WhatsApp state. Keeping this in
// the bridge (rather than only in the Android WebSocket client) means a UI
// reconnect gets the real WhatsApp state immediately.
func (b *Bridge) setConnectionState(state, reason string) {
	b.connectionStateMu.Lock()
	b.connectionState = state
	b.connectionReason = reason
	b.connectionStateMu.Unlock()

	b.BroadcastEvent("connection_state", ConnectionStateEvent{
		State:  state,
		Reason: reason,
	})
}

func (b *Bridge) currentConnectionState() (string, string) {
	if b.client.Store.ID == nil {
		b.connectionStateMu.RLock()
		reason := b.connectionReason
		b.connectionStateMu.RUnlock()
		return "logged_out", reason
	}

	b.connectionStateMu.RLock()
	state, reason := b.connectionState, b.connectionReason
	b.connectionStateMu.RUnlock()
	if state == "" {
		if b.client.IsConnected() && b.client.IsLoggedIn() {
			return "connected", ""
		}
		if b.client.IsConnected() {
			return "connecting", "WhatsApp socket is open but not authenticated"
		}
		return "disconnected", ""
	}
	return state, reason
}

func (b *Bridge) markConnectionConnected() {
	b.reconnectMu.Lock()
	b.resetReconnectStateLocked()
	b.reconnectPaused = false
	b.reconnectMu.Unlock()
	b.setConnectionState("connected", "")
}

func (b *Bridge) resetReconnectStateLocked() {
	b.reconnectDelay = 0
	b.reconnectNext = time.Time{}
}

func (b *Bridge) pauseReconnect(reason string) {
	b.reconnectMu.Lock()
	b.reconnectPaused = true
	b.reconnectMu.Unlock()
	b.log.Warnf("WhatsApp automatic reconnect paused: %s", reason)
}

// StartConnectionWatchdog retries a paired session when whatsmeow's socket is
// gone without relying solely on a local WebSocket refresh. It deliberately
// uses a bounded backoff so a phone/network outage does not create a tight
// reconnect loop.
func (b *Bridge) StartConnectionWatchdog(ctx context.Context) {
	go func() {
		ticker := time.NewTicker(connectionWatchdogInterval)
		defer ticker.Stop()

		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if b.client.Store.ID == nil || (b.client.IsConnected() && b.client.IsLoggedIn()) {
					continue
				}
				if err := b.ensureConnection(false, "retrying WhatsApp connection"); err != nil {
					b.log.Warnf("WhatsApp connection retry failed: %v", err)
				}
			}
		}
	}()
}

// ensureConnection either resets a live WhatsApp socket or creates a new one
// for the existing stored session. It never deletes session data or initiates
// pairing; a permanent auth/device failure is handled by the event handler.
func (b *Bridge) ensureConnection(force bool, reason string) error {
	if b.client.Store.ID == nil {
		b.setConnectionState("logged_out", "no paired WhatsApp session")
		return errors.New("WhatsApp is not paired")
	}

	b.reconnectMu.Lock()
	defer b.reconnectMu.Unlock()

	now := time.Now()
	if !force {
		if b.reconnectPaused || now.Before(b.reconnectNext) {
			return nil
		}
	} else {
		// A user-requested refresh is an explicit request to try again after a
		// permanent/retryable state, but it still does not erase credentials.
		b.reconnectPaused = false
		b.reconnectDelay = 0
		b.reconnectNext = time.Time{}
	}

	b.setConnectionState("connecting", reason)
	if b.client.IsConnected() {
		if !force && b.client.IsLoggedIn() {
			b.resetReconnectStateLocked()
			b.reconnectPaused = false
			b.setConnectionState("connected", "")
			return nil
		}
		// ResetConnection lets whatsmeow's own reconnect loop preserve the
		// session and perform the correct socket teardown.
		b.client.ResetConnection()
		return nil
	}

	err := b.client.Connect()
	if errors.Is(err, whatsmeow.ErrAlreadyConnected) {
		b.resetReconnectStateLocked()
		b.reconnectPaused = false
		b.setConnectionState("connected", "")
		return nil
	}
	if err == nil {
		b.reconnectDelay = 0
		b.reconnectNext = time.Time{}
		return nil
	}

	if b.reconnectDelay == 0 {
		b.reconnectDelay = initialReconnectDelay
	} else {
		b.reconnectDelay *= 2
		if b.reconnectDelay > maxReconnectDelay {
			b.reconnectDelay = maxReconnectDelay
		}
	}
	b.reconnectNext = now.Add(b.reconnectDelay)
	b.setConnectionState("connecting", fmt.Sprintf("%s; retrying in %s", err, b.reconnectDelay.Round(time.Second)))
	return err
}

func (b *Bridge) handleReconnect() (json.RawMessage, error) {
	if err := b.ensureConnection(true, "manual reconnect requested"); err != nil {
		return nil, err
	}
	return mustMarshal(struct{}{}), nil
}
