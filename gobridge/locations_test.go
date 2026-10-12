package main

import (
	"context"
	"testing"
	"time"

	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"google.golang.org/protobuf/proto"
)

func TestGeoURI(t *testing.T) {
	for _, c := range []struct {
		lat, lng float64
		want     string
	}{
		{51.5194, -0.127, "geo:51.5194,-0.127"},
		{0, 0, "geo:0,0"},
		{-33.856784, 151.215297, "geo:-33.856784,151.215297"},
	} {
		if got := geoURI(c.lat, c.lng); got != c.want {
			t.Errorf("geoURI(%v, %v) = %q, want %q", c.lat, c.lng, got, c.want)
		}
	}
}

func mediaURL(t *testing.T, b *Bridge, id string) string {
	t.Helper()
	var u string
	if err := b.msgDB.QueryRow(`SELECT COALESCE(media_url, '') FROM messages WHERE id = ?`, id).Scan(&u); err != nil {
		t.Fatalf("media_url of %s: %v", id, err)
	}
	return u
}

// A shared place is stored with its name (or address, or nothing) and a
// geo: link to open in a maps app.
func TestHandleMessage_location(t *testing.T) {
	b := newFKClientBridge(t)
	now := time.Now().Unix()
	b.HandleEvent(messageEvent(alice, alice, "l1", now, &waE2E.Message{LocationMessage: &waE2E.LocationMessage{
		Name: s("British Museum"), Address: s("Great Russell St"),
		DegreesLatitude: proto.Float64(51.5194), DegreesLongitude: proto.Float64(-0.127),
	}}))
	b.HandleEvent(messageEvent(alice, alice, "l2", now, &waE2E.Message{LocationMessage: &waE2E.LocationMessage{
		DegreesLatitude: proto.Float64(1.5), DegreesLongitude: proto.Float64(2.25),
	}}))

	if ct, text := contentOf(t, b, "l1"); ct != contentLocation || text != "British Museum" {
		t.Errorf("l1 stored %q %q", ct, text)
	}
	if got := mediaURL(t, b, "l1"); got != "geo:51.5194,-0.127" {
		t.Errorf("l1 media_url = %q", got)
	}
	var label *string
	b.msgDB.QueryRow(`SELECT text_body FROM messages WHERE id = 'l2'`).Scan(&label)
	if label != nil {
		t.Errorf("a dropped pin's label = %q, want null", *label)
	}
	if got := mediaURL(t, b, "l2"); got != "geo:1.5,2.25" {
		t.Errorf("l2 media_url = %q", got)
	}
}

// Upgrading gives the places 0.4.0 stored as bare coordinates their name
// and link: from the proto stored with them, else from the coordinates.
func TestMigrateLocations(t *testing.T) {
	b := v3Database(t)
	v3Message(t, b, "l1", alice.String(), 100, &waE2E.Message{LocationMessage: &waE2E.LocationMessage{
		Address: s("Great Russell St"), DegreesLatitude: proto.Float64(51.5194), DegreesLongitude: proto.Float64(-0.127),
	}})
	v3Message(t, b, "l2", alice.String(), 101, nil)
	v3Message(t, b, "t1", alice.String(), 102, &waE2E.Message{Conversation: s("51.5, -0.1")})
	b.msgDB.Exec(`UPDATE messages SET content_type = 'location', text_body = '51.519400, -0.127000' WHERE id = 'l1'`)
	b.msgDB.Exec(`UPDATE messages SET content_type = 'location', text_body = '1.500000, 2.250000' WHERE id = 'l2'`)
	b.msgDB.Exec(`UPDATE messages SET text_body = '51.5, -0.1' WHERE id = 't1'`)

	if err := b.InitMessageDB(context.Background()); err != nil {
		t.Fatal(err)
	}
	if _, text := contentOf(t, b, "l1"); text != "Great Russell St" {
		t.Errorf("l1 label = %q", text)
	}
	if got := mediaURL(t, b, "l1"); got != "geo:51.5194,-0.127" {
		t.Errorf("l1 media_url = %q", got)
	}
	if _, text := contentOf(t, b, "l2"); text != "" {
		t.Errorf("l2 label = %q, want none", text)
	}
	if got := mediaURL(t, b, "l2"); got != "geo:1.5,2.25" {
		t.Errorf("l2 media_url = %q", got)
	}
	if got := mediaURL(t, b, "t1"); got != "" {
		t.Errorf("a text that looks like coordinates got %q", got)
	}
}

// A received document keeps its file name (Save saves under it) and MIME
// type, and is downloaded with its own extension.
func TestReceivedDocument_nameAndType(t *testing.T) {
	b := newFKClientBridge(t)
	b.HandleEvent(messageEvent(alice, alice, "d1", time.Now().Unix(), &waE2E.Message{DocumentMessage: &waE2E.DocumentMessage{
		FileName: s("Budget 2027.XLSX"), Title: s("Budget"),
		Mimetype: s("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
	}}))
	var text, mime string
	b.msgDB.QueryRow(`SELECT text_body, media_mime FROM messages WHERE id = 'd1'`).Scan(&text, &mime)
	if text != "Budget 2027.XLSX" || mime != "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" {
		t.Errorf("stored %q as %q", text, mime)
	}
}

func TestMediaExt(t *testing.T) {
	doc := func(name, mime string) *waE2E.Message {
		return &waE2E.Message{DocumentMessage: &waE2E.DocumentMessage{FileName: s(name), Mimetype: s(mime)}}
	}
	cases := []struct {
		name string
		msg  *waE2E.Message
		want string
	}{
		{"a document's own extension", doc("Budget 2027.XLSX", "application/octet-stream"), ".xlsx"},
		{"Office by MIME type", doc("", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"), ".docx"},
		{"no extension: the MIME type's", doc("README", "text/plain"), ".txt"},
		{"an odd extension isn't trusted", doc("x.tar.g$z", "application/zip"), ".zip"},
		{"unknown", doc("blob", "application/x-unknown"), ".bin"},
		{"voice note", &waE2E.Message{AudioMessage: &waE2E.AudioMessage{Mimetype: s("audio/ogg; codecs=opus")}}, ".ogg"},
		{"photo", &waE2E.Message{ImageMessage: &waE2E.ImageMessage{Mimetype: s("image/jpeg")}}, ".jpg"},
	}
	for _, c := range cases {
		if got := mediaExt(c.msg); got != c.want {
			t.Errorf("%s: mediaExt = %q, want %q", c.name, got, c.want)
		}
	}
}
