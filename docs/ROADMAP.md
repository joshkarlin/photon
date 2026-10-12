# Roadmap

This is where Photon goes after v0.5.0. It's based on a code and library review done on 2026-10-12: whatsmeow at `662ad1dc6900`, Turasa signal-service-java `2.15.3_unofficial_143`, and Signal-Android's current receive logic. Effort is S, M or L.

Some things are out of scope for Photon on purpose: setting a chat's disappearing-messages timer (Photon follows the phone's), live-location tracking, sticker images, link previews, stories, payments, MMS and group SMS. LightOS already handles SMS, so SMS in Photon is only for completeness.

## Shipped in 0.5.0

- **Disappearing messages** on WhatsApp and Signal. Photon follows each chat's timer, puts it on every send (Signal with its timer version), deletes messages when their time is up, and shows the timer under the chat's title and a line when it changes. It never sets the timer.
- **View-once media** shows as "[ view-once photo · open on your phone ]" on both platforms. It counts as unread and is never downloaded, thumbnailed or saved.
- **Messages that used to vanish:** Signal edits, every Signal attachment, Signal polls and shared contacts (as text), and WhatsApp messages that couldn't be decrypted (a placeholder, replaced when the phone resends the message).
- **Display fixes:** WhatsApp locations show their name and coordinates and open a `geo:` link. Received Signal documents show their file name. Save keeps a document's real name and gives every file its proper extension.
- **A separate Calls notification setting**, with its own channels and insistent ringing. Signal group-call starts alert as calls.
- **Archived chats** sync from the phone on both platforms, leave the lists, and open from an Archived row in the platform picker.

## Later

- **Loose ends from 0.5.0 (S each).**
  - Download a long Signal message's full text.
  - Follow WhatsApp's "disappear after read" timers.
  - Stop storing a sent transcript's profile key (yours) as the recipient's.
  - Before the final 0.5.0: test that a group transcript with no rows reads nothing, and that one leaves an unknown timer unknown. Test version 0 on media sends and retries.
  - Compare the transcript read cutoff against receive time, as Signal-Android does, not the sender's timestamp.
- **Polls (M per platform).** Both libraries support them. Both platforms show a poll as text for now.
  - WhatsApp: `DecryptPollVote` / `BuildPollVote`. Options are SHA-256 hashes; the latest vote per voter counts.
  - Signal: `pollCreate` / `pollVote` / `pollTerminate`, at protocol version 8.
  - In the chat: the question and options with counts. Tap to vote from a LightOS pick-list.
  - Risks: building a WhatsApp vote needs the poll sender's original JID (LID), and Signal's `voteCount` must go up across devices.
- **Notification batching (M).** A Delivery setting: Immediate / Hourly / 3 a day.
  - Use an alarm from the foreground service, not WorkManager.
  - Count unread at delivery time: "5 messages in 3 chats", one line per chat.
  - Calls always come through immediately.
  - Open: fixed or chosen times? Do mentions break through?
- **Location page and sending your location (M).** A page with a greyscale OpenStreetMap tile, cached like Journey's, and OPEN IN MAPS. Sending needs the location permission and one fix: WhatsApp sends a `LocationMessage`, Signal a maps link.
- **Editing your own messages (S–M).** The WhatsApp bridge already has `edit_message`. Signal has `sendEditMessage`.
- **Contact cards (S):** show the numbers, with Save. **Stickers (S):** show as their emoji. **GIFs (S):** label them.
- **Live locations (S).** 0.5.0 shows only the starting point. Update that row instead of notifying again, after checking whether updates reach linked devices at all.
- **Mute and archive from Photon.** WhatsApp is straightforward (app-state mute and archive). Signal needs a Storage Service write, which is riskier.
- **Signal reach and admin (L).** Reaching new numbers (contact discovery), group admin, and history restore.

## Maybe

- **PDF viewer (M).** Nothing on the LP3 opens PDFs or Office files. An in-app viewer would use `PdfRenderer`, in greyscale and scrolled with the dial, and show `text/*` as text. "Open with" only appears when another app can open the file.
- **Clearing LightOS's SMS notification (S–M).** LightOS holds the SMS role and posts a single message notification that ignores the provider's read flag. A NotificationListenerService could cancel it, all or nothing, once every SMS chat is read in Photon. Parked: SMS is only for completeness.
