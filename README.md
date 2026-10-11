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

This section lists open bugs and by-design limitations, one line each. Fixes waiting for on-device confirmation are in [`docs/VERIFY.md`](docs/VERIFY.md).

### Bugs

- **WhatsApp received images may not load**: tapping a received image can still fail to show it. The stale-cache cause is fixed, but real download failures (expired CDN media, bridge errors) need on-device logs to diagnose.
- **Signal sent-transcript results unchecked**: the sync transcript to your own devices ignores per-device results, so a sync failure that doesn't throw can leave a sent message missing from your other linked devices.
- **Long chat titles overlap the back button**: the shared chat header centres the title without leaving room for the `<` button.

#### Inconsistencies (from a cross-subsystem audit)

- **Signal/SMS re-send mark-read on every list update**: WhatsApp only marks read when a new incoming message arrives (`lastMarkedReadId`). Signal and SMS call `markRead` on every message-list emission, and on SMS the `notifyChange` re-triggers the same observer. This is wasteful, not incorrect.
- **SMS list lacks the shared flow wrapper**: SMS builds its own conversations flow instead of using `conversationsFlow()`, so a transient provider error (a permission revoked mid-session, for example) can blank or stall the list.
- **WhatsApp typing/call events use unresolved JIDs**: `handleChatPresence` and `handleCallOffer` broadcast raw `@lid` JIDs, so a typing indicator on an `@lid` chat never matches the open conversation.
- **Incoming message status differs by platform**: WhatsApp stores `sent` for live messages and `read` for history, while Signal and SMS store `received`. Nothing breaks today because the UI only shows status on your own messages.
- **Blank-name fallbacks differ between header and list**: Signal shows `aci[:8]` in the header but the full JID in the list row, and group-sender fallbacks vary. One shared `displayTitle` helper would fix both.
- **Unnamed WhatsApp DMs show the raw JID in the list**: `ConversationRow` falls back to `…@s.whatsapp.net`, while the chat header shows `+<number>`.
- **Group participant names can lag**: a participant-name write that doesn't change the conversation row is dropped by `conversationsFlow`'s `distinctUntilChanged`, so a newly learned name can be briefly stale (WhatsApp and Signal).

### Limitations

- **Signal video send untested**: photo send works on device. Video uses the same path but hasn't been tried.
- **Voice notes untested**: recording and sending are wired up for WhatsApp and Signal, but neither has been tested on device.
- **Signal group admin**: Photon sends and receives in GroupV2 groups but can't create groups, add or remove members, rename, or leave. Changes made on other devices are picked up through the revision check on the next message.
- **Signal group sends fan out per recipient**: Photon doesn't use sender-key distribution, so sends to large groups are slower and `GroupSendEndorsements` are skipped.
- **No Signal history**: only messages received after linking appear. Importing history needs backup/restore support.
- **No Signal read receipts to senders**: Photon syncs read state to your own devices but never sends read receipts to message authors, because it can't see the account's read-receipt privacy setting.
- **Partial Signal verification state**: Photon only learns verified status from verification syncs received after linking. Accepting a changed safety number in Photon doesn't mark it verified on your other devices.
- **Signal pre-key fallback uses reflection**: if `KeysApi.setPreKeys` fails, Photon uploads pre-keys by calling the library's package-private `PushServiceSocket.makeServiceRequest` through reflection, which a library upgrade could break.
- **Media re-download expires**: viewed media is deleted after 5 minutes and downloaded again on tap. That fails once WhatsApp's CDN copy expires or Signal's attachment pointer lapses (about 45 days).
- **Dictation unavailable**: the LP3 has no built-in speech-to-text, and the framework's `RecognitionService` permission check rejected third-party recognisers with error 9 (`ERROR_INSUFFICIENT_PERMISSIONS`) even with RECORD_AUDIO granted. This hasn't been retested since the LP3 moved to Android 14+.

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

### Light SDK readiness

Photon will remain fully on-device; a developer-operated messaging relay is out of scope. Distribution through the Light SDK depends on Light supporting individually approved real-time/background capabilities and vetted protocol dependencies. See [lightphone/light-sdk#128](https://github.com/lightphone/light-sdk/issues/128) for the current discussion.

- [ ] Establish an on-device reliability baseline for each protocol: idle battery drain, memory use, socket uptime, reconnect frequency and backoff, and message latency over a representative 24-48 hour run
- [ ] Test and document lifecycle behavior for app foreground/background, screen lock, Wi-Fi/cellular transitions, airplane mode, process death, and reboot, with Signal and WhatsApp results recorded separately
- [ ] Add explicit per-protocol background-operation controls and status, including one action that closes connections and prevents automatic restart
- [ ] Publish a dependency inventory covering source, license, pinned version or commit, native artifacts and ABIs, update procedure, and reproducible build path
- [ ] Document protocol-specific distribution risks and support boundaries separately for WhatsApp, Signal, and SMS
- [ ] State clearly in the README and release notes that Photon is community-supported and that Light does not own protocol outages or compatibility failures
- [ ] Prototype Light SDK UI and navigation integration independently of protocol and background-service approval
- [ ] If Light exposes restricted real-time APIs, build a no-cloud SDK compatibility target and validate it against the lifecycle and reliability baseline above
- [ ] If Light accepts third-party protocol dependencies, request precise allowlist changes for Turasa `signal-service-java`, Signal's `libsignal-android`, and a vetted, reproducible Go/whatsmeow native artifact

## Technical notes

- Go binary cross-compiled with `CGO_ENABLED=0` using `modernc.org/sqlite` (pure Go SQLite). No NDK required.
- Packaged as `libgobridge.so` in `jniLibs/arm64-v8a/` for Android extraction.
- SQLite uses WAL mode + `busy_timeout=5000` to prevent concurrent access errors.
- Signal uses a self-signed CA (bundled as `res/raw/signal_ca.pem`).
- Last-resort Kyber pre-keys stored as `is_last_resort=1` to prevent deletion after first use (one-time Kyber pre-keys are deleted on use).
- Turasa v143 uses Java Records (e.g. `LinkDeviceRequest`) that Android desugaring can't serialize via Jackson. `AndroidRecordFix.kt` patches JsonUtil's ObjectMapper via reflection to handle them.
- Signal GroupV2: `master_key` + `revision` stored per-conversation; member list cached in the `participants` table. Server fetch only when receiver sees an incoming message with a higher revision (or on first-ever send into a never-received-from group). No periodic refresh.
- Failed-send recovery: outgoing rows are inserted with `status=sending` before the wire call (Go bridge pre-generates the stanza id for WhatsApp; Signal/SMS reuse the local id). Status flips to `sent`/`failed` on result. Retry reuses the same id + wire timestamp so recipients dedupe.
- UI refresh is event-driven: WhatsApp re-queries on Go-bridge WebSocket events (plus reconnect), Signal on a change flow emitted by its message DB, SMS via ContentObserver. A 45s fallback re-query catches missed events; queries stop when no screen is subscribed.
- Kotlin 2.1.0, AGP 8.5.2, core library desugaring enabled.

## License

MIT
