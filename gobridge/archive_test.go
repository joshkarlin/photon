package main

import (
	"context"
	"testing"
	"time"

	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/proto/waSyncAction"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

func isArchived(t *testing.T, b *Bridge, jid string) bool {
	t.Helper()
	var archived bool
	if err := b.msgDB.QueryRow(`SELECT is_archived FROM conversations WHERE jid = ?`, jid).Scan(&archived); err != nil {
		t.Fatalf("archive state of %s: %v", jid, err)
	}
	return archived
}

func archiveEvent(jid types.JID, archived bool, at time.Time) *events.Archive {
	return &events.Archive{JID: jid, Timestamp: at, Action: &waSyncAction.ArchiveChatAction{Archived: proto.Bool(archived)}}
}

func keepArchivedEvent(keep bool, at time.Time) *events.UnarchiveChatsSetting {
	return &events.UnarchiveChatsSetting{Timestamp: at, Action: &waSyncAction.UnarchiveChatsSetting{UnarchiveChats: proto.Bool(!keep)}}
}

// Archiving on the phone archives the chat here, even one Photon has no
// message in yet; a late event never undoes a newer one.
func TestHandleArchive(t *testing.T) {
	b := newFKClientBridge(t)
	events := captureEvents(b)
	now := time.Now()

	b.HandleEvent(archiveEvent(alice, true, now))
	if !isArchived(t, b, alice.String()) {
		t.Fatal("not archived")
	}
	if got := events.ofType("conversation_updated"); len(got) != 1 || got[0]["jid"] != alice.String() {
		t.Errorf("conversation_updated = %v", got)
	}
	b.HandleEvent(archiveEvent(alice, false, now.Add(-time.Hour))) // late
	if !isArchived(t, b, alice.String()) {
		t.Errorf("an older unarchive undid the archive")
	}
	b.HandleEvent(archiveEvent(alice, false, now.Add(time.Minute)))
	if isArchived(t, b, alice.String()) {
		t.Errorf("still archived after an unarchive")
	}
}

// A new message unarchives a chat only when the account doesn't keep chats
// archived, which until WhatsApp says otherwise it does (its default).
func TestNewMessage_unarchivesUnlessKeptArchived(t *testing.T) {
	b := newFKClientBridge(t)
	now := time.Now()
	b.HandleEvent(archiveEvent(alice, true, now.Add(-time.Hour)))

	b.HandleEvent(textEvent(alice, alice, "m1", "hi", now.Unix()))
	if !isArchived(t, b, alice.String()) {
		t.Fatalf("unarchived though chats are kept archived by default")
	}

	b.HandleEvent(keepArchivedEvent(false, now))
	b.HandleEvent(textEvent(alice, ownJID, "m2", "my own", now.Unix()))
	if !isArchived(t, b, alice.String()) {
		t.Errorf("the user's own message unarchived the chat")
	}
	b.HandleEvent(textEvent(alice, alice, "m0", "sent before the archive", now.Add(-2*time.Hour).Unix()))
	if !isArchived(t, b, alice.String()) {
		t.Errorf("a message older than the archive unarchived the chat")
	}
	b.HandleEvent(undecryptable(alice, alice, "u1", now.Unix()+1))
	if isArchived(t, b, alice.String()) {
		t.Errorf("a new message didn't unarchive the chat")
	}
	if got := unreadCount(t, b, alice.String()); got != 3 {
		t.Errorf("unread = %d; archived chats still count", got)
	}
}

// Archived chats are still announced: archiving doesn't silence a chat.
func TestNewMessage_archivedChatsStillAnnounce(t *testing.T) {
	b := newFKClientBridge(t)
	events := captureEvents(b)
	b.HandleEvent(archiveEvent(alice, true, time.Now().Add(-time.Hour)))
	b.HandleEvent(textEvent(alice, alice, "m1", "hi", time.Now().Unix()))
	if got := len(events.ofType("new_message")); got != 1 {
		t.Errorf("new_message = %d", got)
	}
}

// History carries each chat's archive state and the account setting as the
// phone had them; neither replaces anything newer.
func TestHandleHistorySync_archive(t *testing.T) {
	b := newFKClientBridge(t)
	b.retention = RetentionConfig{MaxMessages: 50}
	now := time.Now()
	carol := types.NewJID("447700900003", types.DefaultUserServer)
	b.HandleEvent(archiveEvent(carol, false, now)) // unarchived since
	b.HandleEvent(keepArchivedEvent(true, now))

	b.handleHistorySync(&events.HistorySync{Data: &waHistorySync.HistorySync{
		GlobalSettings: &waHistorySync.GlobalSettings{AutoUnarchiveChats: proto.Bool(true)},
		Conversations: []*waHistorySync.Conversation{
			{ID: s(alice.String()), Archived: proto.Bool(true), Messages: []*waHistorySync.HistorySyncMsg{
				historyMsg(alice, "h1", false, "", uint64(now.Unix()-100), &waE2E.Message{Conversation: s("hi")}),
			}},
			{ID: s(carol.String()), Archived: proto.Bool(true)},
		},
	}})

	if !isArchived(t, b, alice.String()) {
		t.Errorf("alice's chat isn't archived")
	}
	if isArchived(t, b, carol.String()) {
		t.Errorf("history's archive state replaced a newer unarchive")
	}
	if !b.keepChatsArchived() {
		t.Errorf("history's setting replaced a newer one")
	}

	fresh := newFKClientBridge(t)
	fresh.handleHistorySync(&events.HistorySync{Data: &waHistorySync.HistorySync{
		GlobalSettings: &waHistorySync.GlobalSettings{AutoUnarchiveChats: proto.Bool(true)},
	}})
	if fresh.keepChatsArchived() {
		t.Errorf("history's setting wasn't stored")
	}
}

// Upgrading copies each chat's archive state from whatsmeow's chat
// settings, under either of a DM's JIDs; a full app-state sync does the
// same for chats nothing newer has set.
func TestArchiveBackfill(t *testing.T) {
	ctx := context.Background()
	lid := types.NewJID("11223344", types.HiddenUserServer)
	b := v3Database(t)
	b.client = newTestClient(t)
	b.client.Store.LIDs.PutLIDMapping(ctx, lid, alice)
	b.client.Store.ChatSettings.PutPinned(ctx, alice, true) // a settings row without the archive
	b.client.Store.ChatSettings.PutArchived(ctx, lid, true)
	b.client.Store.ChatSettings.PutArchived(ctx, grp, true)
	for _, jid := range []types.JID{alice, bob, grp} {
		b.msgDB.Exec(`INSERT INTO conversations (jid) VALUES (?)`, jid.String())
	}

	if err := b.InitMessageDB(ctx); err != nil {
		t.Fatal(err)
	}
	for jid, want := range map[types.JID]bool{alice: true, bob: false, grp: true} {
		if got := isArchived(t, b, jid.String()); got != want {
			t.Errorf("%s archived = %v, want %v", jid, got, want)
		}
	}

	// Unarchived on the phone since; whatsmeow's LID entry still says
	// archived. A later full sync mustn't re-archive it.
	captureEvents(b)
	b.HandleEvent(archiveEvent(alice, false, time.Now()))
	b.syncArchivedFromStore() // as after AppStateSyncComplete for regular_low
	if isArchived(t, b, alice.String()) {
		t.Errorf("a stale whatsmeow entry re-archived the chat")
	}
}

func TestMergeConversation_keepsTheArchive(t *testing.T) {
	b := newFKClientBridge(t)
	lid := types.NewJID("11223344", types.HiddenUserServer)
	b.HandleEvent(archiveEvent(lid, true, time.Now()))
	b.HandleEvent(textEvent(alice, alice, "m1", "hi", time.Now().Unix()-100))
	if _, err := b.mergeConversation(lid.String(), alice.String()); err != nil {
		t.Fatal(err)
	}
	if !isArchived(t, b, alice.String()) {
		t.Errorf("the merge lost the archive")
	}
}

// A chat whose messages are all gone keeps its archive state.
func TestPruneMessages_keepsArchivedChats(t *testing.T) {
	b := newFKClientBridge(t)
	b.HandleEvent(archiveEvent(alice, true, time.Now()))
	b.pruneMessages()
	if !isArchived(t, b, alice.String()) {
		t.Errorf("the archived chat's row was pruned")
	}
}
