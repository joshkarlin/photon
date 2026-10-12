# Roadmap

This is where Photon goes after v0.4.0. It's based on a code and library review done on 2026-10-12: whatsmeow at `662ad1dc6900`, Turasa signal-service-java `2.15.3_unofficial_143`, and Signal-Android's current receive logic. Effort is S, M or L.

Some things are out of scope for Photon on purpose: live-location tracking, sticker images, link previews, stories, payments, MMS and group SMS. LightOS already handles SMS, so SMS in Photon is only for completeness.

## Next release (0.5.0)

Correctness and privacy fixes come first, then new features.

### 1. Disappearing messages (M–L, correctness and privacy)
Photon ignores disappearing messages in both directions:
- **Photon's own messages never disappear.** It never sets the WhatsApp `ContextInfo.Expiration` or the Signal `expireTimer`.
- **On Signal, a send can turn the timer off.** In 1:1 chats whose timer was set by an older client (timer version 1), a message from Photon turns disappearing messages off for both people.
- **Incoming disappearing messages stay** on the LP3 for the whole storage window.

The plan:
- Store each chat's timer.
  - WhatsApp: from setting messages, incoming `ContextInfo`, history's `EphemeralExpiration`, and group info.
  - Signal: `expireTimer` plus `expireTimerVersion`, following Signal-Android's rules, and the group's `disappearingMessagesTimer`.
- Put the timer on every send, including Signal sync transcripts.
- Delete expired messages, along with their media and notifications.
- Show a centred line in the chat, e.g. "Disappearing messages · 1 week".

Photon follows the timer but never changes it. Before shipping, test the Signal version-1 case on a throwaway chat.

### 2. View-once media (S, privacy)
- **WhatsApp:** linked devices only get an "unavailable" notice, and Photon drops it.
- **Signal:** Photon keeps view-once media as normal media and offers Save.
- **Fix:** on both, show "view-once photo · open on your phone", which counts as unread. Never download, thumbnail or save the media.

### 3. Messages that disappear silently (S–M)
Each of these should appear instead of being dropped:
- Signal edits (there's no `editMessage` branch).
- Every Signal attachment after the first.
- Signal polls and shared contacts, as readable text until polls are fully supported.
- WhatsApp messages that couldn't be decrypted (`UndecryptableMessage`). Show a placeholder that's replaced when the phone resends the message.

### 4. Small display fixes (S)
- **WhatsApp locations:** show the name or address and the coordinates, not `[ location ]`. Tapping opens a `geo:` link; Organic Maps answers it on the user's LP3.
- **Received Signal documents:** show their file name. It's already in `raw_proto` (`AttachmentPointer.fileName`), so existing rows can be backfilled.
- **Save** uses the file's real name, and Office files get proper extensions instead of `.bin`.

### 5. A separate Calls notification setting (S)
- Rename the NOTIFICATIONS row to **Messages** and add a **Calls** row. Both offer Sound / Vibrate / Silent / Off, and Calls starts from the current setting.
- Calls get their own channels. A ringing call keeps alerting (`FLAG_INSISTENT`) until it ends or 60s pass.
- Signal group-call starts count as calls.
- With Messages off and Calls on vibrate, you aren't pinged for messages but still know when a call is coming in.

### 6. Archived chats (M)
- **Sync:** archive state comes from the primary phone.
  - WhatsApp: `events.Archive`, a backfill from whatsmeow's `ChatSettings.Archived` (as was done for mutes), history's `Archived`, and "Keep chats archived".
  - Signal: the `archived` flags in Storage Service `ContactRecord` and `GroupV2Record`, plus `keepMutedChatsArchived`. These come from the same records the mute sync reads. Existing installs need one full re-read.
- **List:** archived chats leave the list. The platform picker gets an "Archived" view.
- **Notifications:** archived chats still notify unless muted, matching WhatsApp and Signal (archiving doesn't silence a chat).

## Later

- **Polls (M per platform).** Both libraries support them.
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
- **Live locations (S).** Show the start point with "open on phone", and update that row instead of notifying again. First check whether updates reach linked devices at all.
- **Mute from Photon.** WhatsApp is straightforward (app-state mute). Signal needs a Storage Service write, which is riskier.
- **Signal reach and admin (L).** Reaching new numbers (contact discovery), group admin, and history restore.

## Maybe

- **PDF viewer (M).** Nothing on the LP3 opens PDFs or Office files. An in-app viewer would use `PdfRenderer`, in greyscale and scrolled with the dial, and show `text/*` as text. "Open with" only appears when another app can open the file.
- **Clearing LightOS's SMS notification (S–M).** LightOS holds the SMS role and posts a single message notification that ignores the provider's read flag. A NotificationListenerService could cancel it, all or nothing, once every SMS chat is read in Photon. Parked: SMS is only for completeness.
