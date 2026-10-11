# Photon

Minimalist multi-protocol messaging app for the Light Phone III. WhatsApp (whatsmeow Go bridge) + Signal (libsignal-service-java) + SMS.

## Device

- **Light Phone III**: Nearly square screen — 1080x1240 @ 480dpi, 3.92" matte AMOLED. Do NOT assume standard tall smartphone proportions. The screen is 27×31 LP3 grid units, so 1 unit = 40px.
- Android 14 (API 34), confirmed on device. arm64-v8a only. Photon's minSdk is 34.
- Physical: scroll dial, two-stage camera button, home/menu button
- **Scroll dial**: Pixart pat9126ja optical rotary encoder (`/dev/input/event4`). LightOS maps to custom keycodes: `WHEEL_CW` (318, clockwise/down) and `WHEEL_CCW` (317, counter-clockwise/up). Raw Linux events are KEY_T/KEY_R but Generic.kl remaps them. `MainActivity.dispatchKeyEvent` consumes them unless Settings › Chat › Scroll Dial is Off, and passes them through `ScrollDialState` to `ListScrollDialEffect`, `MenuScrollDialEffect` (one menu row per step) and `ChatScrollDialEffect` (`ui/shared/ScrollDial.kt`).

## LP3 Style Guide

Photon should feel native to the LP3. The rules below were measured against LightOS on the device (Messages, Settings, Search). Follow them. When Photon has to differ, add the difference to the exceptions list with its reason, so it's deliberate rather than drift.

### Light SDK

- The UI is built on the Light SDK's `sdk-ui` (`com.thelightphone.sdk.ui`): `LightTheme`, `LightText`, `LightTopBar`, `LightBottomBar`, `LightIcons`, `LightScrollView`, `lightClickable`, the grid helpers and the LP3 keyboard. Reach for an SDK component before writing your own.
- `PhotonTheme` (`ui/theme/Theme.kt`) is `LightTheme(LightThemeColors.Dark)` with haptics on.
- Never use `LightQrCodeScanner`. Its dependencies (ML Kit, CameraX, Google transport) are excluded in `app/build.gradle.kts`, so it fails at runtime. Photon only draws QR codes, with zxing (`QrUtils`).

### Font
- LightOS uses **Akkurat**, and it loads on the LP3. `LightTheme`'s typography loads it through the SDK's `lightFontFamily()`: the LP3's system copy, else a bundled one, else the Compose default. Don't set fonts yourself. Public Sans is not the LightOS font.

### Colour
| Role | Token | Value |
|------|-------|-------|
| Background | `LightThemeTokens.colors.background` | `#000000` |
| Content | `LightThemeTokens.colors.content` | `#FFFFFF` |
| Secondary | `contentSecondary` (`LightText(lighten = true)`) | `#BBBBBB` |
| Faint | `PhotonColors.Faint` | `#666666` |

Use Faint only where something must recede further than secondary: deleted-message placeholders, the empty input hint, disabled actions and unreachable Send via rows. **No colour**: no decorative, brand or status colours anywhere.

### Type
Use `LightText` with a `LightTextVariant`. For components that take a `TextStyle`, use `lightTextStyle(variant)`.

| Use | Variant |
|-----|---------|
| Menu labels, conversation names, New message rows and its "To" field | `Heading` (56–59px cap height, as LightOS Settings and Messages) |
| Menu values, list times, detail lines under a row | `Detail` (lightened) |
| Message text | `Paragraph` |
| Timestamps, sender labels, status, quotes, call rows in a thread | `Detail` (lightened) |
| Unread count | `Superfine` |
| Empty and loading states, page explanations | `Copy` |
| Bottom-bar text buttons | the SDK `Button` style, through `LightBarButton.Text` |
| Top bar title | `LightTopBarCenter.Text` |

Case:
- **Menu labels** are Title Case, as LightOS Settings writes them ("Airplane Mode", "Account & Info", "Scroll Dial", "Delete for Everyone").
- **Values** are sentence case ("Full screen", "Not linked"). `sentenceCase()` (`SettingsScreen.kt`) turns the preference enums' capitals into values.
- **Bottom-bar text buttons** are spaced caps ("ALL CHATS", "DELETE", "ACCEPT CHANGE").
- **Empty and loading states** are lower-case fragments, as LightOS writes them: "no messages", "connecting", "syncing", "downloading…", "waiting for QR code…". Full sentences keep normal case.

### Layout
- **Grid units**: measure in LP3 grid units (`Float.gridUnitsAsDp()`), and keep Photon's measurements in `PhotonGrid` (`ui/theme/Theme.kt`).
- **Margins**: page content is inset 2 units on both sides (`PhotonGrid.MARGIN`), so on the 1080px screen it spans x 80–1000, as in LightOS Messages, Settings and Search.
- **Scroll views** span the full width and inset their content inside: `PageScrollView` for menus and forms, or a row that insets itself, as `ConversationRow` does in the chat list. The scroll bar is drawn `Inside` (`PageScrollBar`), in the right margin by the screen's edge (track at x≈1040). **Never pad around a scroll view.** The SDK's default `Outside` bar on a padded lazy list reserves its width twice, which put the chat list's bar two units from the edge and its times three units short of it.
- **Row heights**: menu rows are 5 units (`MENU_ROW_HEIGHT`, LightOS Settings' 200px pitch). Chat-list rows are 4 units (`LIST_ROW_HEIGHT`, LightOS Messages' 160px pitch). The chat list's lazy view needs that uniform height for the SDK's scroll bar.
- **Main chat list**: no top bar. The list starts below an empty top-bar height (`TOP_BAR_HEIGHT`, 3 units), as LightOS Messages does. A `LightBottomBar` that stays put while the list scrolls holds Settings on the left (the LightOS convention, as in Calendar and Weather), the platform filter in the centre as a text button ("ALL CHATS", "SMS", "WHATSAPP", "SIGNAL") that opens the platform picker, and New message on the right. Loading and empty states keep the bar.
- **Chat-list rows**: platform icon (All Chats only), name in `Heading` on one line with an ellipsis, then the muted mark, the unread count and the time, 0.5 units apart. The time always ends the row, so times form one column at the right margin, muted or not.
- **Secondary screens**: `LightTopBar` with `backButton()` (`LightIcons.BACK`) on the left and a centred title. Back is the only exit: no tap-anywhere-to-close and no Cancel button. A page's contextual action goes top right (the media viewer's Save). A confirming action goes in a bottom bar as a text button (DELETE, ACCEPT CHANGE). There's no `<` glyph.
- **Menus**: LightOS menus, via `MenuScreen` / `MenuRow` (`ui/shared/components/LightMenu.kt`): left-aligned `Heading` labels in Title Case straight under the top bar, an optional `Detail` value beneath, no dividers. A disabled row is Faint and doesn't respond.
- **Pages over the thread** (the long-press menu, the attachment sheet, the full-screen editor, the media viewer, the safety-number review) are full screen and opaque, stop touches reaching the thread, and return to it on Back. The long-press menu and the attachment sheet put the keyboard away as they open, and the dial leaves the thread alone while they or the editor are open. A page that can't live in the screen's own layout (the safety-number review, opened from the chat header) uses a `Dialog` with its window animation and dim turned off.
- **Long-press** opens a full-screen LightOS action menu (`MessageActions`), never a popup. It lists only the actions that apply (`messageActions`). A message with none has no long-press and no buzz.
- **Attachment sheet** (`AttachSheet`): the thread's title in the top bar, unlabelled icons (Camera, Photos, Files) in a row about three quarters of the way down, 220px apart centre to centre, and a down chevron in the bottom bar that closes it.
- **Touch**: `lightClickable` gives no ripple and a haptic tick. Message rows use `lightCombinedClickable`, which buzzes on long-press only.
- **No transitions** between pages (frame-by-frame, LP3 style).
- **Loading states**: Show nothing (null initial) until data loads — no flash of empty state or default values. Settings › Chat and Storage show their rows only once the stored values are read.

### Photon's deliberate exceptions from LightOS
- **The scroll dial scrolls Photon.** LightOS uses the dial for brightness. Scroll Dial: Off hands it back.
- **All Chats** merges three platforms and shows a platform icon on each row.
- **Group threads use the LP2 layout** ("Name  12:36" above each run). DMs use the LP3 thread.
- **The thread has no scroll bar.** It's a plain `LazyColumn`, because the SDK's lazy scroll view needs rows of one height and messages vary.
- **The inline input bar and the system-keyboard editor.** Compose: Inline (the default) keeps an input bar under the thread. In Compose: Full screen, Keyboard: System (the default) uses `SystemComposeEditor`, the SDK editor's layout around a real text field, so the installed keyboard can type. LightOS has only the full-screen editor with the LP3 keyboard, which Keyboard: Light gives (`LightTextInputEditor`).
- **Voice notes stay a top-level mic**, not in the attachment sheet, by the user's preference: inline `[+] [field] [mic/send]`, full screen `[+] [voice] [compose]`. Both use `VoiceNoteIcon` (`LightIcons.MICROPHONE`, "Voice note").
- **Material `Icon`** draws icons that need the secondary tint (the platform icons and the muted mark in `ConversationRow`).
- **The Faint colour** (`PhotonColors.Faint`) is beyond the SDK palette.

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
  - For New message: `reach(phoneE164)` says whether a number can be messaged on the platform, as `Reach.Reachable(jid, existing)` (the chat to open, and whether it already has messages) or `Reach.Unreachable(reason)` (a reason fit to show, such as "not on WhatsApp"). It never throws. `newChatTitle(jid)` titles a DM that has no conversation yet, by the `DisplayNames` policy, until its first message creates one.
  - Add platform behaviour here, not as `if (platform == …)` in the screens.
- **Mark-read**: `MarkReadGate` (`chat/MarkRead.kt`) marks a chat read only when it holds an incoming message that no successful request has covered yet. A request covers the newest incoming message and every incoming one still `UNREAD`, so a Signal message that sorts below newer ones is marked too. One request runs at a time. A failed request is asked again on the next change or when the backend `reconnects`.
- **Navigation** (`nav/`): one list route and one chat route cover every platform. `chats?platform={platform}` is the root and the only list (All Chats when null), and `chat/{platform}/{jid}` is the chat.
  - `platforms` is the platform picker, opened from the list's filter. `new-message` (`Routes.NEW_MESSAGE`) is New message, opened with `openNewMessage()` from the list's bottom bar. A chat picked there opens in its place (`openChatFromNewMessage`), so Back from the chat returns to the list.
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
  - `send_media` and media retries only accept staged files: `filesDir/send_media_*`, `filesDir/voice_*`, or the bridge's own downloads in `filesDir/media/`. No relative paths or symlinks. Pickers' files are copied to `send_media_*` with an extension from their MIME type. The camera writes into `files/camera/` through the FileProvider `app.photon.camera` (`res/xml/camera_paths.xml`, its only root), and the photo is renamed to a `send_media_*.jpg` before it's sent.
  - Shared SQLite (`files/messages.db`): Go writes, Kotlin reads. WAL, `busy_timeout=5000` and foreign keys are set per connection, and the DSN adds `_txlock=immediate`. Foreign keys mean a message needs its conversation row first (see WhatsApp quirks).
  - `check_on_whatsapp` (`reach.go`, `BridgeClient.checkOnWhatsApp`) answers New message's reach check, `{phones}` → `results` of `{phone, jid, on_whatsapp, existing}`. A number with a chat here is answered from the database. A chat still keyed by the person's LID is merged into their phone JID's first, so the chat opened is the one sends use. The rest go to WhatsApp in one whatsmeow `IsOnWhatsApp` query, which needs WhatsApp connected; offline the request fails with `WhatsApp disconnected`. Ask about one person at a time, as the official app does. Never check numbers in bulk, which WhatsApp treats as contact scraping.
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
  - Go tests' in-memory message database has foreign keys off. A test of anything that inserts messages should use `newFKClientBridge` (`firstmessage_test.go`), which opens the production DSN with foreign keys on. That gap hid the dropped first messages.
  - `NotificationLevelChannelTest` runs under a plain `Application`, because `PhotonApp`'s notification-level collector raced the level each test sets.
- **Packaging**: `libsignal_jni_testing.so` is excluded. libsignal's loader prefers it over `libsignal_jni.so`, so shipping it meant the test build ran. libsignal-client's desktop natives (`.dylib`, `.dll`, desktop `.so`) are excluded too.

## Screenshots

Take screenshots to verify UI changes — don't guess what the screen looks like:
```sh
adb shell screencap -p /data/local/tmp/s.png && adb pull /data/local/tmp/s.png /tmp/lp3_screen.png && adb shell rm /data/local/tmp/s.png
```
Then use Read tool on `/tmp/lp3_screen.png` to view. Always clean up screenshots on device after.

## Shared UI components

One chat screen and one chat list serve every platform, over its `ChatBackend`:
- `ui/chat/ChatScreen.kt`: `ChatScreen(platform, jid)` takes `Sessions.backend(platform)` and shows the thread with what `ChatCapabilities` allows. It also wires `MarkReadGate`, `VisibleConversationEffect`, the media viewer, and the contact editor (a DM title opens it with the number `contactPhone` returns on tap). A chat with no conversation yet is titled by `newChatTitle`. There are no per-platform chat or list screens.
- `ui/chat/ChatListScreen.kt`: `ChatListScreen(filter)`. With no filter it's All Chats: every platform's chats by recency, with platform icons. Filtered, it's one platform's chats. The filter in the bottom bar opens the platform picker (`ui/home/PlatformsScreen.kt`: Back and a "Chats" title).
- `ui/shared/ChatScreenContent.kt`: top bar, message list and input, and the pages over the thread.
  - Compose: Inline puts `MessageInputBar` (`components/MessageInput.kt`) under the thread. Full screen puts a `ThreadBottomBar` (attach, voice note, compose) under it, with a full-screen editor overlay (`components/FullScreenCompose.kt`).
  - Keyboard: System uses the installed keyboard. Light uses the SDK's LP3 keyboard (`components/LightKeyboard.kt`, `LightInputField.kt`).
  - Tapping the thread or turning the dial hides the keyboard. There's no keyboard toggle.
  - One draft per conversation, shared by both input styles.
  - Attach (+) opens `AttachSheet`. A long-press opens `MessageActions`. Swipe-to-reply works only where the menu would offer Reply.
- `ui/shared/components/MessageActions.kt`: the long-press pages. `messageActions(msg, support)` decides the rows: Reply, React (a page of `REACTION_EMOJIS`), Retry, Delete (this phone only) and Delete for Everyone (a confirm page, DELETE in the bottom bar). React is never offered on your own messages. Reply, React and Delete for Everyone need a message the others have: not a call row, an unsent message or a deleted one. Back on any page returns to the thread.
- `ui/shared/components/AttachSheet.kt` and `MediaInput.kt` (`rememberMediaInput`): the attachment sheet and what it hands over to.
  - Camera (`TakePicture`, after asking for CAMERA) shows only when an app resolves `ACTION_IMAGE_CAPTURE`, which the manifest's `<queries>` lets Photon see.
  - Photos is Android's photo picker (photos and videos). Files is the document picker for `ATTACHABLE_FILE_TYPES` (images, video, `application/*`, `text/*`). Not audio, which both platforms would send as a voice note.
  - SMS sends no media, so it has no attach (+) and no sheet.
  - Voice notes are recorded from the mic in the inline bar and the full-screen bottom bar (`VoiceNoteIcon`), not from the sheet.
- `ui/shared/components/MediaViewer.kt`: full-screen media. Save is the top bar's right-hand icon (`DOWNLOAD_ARROW`, then `DOWNLOADED_ARROW` "Saved"). There's no bottom bar, and Back is the only way out.
- `ui/signal/SignalIdentityNotice.kt`: Signal's safety-number notice in the chat header. Tapping it opens the review page, a full-screen `Dialog` with no animation or dim, with ACCEPT CHANGE in the bottom bar.
- `ui/shared/components/MessageLayouts.kt`: `MessageRow` lays out DMs and groups (groups pass `senderName`), and `CallRow` is the single centred call line. It also has the burst rule (`startsBurst`: a sender change or a gap over 15 minutes), `formatContent` and `quoteLabel`.
- `ui/shared/ChatListContent.kt` and `ConversationRow.kt`: the list, its loading and empty states, its bottom bar (`settingsButton`, the filter, `newMessageButton`, from `components/BarButtons.kt`) and rows. A row ends with the muted mark (`LightIcons.SPEAKER_MUTED`), the unread count and the time, in that order. `ChatListContent` also reconciles notifications.
- `ui/shared/components/LightMenu.kt` (`MenuScreen`, `MenuRow`) and `PageScrollView.kt`: LightOS menus and the full-width scroll view with the bar `Inside` (see the Style Guide).
- New message:
  - `chat/RecipientSearch.kt` is pure and tested without a screen. `RecipientIndex` merges the LP3 contacts and every platform's chats into people: a contact takes the DMs with any of their numbers, chats with the same unsaved number are one person, and a chat without a number is a person of its own. Groups stay apart. `search` puts a "Send to" row first when the query is a whole phone number, then people, then groups. Names match ignoring case and accents. Three or more digits match a person's numbers, written nationally or internationally. `directChat` opens a person's chat at once when all their chats are on one platform. Otherwise `reachOn` and `sendViaDetail` fill the "Send via" rows from `ChatBackend.reach`.
  - `ui/newmessage/NewMessageScreen.kt` is the LightOS search page: the "To" field in `Heading` with the keyboard up at once (by the Keyboard setting), Enter putting it away and keeping the matches, and a keypad button in the bottom bar for number entry. The Send via page is a menu of WhatsApp, Signal and SMS, each "existing chat", "new chat" or greyed with its reason.
  - `LocalContacts.all()` lists every LP3 contact with their numbers. `PhoneNumbers` (`data/names/`) compares numbers however they're written, reading national ones for the device's country with `PhoneNumberUtils`.
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
- **Foreign keys**: a message row references its conversation, and foreign keys have been on for every connection since June (32e0654). `InsertMessage` and `InsertMessages` create an empty conversation row first (`ensureConversation`), and callers upsert its name and last message after the insert. Until 271a63e every path inserted the message first, so the insert failed: the first message of every new chat (sent, received, or a missed call) was dropped, and a re-pair's history sync stored only empty chat rows. Recovering that history needs a re-pair.
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
- **History sync**: Only happens during initial pairing. Re-pairing needed for full refresh (Reset in settings). It keeps what the Storage settings allow.

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
- **New chats**: contact sync doesn't create DM rows, so someone known only from it (an ACI mapped to their number) has no conversation. `SignalMessageSender` creates the row when it stores an outgoing message to an ACI without one, whether or not the send then goes out, so a first DM from New message is listed (and a failed one can be retried). A group without its master key, or a non-ACI, still starts nothing.
- **No contact discovery**: `SignalBackend.reach` finds only an ACI the contacts table already maps to the number (`getAciForPhone`, from contact sync or past chats). Photon doesn't use Signal's contact discovery service (CDSI), so it can't start a chat with anyone else.
- **PNI**: a conversation keyed by a contact's PNI is merged into their ACI conversation (`SignalServiceIdMerge`). The merge runs at receiver start, after a contacts sync, and when an envelope's PNI resolves.
- **Message ids** are `{authorAci}_{timestampMs}_{rand}`. Reactions, deletes and read syncs match by the `{author}_{timestamp}` prefix.
- **DB migrations**: v4 added per-row read state (`received` = unread, `seen` = read here but not synced, `read`, `deleted`). v5 cleared stored resolved names. v6 added `conversations.muted_until` and the storage-sync tables. There's no `onDowngrade`, so an older build can't open the database.
- **Mutes** (`signal/storage/`): read-only from the primary's Storage Service. Photon never writes storage records.
  - **Key**: `AccountEntropyPool.deriveMasterKey().deriveStorageServiceKey()`. The pool is stored at link time from the provisioning message. Existing installs send one KEYS sync request and store the pool from the primary's `Keys` reply. It's never logged.
  - **Reads**: on connect (inside the 10-minute upkeep throttle) and on `FetchLatest(STORAGE_MANIFEST)`. The manifest is downloaded only when its version changes, and then only new contact and GroupV2 records are read.
  - **Storage**: `muted_until` uses WhatsApp's encoding (0 / -1 / unix seconds), so `isMutedAt` serves both. PNI-only records reach the ACI chat after a contacts sync.
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
  - It's grouped by release (the current one with a short "Start here" list of its riskiest checks), then by area. Each release candidate of the version in progress keeps its own section. Earlier versions' entries sit under Earlier fixes.
  - Each check has an id (A1, W3, …), the steps (**Do**), the expected result (**Expect**), and any useful `adb logcat` lines, DB queries and caveats such as "needs a re-pair".
  - An entry that came from a README issue says what was wrong (**Was**).
- Neither file holds fix narratives. Root-cause stories go in commit messages.

Lifecycle:

1. **New bug or limitation found**: add a one-liner to README Known issues.
2. **Fix implemented**: move the entry to `docs/VERIFY.md` with verification steps and remove it from the README. If the fix is partial, keep a one-line README entry for what's still open and mark the VERIFY entry *(partial)*.
3. **User confirms on device**: delete the VERIFY entry, and its partial README line if it has one. Only do this when the user explicitly confirms. A code change or a passing test is not confirmation. The fix history stays in git.
4. **Verification fails**: move the entry back to README Known issues as an open bug, saying what you saw.

## Notifications

- **Channels** (`PhotonApp`): one per notification level for messages and calls: `photon_messages` (SOUND, IMPORTANCE_HIGH), `photon_messages_vibrate` (VIBRATE, high, no sound) and `photon_messages_silent` (SILENT, IMPORTANCE_LOW). Channel sound and importance are fixed once created, so the level picks the channel at post time. Also `photon_service_v3` (IMPORTANCE_NONE) for the foreground service, hidden by default. The old `photon_service_v2` channel is deleted at startup.
- **Ids**: `NotificationHelper` tags each notification with its conversation JID and gives each kind its own id (message, incoming call, missed call). A chat's notifications replace each other rather than stacking, and can be cancelled after a restart.
- **Tap**: opens the chat. The intent carries `EXTRA_PLATFORM` and `EXTRA_CONVERSATION`, which `ChatDeepLink` turns into `chat/{platform}/{jid}` over the chat list.
- **Visible chat**: `VisibleConversationEffect` marks the chat on screen while it's resumed and clears its notifications. Message and missed-call notifications aren't posted for it, but a ringing call is.
- **Reconcile**: `ChatListContent` calls `NotificationHelper.reconcile` on every list update, which clears message and missed-call notifications for chats whose unread count is 0.
- **Text**: one formatter, `messageNotificationText` (`service/MessageNotificationText.kt`). A DM is titled with the person, and a group with the group, with a body of "Sender: text". The platform label is appended to the title (`· WhatsApp`).
- **Corrections** (`update = true`, e.g. after a delete-for-everyone) use `setOnlyAlertOnce`, so they don't sound again.
- **WhatsApp**: `WhatsAppSession` subscribes losslessly to the bridge events `new_message`, `read_self`, `message_revoked`, `call_incoming`, `call_ended` and `call_missed`. Muted chats don't notify messages. Calls ring regardless of mute.
- **Signal**: `signal/receiver/MessageNotifications` handles messages and corrections, and `CallHandler` handles calls, both through `SignalNotifier`. Muted chats (see Signal › Mutes) don't notify messages or group-call starts. Calls ring regardless of mute.
- **SMS**: not notified. The system SMS app handles notifications.
- Configurable via Settings › Chat › Notifications (Sound / Vibrate / Silent / Off, migrated from the old On/Off). `NotificationHelper` caches the level through a collector `PhotonApp` starts, and chooses the channel on each post. At Off nothing is posted, and a correction cancels its notification instead.

## Logs

Logcat tags: `GoBridge` (the bridge's own output, at debug level), `BridgeClient`, `PhotonService` (the service and the WhatsApp and Signal sessions), `SignalMessageReceiver` (the receiver and every `signal/receiver/` handler), `SignalMessageSender` (the sender, transport and connection), `SignalRepository`, `SignalGroupV2Manager`, `SignalAccountManager`, `SmsRepository` and `LocalContacts`.
