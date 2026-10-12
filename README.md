# Photon

A minimal, multi-protocol messaging client for the Light Phone III. WhatsApp, Signal, and SMS in one LP3-native interface.

## What it does

Photon connects to WhatsApp, Signal, and SMS and presents your messages in a monochrome interface built with the Light SDK, so it looks and behaves like the rest of LightOS. Three messaging platforms, one intentional design.

- **WhatsApp**: Native protocol via [whatsmeow](https://github.com/tulir/whatsmeow): text, reactions, replies, media send/receive, group chats, business chats, call notifications
- **Signal**: Native protocol via [libsignal-service-java](https://github.com/AsamK/signal-cli) as a linked device: text, reactions, replies, media, GroupV2 chats, call notifications
- **SMS**: Reads from Android's SMS store: view and send text messages
- **All Chats**: Default view, merges all platforms by recency. Tap ALL CHATS in the bottom bar to switch to one platform, or to see your archived chats.
- **New message**: Find anyone by name or number across your LP3 contacts and every platform's chats, then open their chat or start one on WhatsApp, Signal or SMS.

### Ephemeral by design

Messages don't live forever. Photon keeps a configurable rolling window of recent messages (default: 50 per chat or 7 days) and prunes the rest. Chats with disappearing messages also follow their own timer. Media downloads are temporary — viewed then auto-deleted after 5 minutes unless explicitly saved to the device.

## Installation

Photon needs Android 14 or later (the LP3 runs Android 14).

```bash
adb install -r app-release.apk
```

`-r` upgrades an existing install in place and keeps your chats and links. Downgrading isn't supported: an older build can't open the newer Signal database.

### Upgrading to 0.5.0

No re-pair is needed.

- **Signal reads your mutes and archived chats again** on its next connect, with the storage key Photon already has. Your primary phone only needs to come online if Photon never got that key.
- **Signal learns each chat's disappearing-messages timer as messages arrive** (or from your primary's contact sync), and fetches each group's state once. Until Photon knows a chat's timer, its own messages there don't disappear. WhatsApp reads timers from the messages it already has, so only a DM with no stored sign of its timer waits for the next message.
- **Calls** (Settings › Chat) starts at your old Notifications value. Notifications is now called Messages.

### Upgrading to 0.4.0

After installing, re-pair WhatsApp: Settings → Connections → WhatsApp → Reset, confirm with RESET, then link again. Since June (v0.3.0), Photon dropped the first message of every new WhatsApp chat, sent or received, and a re-pair brought back no messages. 0.4.0 fixes this, but WhatsApp only sends history when you pair, so re-pairing is how you get those messages back. You get what WhatsApp sends at pairing, within your Storage settings (default 50 messages per chat, 7 days).

## Building from source

### Prerequisites

- Go 1.25+ (for the WhatsApp bridge)
- JDK 17 (Android Studio's bundled JBR works) and the Android SDK (compileSdk 36)
- ADB (included with the Android SDK)

The Light SDK UI is built from source: [`lightphone/light-sdk`](https://github.com/lightphone/light-sdk) v0.2.0 is a git submodule at `third_party/light-sdk`, included as a Gradle composite build. Clone with submodules:

```bash
git clone --recurse-submodules <repo-url>
# or, in an existing clone:
git submodule update --init
```

### Build

```bash
# Build Go bridge
cd gobridge
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -buildvcs=false -trimpath -ldflags="-s -w" \
  -o ../app/src/main/jniLibs/arm64-v8a/libgobridge.so .
cd ..

# Build APK (set JAVA_HOME and ANDROID_HOME for your environment)
./gradlew assembleDebug

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

```
┌───────────────────────────────────────────────┐
│  Android app (Kotlin/Compose, Light SDK UI)   │
│                                               │
│  ChatListScreen ── ChatScreen ── Contact      │
│   (All Chats or one platform; bottom bar:     │
│    Settings, platform filter, New message)    │
│        │                                      │
│        ▼                                      │
│  ChatBackend: WhatsApp / Signal / SMS         │
│        │                                      │
│  ┌─────▼──────────────────────────────────┐   │
│  │ PhotonService (foreground), publishing │   │
│  │ one session per platform via Sessions  │   │
│  │  WhatsAppSession: Go bridge process    │   │
│  │  SignalSession: libsignal in-process   │   │
│  │  SmsSession: SMS store + observer      │   │
│  └────────────────────────────────────────┘   │
└──────────────┬────────────────────────────────┘
               │ JSON lines over stdin/stdout
┌──────────────▼────────────────────────────────┐
│  Go bridge (whatsmeow)                        │
│  Cross-compiled for android/arm64             │
│  Pure Go — no CGO, no NDK                     │
│  WhatsApp protocol ←→ SQLite (messages)       │
└───────────────────────────────────────────────┘
```

One chat screen and one chat list serve all three platforms, each through its `ChatBackend`.

### WhatsApp
The Go bridge runs as a native child process. It handles the WhatsApp protocol and writes to SQLite. Photon talks to it over the process's own stdin/stdout, one JSON message per line, so no other app can reach it (there is no network port). Kotlin reads the database directly (read-only, WAL mode).

### Signal
In-process Java library (Turasa's signal-service-java + libsignal-android). Own SQLite databases for protocol state and messages. WebSocket connection to Signal servers for receiving. Pre-key management for session establishment.

### SMS
Reads from Android's `content://sms` content provider and resolves names from the LP3's contacts. Sends via `SmsManager`, with a `sentIntent` per part so each send shows as sending until the radio reports back, then as sent or failed. Real-time updates via `ContentObserver`.

## Pairing

**WhatsApp**: Pairs as a companion device (uses one of 4 linked device slots). QR code (default) or pairing code.

**Signal**: Pairs as a linked device via QR code. Primary device: Signal > Settings > Linked Devices > Link New Device.

**SMS**: No pairing needed — reads from the system SMS store.

## Threads

Threads follow the LightOS look: no bubbles, received messages on the left, yours on the right.

- **DMs** use the LP3 thread style. A timestamp sits above the first message of each run (a change of sender, or a gap of more than 15 minutes). Tap any other message to see its time and delivery status.
- **Groups** use the LP2 style: a "Name  12:36" line above each run of incoming messages. Your own messages are right-aligned without a name.
- **Calls and timer changes** show as one centred line, e.g. "Missed voice call · 14:02" or "Disappearing messages · 1 week". While a chat's messages disappear, its title has "disappearing · 1 week" under it.
- **View-once media** reads "[ view-once photo · open on your phone ]", and a WhatsApp message your phone hasn't passed on yet reads "[ waiting for this message · open on your phone ]" until it arrives. Both count as unread. Photon never downloads view-once media.
- **Locations** (WhatsApp) show the place's name or address with its coordinates beneath. Tap one to open it in a map app (Organic Maps on the LP3).
- **Long-press** a WhatsApp or Signal message for its actions, whichever apply: Reply, React (not on your own messages), Retry, Delete (in Photon only) or Delete for Everyone (after a confirm page). View-once and waiting messages offer only Delete.
- **Attach (+)** opens a sheet with Camera (when an app on the phone can take photos), Photos and Files. The voice-note mic stays beside the text field.
- **Photos, videos and documents** open full screen when tapped. Save is at the top right: photos go to Pictures/Photon, videos to Movies/Photon and everything else to Download/Photon. Documents keep their real name.

## Settings

Organized into sub-pages:

### Chat
| Setting | Options | Default |
|---|---|---|
| Messages | Sound / Vibrate / Silent / Off | Sound |
| Calls | Sound / Vibrate / Silent / Off | Your Messages level |
| Thumbnails | On / Off | On |
| Scroll Dial | Off / Slow / Medium / Fast | Medium |
| Compose | Inline / Full screen | Inline |
| Keyboard | System / Light | System |

- **Messages** sets how message notifications alert. Sound, Vibrate and Silent each post through their own Android channel ("Messages", "Messages (vibrate)", "Messages (silent)"), so any system settings you made for "Messages" still apply.
- **Calls** sets how ringing, missed and group calls alert, through the "Calls", "Calls (vibrate)" and "Calls (silent)" channels. At Sound or Vibrate a ringing call keeps ringing or buzzing until it ends or 60 seconds pass. Every level but Off shows a call heads-up, even Silent. With Messages Off and Calls on Vibrate, messages stay quiet but you still know when someone calls.
- **Scroll Dial** sets how far one turn of the dial scrolls chats and menus. Off hands the dial back to LightOS, which uses it for brightness.
- **Compose: Full screen** follows LightOS Messages: the thread has a bottom bar with attach (+), voice note and compose, and compose opens a full-screen editor. Inline keeps a text field under the thread.
- **Keyboard: Light** is the LP3 keyboard from the Light SDK. System is the phone's installed keyboard (Gboard, for example). Tap the text field to show the keyboard; tap the thread or turn the dial to hide it.

### Storage
| Setting | Options | Default |
|---|---|---|
| Messages per Chat | 25 / 50 / 100 / 200 | 50 |
| Message History | 1 / 3 / 7 / 14 / 30 days | 7 |

### Connections
Per-platform status, Refresh Connection, and Reset (clear data + re-pair). Reset asks first, then unlinks Photon from your phone (WhatsApp logs out, Signal unlinks) before clearing its data. An unlinked platform offers Link Device.

## Current features

- [x] WhatsApp: text, reactions, replies, media viewing + sending, group chats, contact names, history sync, mute sync from primary device (timed mutes expire)
- [x] WhatsApp business chats and the rest of WhatsApp's message types (templates, interactive messages, lists, polls, …). Anything Photon can't show reads `[ unsupported message ]`.
- [x] WhatsApp edits and delete-for-everyone are applied, only when they come from the sender (a group admin can also delete for everyone)
- [x] WhatsApp messages this device couldn't decrypt show a placeholder, replaced when your phone resends them
- [x] Signal: text messaging (DM + GroupV2), send/receive, device linking, sync transcripts from primary, reply-to-message, reactions, media, contact book sync
- [x] Signal edits are applied in place, every attachment of a message is shown, and polls and shared contacts show as text
- [x] Signal GroupV2: incoming + outgoing fan-out send, group title + member resolution, locally-cached state refreshed on revision bumps
- [x] SMS: read conversations, send messages (shown as sending, then sent or failed), per-conversation read tracking (local)
- [x] Disappearing messages (WhatsApp + Signal): Photon follows each chat's timer, sends with it, and deletes messages when their time is up. Timer changes show in the thread, and the chat's title shows the timer. Photon never changes a timer.
- [x] View-once media (WhatsApp + Signal) shows as a placeholder to open on your phone, and is never downloaded or saved
- [x] Locations (WhatsApp): the place's name and coordinates, opened in a map app with a tap
- [x] Archived chats (WhatsApp + Signal) follow your phone, and open from Archived in the platform picker
- [x] Calls (WhatsApp + Signal): notifications and call rows for incoming, missed, and answered or declined on another device, including group calls, with their own Calls setting. Photon can't answer calls.
- [x] Display names follow one policy everywhere: LP3 contact, then the platform's address book (WhatsApp's synced contact name, or the Signal primary's contact name), then a verified business name, then the profile or push name, then the formatted number. Raw ids are never shown, and edits in the LP3 Contacts app show within seconds.
- [x] All Chats: default home view; tap the filter in the bottom bar to switch between All / SMS / WhatsApp / Signal
- [x] Reply-to-message: swipe right on a WhatsApp or Signal message, or long-press → Reply. Quote tap scrolls to the original.
- [x] Failed-send recovery (WhatsApp, Signal, SMS): a failed message reads "failed · tap to retry". WhatsApp and Signal retries preserve the wire id/timestamp so recipients dedupe, and WhatsApp retry re-uploads media too.
- [x] Message actions (WhatsApp + Signal): long-press a message for a LightOS menu of Reply, React, Retry, Delete and Delete for Everyone, offering only what applies. Delete removes it from Photon. Your own sent messages offer Delete for Everyone (WhatsApp revoke / Signal remote delete) after a confirm page. Reactions go on other people's messages only.
- [x] New message: find anyone by name or number across LP3 contacts and every platform's chats, then open their chat or start one on WhatsApp, Signal or SMS
- [x] Attachments (WhatsApp + Signal): Camera (when a camera app is available), Photos (the system photo picker) and Files (documents such as PDFs, sent under their own file name). Saved documents keep their real name, and every saved file gets its proper extension.
- [x] Voice notes: record and send on WhatsApp and Signal; incoming voice notes autoplay on open
- [x] In-app contact editor: tap a DM's title → save/update directly via `ContactsContract` (no AOSP handoff)
- [x] Ephemeral retention (configurable count + days) on WhatsApp and Signal, kept across bridge restarts
- [x] Notifications (WhatsApp + Signal): Messages and Calls each Sound, Vibrate, Silent or Off. Chats muted on your primary stay silent on both platforms, with a muted mark in the chat list. Tapping one opens that chat, and the chat on screen never gets one.
- [x] Connection status in settings
- [x] Reset/refresh per connection
- [x] Light SDK look: Akkurat font, LightOS menus, top and bottom bars, margins and type sizes measured against LightOS, haptics, monochrome
- [x] Thread styles: LP3 for DMs, LP2 for groups
- [x] Inline or full-screen compose, with the system keyboard or the LP3 keyboard
- [x] γ (gamma) app launcher icon
- [x] Scroll dial support (LP3 hardware wheel: Off / Slow / Medium / Fast)

## Known issues

This section lists open bugs and by-design limitations, one line each. Fixes waiting for on-device confirmation are in [`docs/VERIFY.md`](docs/VERIFY.md).

### Bugs

- **WhatsApp received images may not load**: tapping a received image can still fail to show it. The stale-cache cause is fixed, but real download failures (expired CDN media, bridge errors) need on-device logs to diagnose.
- **Signal sent-transcript results unchecked**: the sync transcript to your own devices ignores per-device results, so a sync failure that doesn't throw can leave a sent message missing from your other linked devices.
- **Replying from your phone can break a Signal contact's name**: a sent transcript's profile key is your own, but Photon stores it as the recipient's, so their profile name can fail to resolve until they next message you.

### Limitations

- **Calls can't be answered**: Photon has no voice or video stack, so calls only show as notifications and call rows.
- **Signal call edge cases**: a hangup that arrives without its offer is ignored, outgoing calls placed on the primary aren't recorded, and clearing the call log on the primary doesn't clear Photon's call rows.
- **Mutes and archives are read-only**: Photon follows mutes and archived chats set on your primary (WhatsApp and Signal) but can't mute or archive a chat itself. Calls still ring and missed calls still show in muted chats, and an @-mention doesn't break through a muted Signal group.
- **Signal mutes and archives need the storage key**: if Photon never got it, it asks the primary once, and the primary answers when it's online.
- **Unread Signal disappearing messages never expire**: as on Signal, a received message's timer only starts once it's read, in Photon or on another device.
- **A Signal read can be lost offline**: a message read in Photon starts its timer at once, and if it disappears before Photon can send the read sync, your other devices never learn it was read.
- **Signal timers are unknown until Photon learns them**: a DM's from its next message, transcript or contact sync (every DM after upgrading to 0.5.0), and a group's once Photon can fetch the group. Until then, Photon's messages there carry no timer and don't disappear.
- **Signal contact-sync timers without a version are ignored**, because they can't be ordered against what messages said.
- **Signal poll votes and poll endings aren't shown**: a poll shows as its question and options only.
- **Long Signal messages are cut short**: a message over 2,000 characters shows only its first part, and the rest isn't downloaded.
- **Signal view-once media from before 0.5.0 can still be saved**: those rows don't record that they were view-once, so they stay ordinary photos and videos.
- **A WhatsApp DM's timer can be unknown after upgrading to 0.5.0**: if none of its stored messages showed one, Photon learns it from the next message that does, and until then Photon's messages there don't disappear.
- **WhatsApp "disappear after read" isn't followed**: Photon doesn't handle `afterReadDuration` timers or `EPHEMERAL_SYNC_RESPONSE` messages.
- **WhatsApp "Keep chats archived" is assumed on** until WhatsApp syncs the setting, so until then a new message leaves an archived chat archived.
- **Live locations show only their starting point**: Photon doesn't follow a WhatsApp live location's updates.
- **The LP3's clock affects disappearing messages**: expiry is checked against it, and some start times come from other devices (a WhatsApp message's send time, a Signal transcript's), so a wrong clock deletes messages early or late.
- **Signal held envelopes are memory-only**: if Photon is killed after a message fails to process, the redelivered copy can't be decrypted again.
- **Signal message ids keep their random suffix**: Photon matches Signal messages by author and timestamp, and the rewrite of stored ids that would drop the suffix was deferred.
- **Signal chats drop off the list when all their messages age out**, as on WhatsApp.
- **Signal User-Agent impersonates Signal-Android 7.24.4**: a server-side minimum-version cutoff could block it.
- **Signal video send untested**: photo send works on device. Video uses the same path but hasn't been tried.
- **Voice notes untested**: recording and sending are wired up for WhatsApp and Signal, but neither has been tested on device.
- **Signal group admin**: Photon sends and receives in GroupV2 groups but can't create groups, add or remove members, rename, or leave. Changes made on other devices are picked up through the revision check on the next message.
- **Signal group sends fan out per recipient**: Photon doesn't use sender-key distribution, so sends to large groups are slower and `GroupSendEndorsements` are skipped.
- **Signal can only start chats with people it already knows**: New message reaches a number on Signal only when Photon already has its Signal account from the primary's contact sync or an earlier chat, because Photon doesn't use Signal's contact discovery.
- **No Signal history**: only messages received after linking appear. Importing history needs backup/restore support.
- **No Signal read receipts to senders**: Photon syncs read state to your own devices but never sends read receipts to message authors, because it can't see the account's read-receipt privacy setting.
- **Partial Signal verification state**: Photon only learns verified status from verification syncs received after linking. Accepting a changed safety number in Photon doesn't mark it verified on your other devices.
- **WhatsApp history doesn't gain new message types**: business templates, missed-call stubs and edits/revokes in rows stored before this version only appear after a re-pair, because history sync only runs at pairing.
- **WhatsApp reach check needs WhatsApp connected**: New message can only ask WhatsApp whether a number is on it while connected, and otherwise reads "WhatsApp disconnected", except for people you already have a WhatsApp chat with.
- **WhatsApp call-log dedupe is unverified**: it assumes a call-log message's id equals the call's id, which hasn't been confirmed.
- **A WhatsApp contact deleted on the phone keeps its name**, because whatsmeow ignores contact removals from app state.
- **A WhatsApp @-mention of an unknown LID stays as digits**: a mentioned member with no known number or name keeps the raw digits in the text.
- **SMS notifications come from the system SMS app** and linger after you read the chat in Photon, because Photon isn't the default SMS app.
- **SMS delivery reports aren't requested**: an SMS shows as sent once it leaves the phone, never as delivered.
- **SMS retry leaves the failed copy**: a failed SMS that the system already stored is sent again as a new message, and the failed one stays in the thread, because only the default SMS app can change the SMS store.
- **Delete only removes a message in Photon**: it doesn't sync to your primary or other linked devices. Delete for Everyone does.
- **Camera may be unavailable**: the attachment sheet only offers Camera when an app on the LP3 handles photo capture. Photos and Files still work.
- **Fictional UK numbers written nationally aren't recognised** (low priority): Android's number parser rejects the reserved `07700 900xxx` range unless it's written with +44, so New message offers no "Send to" row for it.
- **The LP3 keyboard has empty space above it**: docked inline, the Light keyboard leaves about 30dp of blank space above its first row.
- **Media re-download expires**: viewed media is deleted after 5 minutes and downloaded again on tap. That fails once WhatsApp's CDN copy expires or Signal's attachment pointer lapses (about 45 days).
- **Dictation unavailable**: the LP3 has no built-in speech-to-text, and the framework's `RecognitionService` permission check rejected third-party recognisers with error 9 (`ERROR_INSUFFICIENT_PERMISSIONS`) even with RECORD_AUDIO granted. This hasn't been retested since the LP3 moved to Android 14.

## Planned features

- [ ] Signal history sync (backup download + restore)
- [ ] Signal group admin: create, leave, add/remove members, edit title
- [ ] MMS support (picture messages)
- [ ] Dictation (requires system-level STT or alternative approach)
- [ ] Sticker rendering
- [ ] Option to set Photon as default SMS app (enables writing `read`/`seen` to the Android SMS provider so read state syncs with other SMS clients; currently tracked locally via SharedPreferences)
- [ ] Message search

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
- Failed-send recovery: outgoing rows are inserted with `status=sending` before the wire call (Go bridge pre-generates the stanza id for WhatsApp; Signal reuses the local id). Status flips to `sent`/`failed` on result. Retry reuses the same id + wire timestamp so recipients dedupe.
- SMS sends are tracked with a `sentIntent` per part. Photon shows the send as sending until the radio has reported every part. By then Android has stored it as sent or failed, and the stored row shows instead. A send `SmsManager` refuses outright never reaches the store: it shows as failed and retries as itself. A failed stored row retries as a new message.
- UI refresh is event-driven, through the same refresh flows on every platform: WhatsApp re-queries on bridge events (plus reconnect), Signal on a change flow emitted by its message DB, SMS via ContentObserver. A 45s fallback re-query catches missed events; queries stop when no screen is subscribed.
- The APK is about half its earlier size. libsignal's 100 MB *testing* native library is no longer bundled (its loader preferred it over the production library), and neither are libsignal's desktop natives or the Light SDK's ML Kit, CameraX and Google transport/telemetry dependencies. Photon only draws QR codes, with zxing.
- Toolchain: Gradle 9.0, AGP 8.12.3, Kotlin 2.3.20, compileSdk 36, minSdk 34 (Android 14+), targetSdk 35, JDK 17, core library desugaring enabled.

## License

MIT
