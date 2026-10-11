# Photon

Minimalist multi-protocol messaging app for the Light Phone III. WhatsApp (whatsmeow Go bridge) + Signal (libsignal-service-java) + SMS.

## Device

- **Light Phone III**: Nearly square screen — 1080x1240 @ 480dpi, 3.92" matte AMOLED. Do NOT assume standard tall smartphone proportions.
- Android 14+ (API ≥34; confirm with `adb shell getprop ro.build.version.sdk`), arm64-v8a only. Photon's minSdk is 34.
- Physical: scroll dial, two-stage camera button, home/menu button
- **Scroll dial**: Pixart pat9126ja optical rotary encoder (`/dev/input/event4`). LightOS maps to custom keycodes: `WHEEL_CW` (318, clockwise/down) and `WHEEL_CCW` (317, counter-clockwise/up). Raw Linux events are KEY_T/KEY_R but Generic.kl remaps them. `MainActivity.dispatchKeyEvent` consumes them unless Settings › Chat › SCROLL DIAL is OFF, and passes them through `ScrollDialState` to `ListScrollDialEffect`, `MenuScrollDialEffect` and `ChatScrollDialEffect` (`ui/shared/ScrollDial.kt`).

## LP3 Style Guide

Photon should feel native to the LP3. Understand these rules, then make deliberate exceptions when needed — don't deviate by accident.

### Light SDK

- The UI is built on the Light SDK's `sdk-ui` (`com.thelightphone.sdk.ui`): `LightTheme`, `LightText`, `LightTopBar`, `LightBottomBar`, `LightIcons`, `LightScrollView`, `lightClickable`, the grid helpers and the LP3 keyboard. Reach for an SDK component before writing your own.
- `PhotonTheme` (`ui/theme/Theme.kt`) is `LightTheme(LightThemeColors.Dark)` with haptics on.
- Never use `LightQrCodeScanner`. Its dependencies (ML Kit, CameraX, Google transport) are excluded in `app/build.gradle.kts`, so it fails at runtime. Photon only draws QR codes, with zxing (`QrUtils`).

### Font
- LightOS uses **Akkurat**. `LightTheme`'s typography loads it through the SDK's `lightFontFamily()`: the LP3's system copy, else a bundled one, else the Compose default. Don't set fonts yourself. Public Sans is not the LightOS font.

### Colour
| Role | Token | Value |
|------|-------|-------|
| Background | `LightThemeTokens.colors.background` | `#000000` |
| Content | `LightThemeTokens.colors.content` | `#FFFFFF` |
| Secondary | `contentSecondary` (`LightText(lighten = true)`) | `#BBBBBB` |
| Faint | `PhotonColors.Faint` | `#666666` |

Use Faint only where something must recede further than secondary: deleted-message placeholders, the empty input hint, disabled actions. **No colour**: no decorative, brand or status colours anywhere.

### Type
Use `LightText` with a `LightTextVariant`. For components that take a `TextStyle`, use `lightTextStyle(variant)`.

| Use | Variant |
|-----|---------|
| Message text | `Paragraph` |
| Timestamps, sender labels, status, quotes, call rows | `Detail` (lightened) |
| Menu row label / value | `Copy` / `Paragraph` (lightened) |
| Conversation name / time / unread count | `Copy` / `Detail` / `Superfine` |
| Top bar title | `LightTopBarCenter.Text` |

### Layout
- **Grid units**: measure in LP3 grid units (`Float.gridUnitsAsDp()`; the screen is 27×31 units), and keep Photon's measurements in `PhotonGrid` (`ui/theme/Theme.kt`).
- **Top bars**: `LightTopBar` with `backButton()` (`LightIcons.BACK`) on the left and a centred title. The chat lists put `settingsButton()` on the right. There's no `<` glyph.
- **Touch**: `lightClickable` gives no ripple and a haptic tick. Message rows use `lightCombinedClickable`, which buzzes on long-press only.
- **Menus**: LightOS menus, via `MenuScreen` / `MenuRow` (`ui/shared/components/LightMenu.kt`): large left-aligned rows, an optional value beneath, no dividers.
- **No transitions** between pages (frame-by-frame, LP3 style).
- **Loading states**: Show nothing (null initial) until data loads — no flash of empty state.

### Photon's deliberate exceptions from LightOS
- **The scroll dial scrolls Photon.** LightOS uses the dial for brightness. SCROLL DIAL OFF hands it back.
- **All Chats** merges three platforms and shows a platform icon on each row.
- **Group threads use the LP2 layout** ("Name  12:36" above each run). DMs use the LP3 thread.
- **KEYBOARD defaults to SYSTEM** (the installed keyboard). LIGHT gives the LP3 keyboard.

## Architecture

- Kotlin/Compose, single app module (`app`). The Light SDK's `:sdk:ui` is built from source as a Gradle composite build (see Build).
- **Sessions**: `PhotonService` (foreground service) only orchestrates. It builds one session per platform — `WhatsAppSession`, `SignalSession`, `SmsSession` (`service/`) — starts them, and publishes them through `Sessions`. Each session owns its parts and its start, stop and reset.
  - `Sessions` is a Compose-observable holder (`mutableStateOf`). The UI reads `Sessions.whatsapp` / `.signal` / `.sms` or `Sessions.backend(platform)`.
  - They're null until the service has started. A screen that read null recomposes when they appear.
  - There are no statics on `PhotonService`, and no DI framework.
- **`ChatBackend`** (`chat/`): one interface over each platform's repository — `WhatsAppBackend`, `SignalBackend`, `SmsBackend` — so one chat screen and one chat list serve all three.
  - It provides the conversation list (`StateFlow`, null until first read), messages, group participant names, sends, reactions, retry, delete, mark-read and media download.
  - `ChatCapabilities` says what the screen may offer.
  - Every suspend call throws `ChatBackendException` and nothing else (bar cancellation).
  - Platform hooks: `reconnects`, `onOpen`, `Header` (Signal's safety-number notice), `contactPhone` (read when a DM title is tapped) and `mayHaveContactPhone` (false keeps the title untappable, e.g. an SMS alphanumeric sender).
  - Add platform behaviour here, not as `if (platform == …)` in the screens.
- **Mark-read**: `MarkReadGate` (`chat/MarkRead.kt`) marks a chat read only when it holds an incoming message that no successful request has covered yet. A request covers the newest incoming message and every incoming one still `UNREAD`, so a Signal message that sorts below newer ones is marked too. One request runs at a time. A failed request is asked again on the next change or when the backend `reconnects`.
- **Navigation** (`nav/`): two routes cover every platform. `chats?platform={platform}` is the root and the only list (All Chats when null), and `chat/{platform}/{jid}` is the chat.
  - `pickedRoute` gates pairing: WhatsApp pairs only when the bridge reports `logged_out`, and Signal when the device isn't linked.
  - A notification's extras become a `ChatDeepLink`, which opens the chat over the root list.
- **`MessageStatus`** (`data/model/`): one enum for every platform. Your own messages are `SENDING → SENT → DELIVERED → READ`, or `FAILED`. Incoming ones are `UNREAD`, `SEEN` (Signal: read here, not yet synced) or `READ`. A deleted message is `DELETED`. Each store keeps its own strings and maps them when it reads a row (`MessageStatus.outgoing`, `smsStatus`, the Signal and WhatsApp cursor mappers), so nothing stored changed.
- **Refresh flows** (`data/repository/RefreshFlows.kt`): `conversationsFlow` and `messagesFlow` serve all three platforms. They re-query on change signals, conflated, plus a 45s fallback tick.
  - A read error keeps the last good value and retries.
  - A suspect empty list is re-checked for a few seconds before it's believed.
  - Queries stop 5s after the last subscriber leaves.
- **WhatsApp**: Go binary (`libgobridge.so`) run as a child process (`GoBridge`). `BridgeClient` (formerly `WsClient`) talks to it with one JSON envelope per line over the process's stdin/stdout. There's no socket or port.
  - stderr goes to logcat under `GoBridge` (thread `gobridge-log`). The stdout reader thread is `gobridge-stdout`.
  - The bridge exits when its stdin closes. `GoBridge` closes stdin to stop it, and kills it after 1.5s.
  - `WhatsAppSession` restarts a bridge that exits, backing off from 1s to 60s, and stops it for certain before a reset. Two bridges must never share the WhatsApp session.
  - `send_media` and media retries only accept staged files: `filesDir/send_media_*`, `filesDir/voice_*`, or the bridge's own downloads in `filesDir/media/`. No relative paths or symlinks.
  - Shared SQLite (`files/messages.db`): Go writes, Kotlin reads. WAL, `busy_timeout=5000` and foreign keys are set per connection, and the DSN adds `_txlock=immediate`.
- **Signal**: In-process Java library (Turasa signal-service-java v143 + libsignal-android 0.92.1 AAR), with its own SQLite databases for the protocol store and messages. Code layout:
  - **Receiver**: `SignalMessageReceiver` is a facade (connection, reconnect, upkeep) over the handlers in `signal/receiver/`: `EnvelopeProcessor`, `DataMessageHandler`, `SyncMessageHandler`, `CallHandler`, `Receipts`, `RemoteEdits`, `Attachments`, `ContactsSync`, `ContactNames`, `GroupMetadata`, `PreKeyRefill`, `SessionPings`, `NetworkMonitor`, `MessageNotifications` and `SignalNotifier`.
  - **Message DB**: `db/SignalMessageDatabase.kt` holds the schema, migrations and change notifications. Queries are extension functions grouped by concern: `SignalConversations`, `SignalMessages`, `SignalReadState`, `SignalContacts`, `SignalReactions`, `SignalCallRows`, `SignalRetention` and `SignalServiceIdMerge`.
  - **Sender pipeline**: `SignalMessageSender` is the facade. A `Recipient` (`Dm` / `Group`) and `OutgoingContent` (`Message` / `Reaction` / `RemoteDelete`) go through a `SignalTransport` (all in `SignalOutgoing.kt`). `LibraryTransport` (`SignalLibraryTransport.kt`) does the library sends and uploads. `SenderConnection` owns the library sender and attaches to the receiver's sockets.
- **SMS**: `SmsRepository` reads Android's SMS provider (`content://sms`) through the shared refresh flows, triggered by a `ContentObserver` and LP3 contact changes.
  - The list scan records each conversation's provider threads, and a chat's messages are read by `thread_id`.
  - Photon isn't the default SMS app, so read state lives in its own preferences, and it can't write to the provider.
  - Sends pass one `sentIntent` per part (broadcast `app.photon.sms.SENT`, heard while `SmsSession` runs), and no delivery intent. `PendingSends` shows a send as sending until every part has reported. Android stores the message (sent or failed) just before the last report, so the send is then forgotten and the provider row shows instead. Until then, a sending row is hidden if the provider already holds its match: same text, and newer than any row the chat showed before the send.
  - A send `SmsManager` refuses outright never reaches the provider. It shows as failed and retries as itself. A failed row in the provider is sent again as a new message.
- All three share the same data models (`Conversation`, `Message`, `MessageStatus`).
- Toolchain: Gradle 9.0, AGP 8.12.3, Kotlin 2.3.20 (plugin versions track the Light SDK's, as an included build must match), compileSdk 36, minSdk 34, targetSdk 35, JDK 17, core library desugaring enabled.

## Build

The Light SDK is the git submodule `third_party/light-sdk` (`lightphone/light-sdk`, tag v0.2.0). `settings.gradle.kts` includes it as a composite build and substitutes `com.thelightphone:sdk-ui` with its `:sdk:ui` project. Clone with `--recurse-submodules`, or run `git submodule update --init`.

```sh
# Go bridge (only if gobridge/ changed; needs Go 1.25+)
cd gobridge
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -buildvcs=false -trimpath \
  -ldflags="-s -w" -o ../app/src/main/jniLibs/arm64-v8a/libgobridge.so .

# APK (set JAVA_HOME and ANDROID_HOME — see CLAUDE.local.md for machine-specific paths)
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Use `./gradlew clean assembleDebug` when Go bridge binary changed — Gradle may cache the old native lib. Machine-specific build paths are in `CLAUDE.local.md` (gitignored).

- **Tests**: `cd gobridge && go test ./...` and `./gradlew testReleaseUnitTest`. CI runs both on every push to main and every PR. The release workflow runs `testReleaseUnitTest` before `assembleRelease`. Robolectric tests use `@Config(sdk = [34])` to match minSdk.
- **Packaging**: `libsignal_jni_testing.so` is excluded. libsignal's loader prefers it over `libsignal_jni.so`, so shipping it meant the test build ran. libsignal-client's desktop natives (`.dylib`, `.dll`, desktop `.so`) are excluded too.

## Screenshots

Take screenshots to verify UI changes — don't guess what the screen looks like:
```sh
adb shell screencap -p /data/local/tmp/s.png && adb pull /data/local/tmp/s.png /tmp/lp3_screen.png && adb shell rm /data/local/tmp/s.png
```
Then use Read tool on `/tmp/lp3_screen.png` to view. Always clean up screenshots on device after.

## Shared UI components

One chat screen and one chat list serve every platform, over its `ChatBackend`:
- `ui/chat/ChatScreen.kt`: `ChatScreen(platform, jid)` takes `Sessions.backend(platform)` and shows the thread with what `ChatCapabilities` allows. It also wires `MarkReadGate`, `VisibleConversationEffect`, the media viewer, and the contact editor (a DM title opens it with the number `contactPhone` returns on tap). There are no per-platform chat or list screens.
- `ui/chat/ChatListScreen.kt`: `ChatListScreen(filter)`. With no filter it's All Chats: every platform's chats by recency, with platform icons. Filtered, it's one platform's chats. The title opens the platform picker (`ui/home/PlatformsScreen.kt`).
- `ui/shared/ChatScreenContent.kt`: top bar, message list and input.
  - COMPOSE INLINE puts `MessageInput` under the thread. FULL SCREEN puts a `ThreadBottomBar` (attach, voice note, compose) under it, with a full-screen editor overlay (`components/FullScreenCompose.kt`).
  - KEYBOARD SYSTEM uses the installed keyboard. LIGHT uses the SDK's LP3 keyboard (`components/LightKeyboard.kt`, `LightInputField.kt`).
  - Tapping the thread or turning the dial hides the keyboard. There's no keyboard toggle.
  - One draft per conversation, shared by both input styles.
- `ui/shared/components/MessageLayouts.kt`: `MessageRow` lays out DMs and groups (groups pass `senderName`), and `CallRow` is the single centred call line. It also has the burst rule (`startsBurst`: a sender change or a gap over 15 minutes), `formatContent` and `quoteLabel`.
- `ui/shared/components/MediaInput.kt`: attaching media and recording voice notes, shared by both input styles (`rememberMediaInput`).
- `ui/shared/ChatListContent.kt` and `ConversationRow.kt`: the list's top bar, loading and empty states, and rows. `ChatListContent` also reconciles notifications.
- `ui/preview/DesignPreviews.kt`: LP3-sized (360×413dp) previews. `PreviewSamples` covers bursts, a call, a failed send, reactions, a reply, a deleted message and long titles. The layout tests use them too.

## Display names

One policy for every person on every platform, in `DisplayNames` (`data/names/`, pure Kotlin):
1. LP3 contact
2. the platform's address book (WhatsApp: the primary's synced contact name; Signal: the primary's contact sync)
3. verified business name
4. profile or push name
5. the number, formatted for the device's country
6. `WhatsApp contact` / `Signal contact` / `Unknown`

A group shows its subject, else `WhatsApp group` / `Signal group`. Raw JIDs, LID digits, ACIs and base64 group ids are never shown: a stored "name" that looks like one is treated as blank.

- **Resolve at display time.** Repositories store the sources and resolve when they read, so a policy change needs no migration. Every title, sender label, quote and notification goes through `DisplayNames`.
- **`LocalContacts`** is the LP3 address book, one per process (`LocalContacts.get`). It keeps one lookup cache (misses included) and a `ContactsContract` observer, debounced to 500ms. Each change clears the cache and bumps `version`, and the name flows re-resolve on `changes`. Without READ_CONTACTS, nothing resolves until the permission is granted.
- **WhatsApp**: the bridge stores the best name WhatsApp gives (address book `FullName` > verified business name > push name) in `conversations.name` and `participants.display_name`, with a `name_rank` (3/2/1; a group subject ranks 3). It never stores a number.
  - A write only replaces a name of lower or equal rank. History sync never replaces an equal rank.
  - `WhatsAppNames` (Kotlin) puts the LP3 contact on top for anyone whose number it knows.
  - The bridge emits `names_changed` after contact, push-name, business-name and group-subject changes.
- **Signal**: `SignalNames` resolves when read, from the `contacts` row: the LP3 contact for its number, then `contacts.name` (written only by contact sync, from the primary's address book), then `profile_name`, then the number.
  - Nothing resolved is stored.
  - DB v5 cleared the resolved names older builds had stored.

## WhatsApp quirks

- **LID JIDs**: WhatsApp uses `@lid` JIDs for many conversations. The message handler resolves LID→phone via `GetPNForLID` for consistent conversation keys. Typing, call, reaction and mark-read paths resolve the same way.
- **Reconcile**: about 60s after each connect, the bridge runs `reconcile()` (`handlers.go`): it merges `@lid` conversations into phone rows, canonicalises LID senders, and backfills contact and participant names.
- **Content-free messages** (sender-key distribution and the like) are dropped. Unknown types are stored as `[ unsupported message ]`. Business templates, interactive messages, lists and polls are rendered as text (`extract.go`).
- **Redeliveries**: messages are inserted with `INSERT OR IGNORE`, so a redelivered message isn't counted or notified twice.
- **`is_group`** is set from the JID on every conversation write, so there's no connect-time repair.
- **Group names**: `groupName()` (`groups.go`) fetches the subject via `GetGroupInfo()` and caches it for an hour. `events.GroupInfo` renames update the cache and the stored name at once. Participant names are stored in the participants table.
- **Migrations** are versioned with `PRAGMA user_version` (`db.go`): 1 `muted_until`, 2 legacy read state, 3 name ranks. Each logs `Applied message DB migration N`.
- **Write lock**: the DSN's `_txlock=immediate` takes the write lock up front, which avoids SQLite `BUSY_SNAPSHOT` (517) errors. A revoke that fails anyway is retried in the background.
- **Edits and revokes** are applied only when they come from the sender (or the user's own devices). A group admin may also revoke.
- **Reads**: only the user's own reads clear unread. A contact's read receipt never does. Mark-read and mark-unread from the primary are applied.
- **Mutes**: `muted_until` is 0 (not muted), -1 (always) or the unix second the mute ends. Kotlin decides expiry when it reads the row. Migration 1 copied the end times from whatsmeow once.
- **Calls**: one row per call, `call_<id>`. The phone's call-log message is re-keyed to the same id, so whichever arrives second is ignored.
  - This assumes the log entry carries the call's id, which is unverified. Video detection is unverified too.
  - An ended call can't ring again for 10 minutes.
- **Retention** is passed on the command line (`--max-messages`, `--max-days`, `--media-ttl-mins`), so it survives bridge restarts. `set_retention` applies changes live.
- **History sync**: Only happens during initial pairing. Re-pairing needed for full refresh (Reset in settings).

## Signal quirks

- **Java Records**: Turasa v143 uses `LinkDeviceRequest` as a Java Record. Android desugaring can't serialize via Jackson. Fixed with `AndroidRecordFix.kt` which patches JsonUtil's ObjectMapper via reflection to handle specific Record classes.
- **Self-signed CA**: Signal uses its own CA. Bundled as `res/raw/signal_ca.pem`, loaded into BKS keystore at runtime via `SignalConfig.init()`.
- **Kyber pre-keys**: The last-resort Kyber key, generated at registration and on every refill, must be stored via `storeLastResortKyberPreKey` (`is_last_resort=1`), or `markKyberPreKeyUsed()` deletes it. One-time Kyber pre-keys are stored with `is_last_resort=0` and deleted on use.
- **Pre-key refill**: `PreKeyRefill` (`signal/receiver/PreKeyRefill.kt`) runs in connect upkeep, at most every 10 minutes, when the server's ACI EC or Kyber count drops below 10.
  - It rotates the signed pre-key and the last-resort Kyber key, then uploads fresh batches via `KeysApi.setPreKeys()` only. There's no reflection fallback.
  - Always rotate the signed pre-key: re-uploading the registration-time one was rejected with 422 "Invalid signature", which starved the server of one-time keys.
  - A failed refill is retried on the next connect.
- **Reflection helper**: `serviceRequest()` in `SignalConfig.kt` still calls `PushServiceSocket.makeServiceRequest` through reflection, for the attachment upload form and the logout `DELETE /v1/devices/{id}`. A library upgrade can break it.
- **Calls**: rows are `call_{aci}_{callId}` (group calls `gcall_{group}_{eraId}`).
  - A ring with no hangup expires to missed after 2 minutes. An offer more than 2 minutes old on arrival is recorded as missed without ringing.
  - A hangup without an offer, outgoing calls from the primary, and call-log CLEAR are ignored.
- **Reconnect**: backoff from 5s, doubling to 300s. `NetworkMonitor` resets it when a validated network appears, which needs `ACCESS_NETWORK_STATE` in the manifest. Two credential rejections (401/403) stop the receiver as `logged_out`.
- **PNI**: a conversation keyed by a contact's PNI is merged into their ACI conversation (`SignalServiceIdMerge`). The merge runs at receiver start, after a contacts sync, and when an envelope's PNI resolves.
- **Message ids** are `{authorAci}_{timestampMs}_{rand}`. Reactions, deletes and read syncs match by the `{author}_{timestamp}` prefix.
- **DB migrations**: v4 added per-row read state (`received` = unread, `seen` = read here but not synced, `read`, `deleted`). v5 cleared stored resolved names. There's no `onDowngrade`, so an older build can't open the database.
- **Held envelopes**: an envelope that fails processing is acked only after it's handled, and is retried in-process (`EnvelopeRetryTracker`). The tracker is memory-only.
- **Sync message destinations**: Use `destinationServiceIdBinary` field, not the string `destinationServiceId`.
- **AccountAttributes.Capabilities**: All must be `true` for linked device registration.
- **Device name**: Set via `store.DeviceProps.Os` before client creation in Go bridge (WhatsApp) and `AccountAttributes.name` (Signal). The Signal name must be encrypted with `DeviceNameUtil.encryptDeviceName`; plaintext shows up garbled in the primary's linked-devices list.

## Speech/voice on LP3

- **Text-to-speech**: `com.lightos.speech` provides TTS engine (standard LP3).
- **Speech-to-text**: LP3 has no built-in STT. No Google Speech Services.
- **Dictation**: Photon has no dictation code. The manifest keeps a `<queries>` block for `android.speech.RecognitionService`, so a future attempt can discover recognisers with `queryIntentServices`.
- **Current status**: Needs a working RecognitionService on the device. See `CLAUDE.local.md` for device-specific speech services.

## SMS quirks

- **Alphanumeric senders**: "HSBC UK", "Specsavers" etc are kept as-is. `normalizeAddress` only strips formatting from phone numbers.
- **Contact resolution**: through `LocalContacts`, like the other platforms. An unsaved number is formatted.
- **Not the default SMS app**: Photon can't write the provider, so it can't delete messages, mark them read for other apps, or replace a failed row.
- **No MMS**: Only SMS supported currently.

## Known issues and verification

Two files track problems. Keep both accurate.

- **`README.md` → Known issues** lists open bugs and by-design limitations only, one line each: a bold title and one sentence giving the symptom or limitation and its current state. Group entries under Bugs (including inconsistencies) and Limitations.
- **`docs/VERIFY.md`** lists fixes that are implemented but not yet confirmed on the LP3.
  - It's grouped by release (the current one with a short "Start here" list of its riskiest checks), then by area. Older releases' entries sit under Earlier fixes.
  - Each check has an id (A1, W3, …), the steps (**Do**), the expected result (**Expect**), and any useful `adb logcat` lines, DB queries and caveats such as "needs a re-pair".
  - An entry that came from a README issue says what was wrong (**Was**).
- Neither file holds fix narratives. Root-cause stories go in commit messages.

Lifecycle:

1. **New bug or limitation found**: add a one-liner to README Known issues.
2. **Fix implemented**: move the entry to `docs/VERIFY.md` with verification steps and remove it from the README. If the fix is partial, keep a one-line README entry for what's still open and mark the VERIFY entry *(partial)*.
3. **User confirms on device**: delete the VERIFY entry, and its partial README line if it has one. Only do this when the user explicitly confirms. A code change or a passing test is not confirmation. The fix history stays in git.
4. **Verification fails**: move the entry back to README Known issues as an open bug, saying what you saw.

## Notifications

- **Channels** (`PhotonApp`): `photon_messages` (IMPORTANCE_HIGH) for messages and calls, and `photon_service_v3` (IMPORTANCE_NONE) for the foreground service, hidden by default. The old `photon_service_v2` channel is deleted at startup.
- **Ids**: `NotificationHelper` tags each notification with its conversation JID and gives each kind its own id (message, incoming call, missed call). A chat's notifications replace each other rather than stacking, and can be cancelled after a restart.
- **Tap**: opens the chat. The intent carries `EXTRA_PLATFORM` and `EXTRA_CONVERSATION`, which `ChatDeepLink` turns into `chat/{platform}/{jid}` over the chat list.
- **Visible chat**: `VisibleConversationEffect` marks the chat on screen while it's resumed and clears its notifications. Message and missed-call notifications aren't posted for it, but a ringing call is.
- **Reconcile**: `ChatListContent` calls `NotificationHelper.reconcile` on every list update, which clears message and missed-call notifications for chats whose unread count is 0.
- **Text**: one formatter, `messageNotificationText` (`service/MessageNotificationText.kt`). A DM is titled with the person, and a group with the group, with a body of "Sender: text". The platform label is appended to the title (`· WhatsApp`).
- **Corrections** (`update = true`, e.g. after a delete-for-everyone) use `setOnlyAlertOnce`, so they don't sound again.
- **WhatsApp**: `WhatsAppSession` subscribes losslessly to the bridge events `new_message`, `read_self`, `message_revoked`, `call_incoming`, `call_ended` and `call_missed`. Muted chats don't notify messages. Calls ring regardless of mute.
- **Signal**: `signal/receiver/MessageNotifications` handles messages and corrections, and `CallHandler` handles calls, both through `SignalNotifier`. Signal mutes aren't synced, so they aren't honoured.
- **SMS**: not notified. The system SMS app handles notifications.
- Configurable via Settings › Chat › NOTIFICATIONS (ON/OFF).

## Logs

Logcat tags: `GoBridge` (the bridge's own output, at debug level), `BridgeClient`, `PhotonService` (the service and the WhatsApp and Signal sessions), `SignalMessageReceiver` (the receiver and every `signal/receiver/` handler), `SignalMessageSender` (the sender, transport and connection), `SignalRepository`, `SignalGroupV2Manager`, `SignalAccountManager`, `SmsRepository` and `LocalContacts`.
