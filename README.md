# Photon

A minimal, multi-protocol messaging client for the Light Phone III. WhatsApp, Signal, and SMS in one LP3-native interface.

## What it does

Photon connects to WhatsApp, Signal, and SMS and presents your messages in a monochrome, LP3-styled interface. Three messaging platforms, one intentional design.

- **WhatsApp**: Native protocol via [whatsmeow](https://github.com/tulir/whatsmeow) — text, reactions, replies, media send/receive, group chats
- **Signal**: Native protocol via [libsignal-service-java](https://github.com/AsamK/signal-cli) — text messaging and GroupV2 chats as a linked device
- **SMS**: Reads from Android's SMS content provider — view and send text messages
- **All Chats**: Default view, merges all platforms by recency with a header picker for platform filter

### Ephemeral by design

Messages don't live forever. Photon keeps a configurable rolling window of recent messages (default: 50 per chat or 7 days) and prunes the rest. Media downloads are temporary — viewed then auto-deleted after 5 minutes unless explicitly saved to the device.

## Installation

```bash
adb install app-release.apk
```

## Building from source

### Prerequisites

- Go 1.22+ (for the WhatsApp bridge)
- Android Studio (for the JDK and Android SDK)
- ADB (included with Android SDK)

### Build

```bash
# Build Go bridge
cd gobridge
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -buildvcs=false -trimpath -ldflags="-s -w" \
  -o ../app/src/main/jniLibs/arm64-v8a/libgobridge.so .

# Build APK (set JAVA_HOME and ANDROID_HOME for your environment)
./gradlew assembleDebug

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

```
┌────────────────────────────────────────────┐
│  Android App (Kotlin/Compose)              │
│                                            │
│  All Chats (home) ── Chat ── Contact       │
│       │                                    │
│       └─ header picker: All / SMS /        │
│          WhatsApp / Signal (lateral)       │
│                                            │
│  Settings (icon top-right of All Chats)    │
│                                            │
│  Shared: ChatScreenContent, ChatListContent│
│          ConversationRow, MessageLayouts   │
│                                            │
│  ┌──────────────────────────────────────┐  │
│  │  Foreground Service                  │  │
│  │  WhatsApp: Go bridge + WebSocket     │  │
│  │  Signal: libsignal in-process        │  │
│  │  SMS: ContentProvider + Observer     │  │
│  └──────────────────────────────────────┘  │
└────────────┬───────────────────────────────┘
             │ ws://127.0.0.1:8765
┌────────────▼───────────────────────────────┐
│  Go Bridge (whatsmeow)                     │
│  Cross-compiled for android/arm64          │
│  Pure Go — no CGO, no NDK                  │
│  WhatsApp protocol ←→ SQLite (messages)    │
└────────────────────────────────────────────┘
```

### WhatsApp
Go bridge runs as a native subprocess. Handles the WhatsApp protocol, writes to SQLite, communicates with Kotlin over localhost WebSocket. Kotlin reads the database directly (read-only, WAL mode).

### Signal
In-process Java library (Turasa's signal-service-java + libsignal-android). Own SQLite databases for protocol state and messages. WebSocket connection to Signal servers for receiving. Pre-key management for session establishment.

### SMS
Reads from Android's `content://sms` content provider. Resolves contact names via `content://contacts`. Sends via `SmsManager`. Real-time updates via `ContentObserver`.

## Pairing

**WhatsApp**: Pairs as a companion device (uses one of 4 linked device slots). QR code (default) or pairing code.

**Signal**: Pairs as a linked device via QR code. Primary device: Signal > Settings > Linked Devices > Link New Device.

**SMS**: No pairing needed — reads from the system SMS store.

## Message layouts

Three display modes, configurable separately for DMs and group chats:

**Terminal** (default for DMs): `[18:42 sender] > message`

**Clean** (tap for timestamps): Left/right aligned, no bubbles

**Transcript** (default for groups): `Name | message | time`

## Settings

Organized into sub-pages:

### Chat
| Setting | Options | Default |
|---|---|---|
| Notifications | On / Off | On |
| DM layout | Terminal / Clean / Transcript | Terminal |
| Group layout | Terminal / Clean / Transcript | Transcript |
| Thumbnails | On / Off | On |
| Chat scroll | Slow / Medium / Fast | Medium |
| Menu scroll | 1 Item / 2 Items / 3 Items | 1 Item |

### Storage
| Setting | Options | Default |
|---|---|---|
| Messages per chat | 25 / 50 / 100 / 200 | 50 |
| Message history | 1 / 3 / 7 / 14 / 30 days | 7 |

### Connections
Per-platform status, refresh connection, and reset (clear data + re-pair).

## Current features

- [x] WhatsApp: text, reactions, replies, media viewing + sending, group chats, contact names, history sync, mute sync from primary device
- [x] Signal: text messaging (DM + GroupV2), send/receive, device linking, sync transcripts from primary, reply-to-message, profile name resolution, contact book sync
- [x] Signal GroupV2: incoming + outgoing fan-out send, group title + member resolution, locally-cached state refreshed on revision bumps
- [x] SMS: read conversations, send messages, contact name resolution, per-conversation read tracking (local)
- [x] All Chats: default home view; header picker switches between SMS / WhatsApp / Signal / All
- [x] Three message layouts (Terminal / Clean / Transcript)
- [x] Reply-to-message: swipe right on any WhatsApp or Signal message (hidden on SMS). Quote tap scrolls to the original.
- [x] Failed-send recovery: outgoing messages persist with `sending`/`sent`/`failed` status; tap a failed bubble to retry (preserves wire id/timestamp so recipients dedupe). WhatsApp retry now re-uploads media too, not just text.
- [x] Delete messages (WhatsApp + Signal): long-press a message → Delete. Own sent messages offer "delete for everyone" (WhatsApp revoke / Signal remote-delete, with a confirm tap); failed/sending/incoming messages delete locally
- [x] Voice-note autoplay on open
- [x] In-app contact editor: tap a chat title → save/update directly via `ContactsContract` (no AOSP handoff)
- [x] Local contacts authoritative for Signal display names (LP3 address book → Signal profile name → bare phone)
- [x] Ephemeral retention (configurable count + days)
- [x] Message notifications (WhatsApp + Signal, configurable, respects WhatsApp mute)
- [x] Connection status in notification + settings
- [x] Reset/refresh per connection
- [x] LP3-native dark theme, monochrome design
- [x] Shared UI components across all platforms
- [x] γ (gamma) app launcher icon
- [x] Scroll dial support (LP3 hardware wheel — configurable speed for chats and menus)

## Known issues

- **Signal group name resets to a base64 id**: Fix implemented, pending verification — confirmed via on-device DB inspection (the group row had `name = NULL` with a valid master key): `reresolveConversationNames` (run on every reconnect / contact sync) iterated *all* conversations, and for a group (no phone/profile row) the resolver returned null, which it then wrote over the group's title. The UI fell back to `jid.take(8)+"…"` — the base64 group id. Three-part fix: (1) `reresolveConversationNames` never overwrites a name with null (honouring its own doc, "conversations with no phone are left alone"); (2) a connect-time repair re-fetches the real title for any group whose name is already blank (the null-guard alone can't restore a name that was wiped before the fix shipped), setting a "Signal group" placeholder first so the base64 id never shows; (3) the chat screen falls back to "Signal group" for a blank group title instead of the raw id. Covered by Robolectric DB tests (`reresolveConversationNames` preserves a group title; the repair query selects only blank-named groups that have a master key).
- **WhatsApp empty/ghost conversations (raw-JID names)**: Fix implemented, pending verification — on-device inspection found ~117 conversation rows with no messages and blank names (history sync creates a row per chat in WhatsApp's payload, but messages outside the retention window are never inserted). Two fixes: (1) the conversation list now only shows conversations that actually have a message, dropping the ghosts immediately without a migration; (2) the retention prune's empty-conversation cleanup (and per-conversation message cap) had **never run** — the prune queried `SELECT DISTINCT conversation_jid FROM conversations`, but that column lives on `messages`, not `conversations`, so the query errored and aborted the whole prune; fixed to query `messages`, and restructured to collect JIDs before deleting (the cursor-while-delete pattern could deadlock a single-writer SQLite). Now empty rows are also removed at the source on the next prune cycle. Covered by a Go prune test.
- **WhatsApp conversation preview shows a stale date / group misclassified**: Fix implemented, pending verification — a group could show an old "last message" date (e.g. 6/6 while newer messages arrived today) because history sync set the preview from WhatsApp's raw payload tail, which isn't guaranteed newest-last and may have been retention/nil-skipped; and a chat could be misclassified (DM↔group) because `is_group` was sticky-on-true. Fixes: classify groups solely by JID server (`g.us`), let trusted upserts overwrite `is_group`, derive the history preview from the newest *inserted* message, and a connect-time `RepairConversationMetadata` corrects existing rows (is_group by JID, preview from the newest actual message) without a re-pair. Covered by Go tests.
- **WhatsApp @-mentions show a number instead of a name**: Fix implemented, pending verification — WhatsApp stores the bare number in the message body (e.g. `@447…`) and ships the mentioned JIDs in `contextInfo`; official clients substitute the contact name on display. The bridge now resolves each mentioned JID (LID→phone, then address-book/observed push name from the contact store) and replaces the `@<number>` token in the body. Resolution happens at storage time, so it applies to new messages immediately; existing messages keep their number until re-synced (a re-pair re-resolves them). Unresolvable mentions stay as the number. Covered by a Go test.
- **Signal group messages from others not received**: Fix implemented, pending verification — confirmed via DB (a group with 3 sent / 0 received) and logcat (only `hasSync`/`hasReceipt` envelopes from others, zero `hasData`, zero decrypt failures). Sent messages work; inbound group content wasn't arriving as data at all. Leading hypothesis: linked-device sender-key delivery — other members' clients couldn't establish a session with this linked device to deliver their sender keys, so they never sent group ciphertext here. Photon already sent silent NullMessage session pings for DM contacts; the fix expands that to GroupV2 participants as well, so group-only members learn Photon's linked `deviceId` and include it in future encrypted recipient lists. Reconnect also refreshes GroupV2 metadata for groups with an empty participants table so there are member ACIs to ping. Added per-target session-ping logging and clearer ACI pre-key count/refill logs. Needs on-device confirmation that subsequent group envelopes include `hasData=true`.
- **Signal PNI-vs-ACI conversation keying**: Fix implemented, pending verification — incoming data-message sources and primary-device sent transcript destinations now normalize non-ACI service IDs (PNI) to the contact's ACI via the E164→ACI mapping learned from contact sync before writing conversation/message rows. If no ACI mapping exists yet, Photon keeps the raw id so the message is not lost, records the phone mapping, and logs the unresolved non-ACI key. Covered by a DB test that phone lookup returns the canonical ACI instead of a PNI/raw-id alias.
- **WhatsApp contact names**: Fix implemented, pending verification — address book names (`FullName` from contact sync) now take priority over push names everywhere, the connect-time backfill upgrades push-name rows to address book names, and the push name handler (which wrote malformed JID keys) only fills empty names. Re-check after next sync.
- **WhatsApp LID JIDs**: Fix implemented, pending verification — LID→phone resolution now applied in all write paths (push names, history sync, sends), and a connect-time migration merges existing duplicate `@lid` rows into their phone-JID rows.
- **Signal group admin**: Photon participates in GroupV2 (send + receive) but can't create groups, add/remove members, edit title, or leave. Group state updates initiated elsewhere are picked up automatically on the next message via the revision check.
- **Signal group send via fan-out**: Photon sends per-recipient rather than via sender-key distribution. Works the same end-to-end but is slower for large groups and skips `GroupSendEndorsements`.
- **Signal pre-key upload**: Uses reflection fallback to upload via PushServiceSocket (KeysApi WebSocket path returns 422). Works but fragile.
- **Signal history sync**: Not implemented — only new messages after pairing appear. Requires backup/restore mechanism.
- **Signal device name**: Fix implemented, pending verification — the name is now encrypted with the identity key (`DeviceNameUtil.encryptDeviceName`) before registration. Devices paired before the fix still show garbled text until re-paired (Reset in settings).
- **Signal media send**: Outgoing implemented, pending device testing — files upload to the CDN and send as attachments (DM + group, voice-note flag for audio, retry re-uploads).
- **Signal media receive**: Fix implemented, pending verification — incoming attachments used to be dropped entirely (photos never appeared). They're now stored with their CDN pointer (caption as text body), voice notes auto-download, and photos/videos download on tap via the media viewer. Downloads are pruned on the same 5-minute ephemeral TTL as WhatsApp media (re-downloadable on tap while the CDN pointer is valid — Signal pointers expire after ~45 days).
- **Signal sent messages missing on primary**: Fix implemented, pending verification — the message sender was constructed with `useBinaryId=false, useStringId=false`, so sent transcripts carried no `destinationServiceId` in any form and the primary device dropped them. Both flags are now enabled.
- **WhatsApp received images not loading**: Under investigation — tapping a received image opens the viewer but the image fails to display. One cause fixed: a pruned media file left a stale `media_url` on the row, so the viewer skipped re-download and rendered a broken fallback; the viewer now re-downloads when the cached file is missing. A genuine CDN-download failure (expired media, bridge error) is still possible and needs on-device logcat to confirm the failure mode.
- **Signal media send (+ button missing)**: Fix implemented, pending verification — the Signal chat screen never passed `onSendMedia`, so the "+" attach button was hidden (sending falls back to text/voice only). It now wires `onSendMedia` to `SignalRepository.sendMedia`, matching the WhatsApp screen, so the "+" button appears and photo/video sends work.
- **WhatsApp image send silently vanished**: Fix implemented, pending verification — picking a photo to send could fail silently (a null content stream or empty copy just logged and returned, so no sending/failed bubble appeared). The picker now copies off the main thread, surfaces a "Couldn't attach that file" toast on failure, and only sends when a non-empty file was staged.
- **WhatsApp unread badge stuck**: Fix implemented, pending verification — opening a chat cleared the unread badge only if the network read-receipt to WhatsApp succeeded; any failure (offline, LID-chat quirks) left the count stuck. The local badge now clears unconditionally on chat open, with the read-receipt still sent best-effort.
- **WhatsApp group chats stay unread on the primary device**: Fix implemented, pending verification — reading a group on Photon never cleared the unread badge on the user's main WhatsApp because the read receipt was sent with our own JID as the `participant`. whatsmeow requires the receipt's participant to be the actual sender of each message (and one call per distinct sender), so the malformed group receipt was ignored by the server and never fanned out as a read-self. `MarkRead` now buckets the message IDs by `sender_jid` and sends one correctly-attributed receipt per sender; DMs (where the participant is ignored) are unaffected.
- **WhatsApp media retry**: Fix implemented, pending verification — tap-to-retry only re-sent text; failed photos/videos/voice notes couldn't be retried at all (the bridge rejected them with "only text messages supported"). Retry now re-reads the original local source file, re-uploads to the CDN, and re-sends with the original stanza ID. Requires the source file to still exist (staged send files are pruned after 24h).
- **Signal read state not synced to other devices**: Fix implemented, pending verification — messages read (or sent) on Photon showed as unread on the primary because Photon never sent `SyncMessage.read`. Opening a Signal chat now sends a read sync for unsynced incoming messages (tracked via message status, each synced once). Note: this is the self-device sync only — READ receipts to the message author are deliberately not sent, since that's gated on the account's read-receipts privacy setting which Photon doesn't know.
- **WhatsApp group sender names missing on history-sync messages**: Resolved (user-confirmed Jun 2026) — superseded by the consolidated root-cause entry below; kept for the fix history. Historical group messages showed the sender's bare number while newer live messages showed the contact/profile name, so one person appeared under two labels in the same chat. Causes: (1) history sync detected groups by counting participants in the payload, which is often empty, so groups were misclassified as individual chats — and the participant-name backfill was gated on that flag, so it was skipped; (2) the live handler keyed senders by their unresolved JID (`evt.Info.Sender`) while history used `info.GetParticipant()`, so the same person keyed under different JIDs (LID vs phone); (3) history push names are unreliable. Fixes: groups are now detected by JID server (`g.us`); both paths LID-resolve the sender to the phone JID; history sync backfills push names; and opening a group triggers `resolve_participants`, which fills every stored sender's name from whatsmeow's contact store (address-book name preferred, observed push name fallback) and broadcasts `conversation_updated` to refresh the UI. The on-open resolution fixes already-synced chats **without** another re-pair, as long as the member is known to the contact store.
- **Signal incoming reactions never displayed**: Fix implemented, pending verification — reactions were stored under the `{author}_{timestampMs}` prefix but looked up by the full message id (`{author}_{timestampMs}_{rand}`), so the join never matched and no incoming Signal reaction rendered. `getReactions` now queries by prefix and maps results back onto the full ids.
- **Signal "delete for everyone" ignored on receive**: Fix implemented, pending verification — an incoming remote-delete matched the target by exact id against the suffix-less `{author}_{ts}`, which never hit the stored `_{rand}`-suffixed id. Added `markDeletedByPrefix` (prefix range-scan) that blanks the body/media and sets status, so the message renders as `[ deleted ]` like a WhatsApp revoke.
- **Signal reaction send not implemented**: Known limitation — `SignalRepository.sendReaction` is a stub, so the Signal chat screen no longer shows the reaction picker (it was a dead control). Implementing it needs the libsignal `Reaction` data-message path plus a local echo. WhatsApp reactions are unaffected.
- **SMS voice-note button did nothing**: Fix implemented, pending verification — the mic button rendered on SMS chats but `onSendAudio` wasn't wired (SMS can't send audio), so recordings were silently discarded. The mic is now hidden when the platform can't send audio, matching how the media `+` button is gated.
- **WhatsApp reaction/edit/mark-read used unresolved JIDs**: Fix implemented, pending verification — the outgoing reaction, edit, and mark-read WS handlers keyed on the raw client JID while the live handlers LID-resolve to the phone JID, so group reactions could orphan, edits emitted no refresh event, and the unread badge could fail to clear on `@lid` chats. All three now resolve the JID like the live path (and the edit handler broadcasts `message_updated`).
- **Foreground-service notification badges the app icon**: Fix implemented, pending verification — the always-on service notification (required by Android to keep WhatsApp/Signal connected) showed as an unread asterisk on launcher icons (e.g. inkOS), despite `setShowBadge(false)`. The service channel is now `IMPORTANCE_NONE` (new id `photon_service_v3`), which suppresses the notification from the shade by default so launchers don't badge it; the service still runs and the notification is still built for `startForeground`. Re-enabling the "Background Service" channel in Android settings brings back the live WA/Signal status line. Note: some Android 13 builds may still surface FGS notifications in a minimal form — needs on-device confirmation.
- **No way to link from settings**: Fix implemented, pending verification — Settings → Connections → WhatsApp/Signal showed only a status ("NOT LINKED"/"NOT CONNECTED") with no action when unpaired, because the pairing flow was reachable only from the platform picker. Both pages now show a "LINK DEVICE" row when unlinked that routes to the pairing screen. Also unified the wording: the unpaired state reads "NOT LINKED" for both platforms (was "NOT CONNECTED" for WhatsApp).
- **Dictation**: LP3 has no built-in STT engine. Android 13's `RecognitionService` framework has a known bug where `PermissionChecker.checkCallingPermissionForDataDelivery()` rejects third-party callers with ERROR_INSUFFICIENT_PERMISSIONS (error 9), even with RECORD_AUDIO granted. No code-level workaround exists — requires a system-level STT service or alternative approach.
- **Voice notes**: Recording implemented; sending is now wired for both WhatsApp and Signal but untested on device.
- **Java Records on Android**: Turasa v143 uses Java Records which Android's desugaring can't serialize via Jackson. Fixed with `AndroidRecordFix.kt` (reflection patch on JsonUtil's ObjectMapper).
- **WhatsApp group message sender names show as numbers**: Resolved (user-confirmed Jun 2026; required a re-pair for chats synced before the fix) — on-device diagnostics revealed the real root cause: history-sync group messages were stored with `sender_jid` set to the **group JID itself**. The sender was read from `WebMessageInfo.Key.Participant`, but in history the group sender lives on `WebMessageInfo.Participant` — so it was empty and `senderJID` fell back to the chat (group) JID. Every historical group message therefore had no resolvable sender and rendered as the group's number, while live messages (which read `evt.Info.Sender`) were named correctly — exactly the "recent named, historical numbered" split. Secondary: live sender JIDs kept their device suffix (`:4`), so contact-store lookups missed. Fixes: history reads `WebMessageInfo.Participant` (falling back to the key); a shared `canonicalSenderJID` resolves LID→phone **and** strips the device suffix (`ToNonAD`) in both live and history paths so a person keys identically everywhere. Existing historical rows can't be repaired in place (the lost participant isn't recoverable from stored data) — a re-pair re-syncs history with correct senders. Earlier contributing fixes (still in place): two paths wrote a bare phone number as a participant's `display_name` when no real name was known (the live handler's `"+" + pn.User` LID fallback, and `ResolveGroupParticipants`' number fallback). Because `UpsertParticipant` overwrites with any non-empty value and `ResolveGroupParticipants` runs on every group open + backfill cycle, it clobbered the good push names the live handler had captured for members not in the address book — turning names into numbers. Fix: never store a phone number as a participant name (prefer push name, then address-book contact name, else leave the row absent so the UI falls back to the number on its own), and purge existing `+<digits>` placeholder rows on group open so real names repopulate. Also fixed a UI-refresh gap that was masking it: participant names couldn't ride the conversations `StateFlow` (participant changes don't alter `Conversation` rows, so its `distinctUntilChanged` swallowed them), so the chat screen now observes a dedicated `participantNames(jid)` flow that re-reads on each refresh signal — names now show as soon as `resolve_participants` repopulates them from whatsmeow's contact store, no re-pair needed.

### Open structural-consistency findings (from cross-subsystem audit, not yet fixed)

- **`markRead` re-fires on every list emission (Signal, SMS)**: WhatsApp dedupes via `lastMarkedReadId`, but Signal and SMS call `markRead` on every `onMessagesLoaded` emission — redundant DB/network work, and on SMS the `notifyChange` re-triggers the same ContentObserver. Wasteful, not incorrect. Fix: lift the "only on new incoming id" gate into the shared `ChatScreenContent`.
- **SMS conversations flow lacks the shared resilience wrapper**: WhatsApp and Signal build their conversations `StateFlow` via `conversationsFlow()` (swallows transient DB errors, re-emits last-good, re-checks suspicious empties); SMS hand-rolls its own, so a transient provider error (e.g. permission revoked mid-session) can blank or stall the list. Fix: route SMS through the shared `conversationsFlow`/`messagesFlow` helpers.
- **Typing/call events broadcast unresolved JIDs**: `handleChatPresence` and `handleCallOffer` emit raw `@lid` JIDs while every conversation-correlated event resolves first, so a typing indicator on a `@lid` chat never matches the open conversation. Low stakes (ephemeral). Fix: resolve `evt.Chat`/`evt.Sender` like the other handlers.
- **Incoming-message `status` vocabulary differs per platform**: WhatsApp live stores `"sent"`, WhatsApp history `"read"`, Signal `"received"`, SMS `"received"`. Masked today because the shared UI only renders status for `isFromMe` rows, but a latent trap for any future shared logic. Fix: standardize incoming status to one value.
- **Title-vs-row name fallback diverges**: when a name is blank, the chat header and the conversation row fall back differently (e.g. Signal shows `aci[:8]` in the header but the full jid in the row), and group-sender fallbacks use varying `substringBefore("@")`/`take`. Cosmetic. Fix: a single shared `displayTitle(conv)` / sender-fallback helper.
- **WhatsApp DM with an unresolved name shows the raw `…@s.whatsapp.net` JID in the list**: a conversation with a real message but no resolved name (number not in the address book, no push name observed yet) renders its raw JID in the conversation row (`ConversationRow` falls back to `conv.jid`), while the chat header formats the same JID as `+<number>`. After the empty-ghost fix these only appear for chats that actually have a message. Fix: format the row fallback as `+<number>` (and ideally resolve from the WhatsApp contact store / a `displayTitle` helper) instead of showing the raw JID.
- **`participantNames` staleness (both group platforms)**: a `upsertParticipant` write only changes the participants table, so `conversationsFlow`'s `distinctUntilChanged` suppresses re-emission unless the conversation row also changed — names usually land with the triggering message, but a name learned with no accompanying message update can be briefly stale. Symmetric across WhatsApp/Signal; low severity.


## Planned features

- [ ] Signal history sync (backup download + restore)
- [ ] Signal group admin: create, leave, add/remove members, edit title
- [ ] MMS support (picture messages)
- [ ] Dictation (requires system-level STT or alternative approach)
- [ ] Voice note send/receive
- [ ] Sticker rendering
- [ ] Option to set Photon as default SMS app (enables writing `read`/`seen` to the Android SMS provider so read state syncs with other SMS clients; currently tracked locally via SharedPreferences)
- [ ] Message search
- [ ] Public Sans font (LP3 ecosystem font)
- [ ] New conversation / compose message

## Technical notes

- Go binary cross-compiled with `CGO_ENABLED=0` using `modernc.org/sqlite` (pure Go SQLite). No NDK required.
- Packaged as `libgobridge.so` in `jniLibs/arm64-v8a/` for Android extraction.
- SQLite uses WAL mode + `busy_timeout=5000` to prevent concurrent access errors.
- Signal uses a self-signed CA (bundled as `res/raw/signal_ca.pem`).
- Kyber pre-keys stored as `is_last_resort=1` to prevent deletion after first use.
- Signal GroupV2: `master_key` + `revision` stored per-conversation; member list cached in the `participants` table. Server fetch only when receiver sees an incoming message with a higher revision (or on first-ever send into a never-received-from group). No periodic refresh.
- Failed-send recovery: outgoing rows are inserted with `status=sending` before the wire call (Go bridge pre-generates the stanza id for WhatsApp; Signal/SMS reuse the local id). Status flips to `sent`/`failed` on result. Retry reuses the same id + wire timestamp so recipients dedupe.
- UI refresh is event-driven: WhatsApp re-queries on Go-bridge WebSocket events (plus reconnect), Signal on a change flow emitted by its message DB, SMS via ContentObserver. A 45s fallback re-query catches missed events; queries stop when no screen is subscribed.
- Kotlin 2.1.0, AGP 8.5.2, core library desugaring enabled.

## License

MIT
