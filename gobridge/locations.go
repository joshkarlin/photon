package main

import (
	"context"
	"database/sql"
	"strconv"
	"strings"

	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"google.golang.org/protobuf/proto"
)

// A shared place is stored as ContentTypes.LOCATION: text_body is its name,
// else its address, else null; media_url is a geo: URI of its coordinates,
// which a maps app opens.

// contentLocation is ContentTypes.LOCATION.
const contentLocation = "location"

// liveLocationText labels a live location's starting point. Photon doesn't
// follow its updates.
const liveLocationText = "Live location"

// location is the place a message shares, if it shares one.
func location(msg *waE2E.Message) (label, geo string, ok bool) {
	if loc := msg.GetLocationMessage(); loc != nil {
		return firstNonEmpty(strings.TrimSpace(loc.GetName()), strings.TrimSpace(loc.GetAddress())),
			geoURI(loc.GetDegreesLatitude(), loc.GetDegreesLongitude()), true
	}
	if live := msg.GetLiveLocationMessage(); live != nil {
		return firstNonEmpty(strings.TrimSpace(live.GetCaption()), liveLocationText),
			geoURI(live.GetDegreesLatitude(), live.GetDegreesLongitude()), true
	}
	return "", "", false
}

// geoURI is the geo: URI (RFC 5870) of a point.
func geoURI(lat, lng float64) string {
	return "geo:" + strconv.FormatFloat(lat, 'f', -1, 64) + "," + strconv.FormatFloat(lng, 'f', -1, 64)
}

// legacyCoords reads the "lat, lng" text earlier builds stored for a place.
func legacyCoords(text string) (geo string, ok bool) {
	lat, lng, found := strings.Cut(text, ",")
	if !found {
		return "", false
	}
	la, err1 := strconv.ParseFloat(strings.TrimSpace(lat), 64)
	ln, err2 := strconv.ParseFloat(strings.TrimSpace(lng), 64)
	if err1 != nil || err2 != nil {
		return "", false
	}
	return geoURI(la, ln), true
}

// migrateLocations rewrites the places earlier builds stored, whose
// text_body held their coordinates ("51.500000, -0.120000") and nothing
// else: each takes its name or address and a geo: URI from the proto it was
// stored with, or failing that the URI of the coordinates in its text.
func (b *Bridge) migrateLocations(ctx context.Context, tx *sql.Tx) error {
	rows, err := tx.QueryContext(ctx, `SELECT id, COALESCE(text_body, ''), raw_proto FROM messages WHERE content_type = ? AND media_url IS NULL`, contentLocation)
	if err != nil {
		return err
	}
	type fix struct {
		id    string
		label sql.NullString
		geo   string
	}
	var fixes []fix
	for rows.Next() {
		var id, text string
		var raw []byte
		if rows.Scan(&id, &text, &raw) != nil {
			continue
		}
		var msg waE2E.Message
		if len(raw) > 0 && proto.Unmarshal(raw, &msg) == nil {
			if label, geo, ok := location(&msg); ok {
				fixes = append(fixes, fix{id, sql.NullString{String: label, Valid: label != ""}, geo})
				continue
			}
		}
		if geo, ok := legacyCoords(text); ok {
			fixes = append(fixes, fix{id: id, geo: geo})
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for _, f := range fixes {
		if _, err := tx.ExecContext(ctx, `UPDATE messages SET text_body = ?, media_url = ? WHERE id = ?`, f.label, f.geo, f.id); err != nil {
			return err
		}
	}
	b.log.Infof("Gave %d stored places their name and map link", len(fixes))
	return nil
}
