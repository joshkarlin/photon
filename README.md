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
- [x] Failed-send recovery: outgoing messages persist with `sending`/`sent`/`failed` status; tap a failed bubble to retry (preserves wire id/timestamp so recipients dedupe)
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

- **WhatsApp contact names**: Fix implemented, pending verification — address book names (`FullName` from contact sync) now take priority over push names everywhere, the connect-time backfill upgrades push-name rows to address book names, and the push name handler (which wrote malformed JID keys) only fills empty names. Re-check after next sync.
- **WhatsApp LID JIDs**: Fix implemented, pending verification — LID→phone resolution now applied in all write paths (push names, history sync, sends), and a connect-time migration merges existing duplicate `@lid` rows into their phone-JID rows.
- **Signal group admin**: Photon participates in GroupV2 (send + receive) but can't create groups, add/remove members, edit title, or leave. Group state updates initiated elsewhere are picked up automatically on the next message via the revision check.
- **Signal group send via fan-out**: Photon sends per-recipient rather than via sender-key distribution. Works the same end-to-end but is slower for large groups and skips `GroupSendEndorsements`.
- **Signal pre-key upload**: Uses reflection fallback to upload via PushServiceSocket (KeysApi WebSocket path returns 422). Works but fragile.
- **Signal history sync**: Not implemented — only new messages after pairing appear. Requires backup/restore mechanism.
- **Signal device name**: Fix implemented, pending verification — the name is now encrypted with the identity key (`DeviceNameUtil.encryptDeviceName`) before registration. Devices paired before the fix still show garbled text until re-paired (Reset in settings).
- **Signal media send**: Outgoing implemented, pending device testing — files upload to the CDN and send as attachments (DM + group, voice-note flag for audio, retry re-uploads). Incoming Signal media download is still not implemented.
- **Dictation**: LP3 has no built-in STT engine. Android 13's `RecognitionService` framework has a known bug where `PermissionChecker.checkCallingPermissionForDataDelivery()` rejects third-party callers with ERROR_INSUFFICIENT_PERMISSIONS (error 9), even with RECORD_AUDIO granted. No code-level workaround exists — requires a system-level STT service or alternative approach.
- **Voice notes**: Recording implemented; sending is now wired for both WhatsApp and Signal but untested on device.
- **Java Records on Android**: Turasa v143 uses Java Records which Android's desugaring can't serialize via Jackson. Fixed with `AndroidRecordFix.kt` (reflection patch on JsonUtil's ObjectMapper).


## Planned features

- [ ] Signal history sync (backup download + restore)
- [ ] Signal media download (incoming attachments; outgoing send implemented)
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
- Kotlin 2.1.0, AGP 8.5.2, core library desugaring enabled.

## License

MIT
