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
- **Secondary screens**: `LightTopBar` with `backButton()` (`LightIcons.BACK`) on the left and a centred title. Back is the only exit: no tap-anywhere-to-close and no Cancel button. A page's contextual action goes top right (the media viewer's Save). There's no `<` glyph.
- **Confirming**: an action that can't be undone asks on its own page first: `ConfirmPage` (`ui/shared/components/ConfirmPage.kt`), with Back and a title on top, what will happen in the middle, and the action as a bottom-bar text button (DELETE for Delete for Everyone, RESET for Reset). Back cancels. The safety-number review follows the same layout with ACCEPT CHANGE.
- **Menus**: LightOS menus, via `MenuScreen` / `MenuRow` (`ui/shared/components/LightMenu.kt`): left-aligned `Heading` labels in Title Case straight under the top bar, an optional `Detail` value beneath, no dividers. A disabled row is Faint and doesn't respond.
- **Pages over the thread** (the long-press menu, the attachment sheet, the full-screen editor, the media viewer, the safety-number review) are full screen and opaque, stop touches reaching the thread, and return to it on Back. The long-press menu and the attachment sheet put the keyboard away as they open, and the dial leaves the thread alone while they or the editor are open. A page that can't live in the screen's own layout (the safety-number review, opened from the chat header) uses a `Dialog` with its window animation and dim turned off.
- **Long-press** opens a full-screen LightOS action menu (`MessageActions`), never a popup. It lists only the actions that apply (`messageActions`). A message with none has no long-press and no buzz.
- **Attachment sheet** (`AttachSheet`): the thread's title in the top bar, unlabelled icons (Photos, Files) in a row about three quarters of the way down, 220px apart centre to centre, and a down chevron in the bottom bar that closes it.
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
  - `sendMedia(jid, path, mime, replyToId, fileName)` sends a staged file. `fileName` is the name the user's file had (`pickedFileName` reads `OpenableColumns.DISPLAY_NAME`, and `sanitizeFileName` strips path separators, control and bidi characters and line separators, and caps it at 100 characters keeping the extension). A document is sent under it and carries no caption.
  - For New message: `reach(phoneE164)` says whether a number can be messaged on the platform, as `Reach.Reachable(jid, existing)` (the chat to open, and whether it already has messages) or `Reach.Unreachable(reason)` (a reason fit to show, such as "not on WhatsApp"). It never throws. `newChatTitle(jid)` titles a DM that has no conversation yet, by the `DisplayNames` policy, until its first message creates one.
  - Add platform behaviour here, not as `if (platform == …)` in the screens.
- **Mark-read**: `MarkReadGate` (`chat/MarkRead.kt`) marks a chat read only when it holds an incoming message that no successful request has covered yet. A request covers the newest incoming message and every incoming one still `UNREAD`, so a Signal message that sorts below newer ones is marked too. One request runs at a time. A failed request is asked again on the next change or when the backend `reconnects`.
- **Navigation** (`nav/`): one list route and one chat route cover every platform. `chats?platform={platform}` is the root and the only list (All Chats when null), and `chat/{platform}/{jid}` is the chat.
  - `platforms` is the platform picker, opened from the list's filter. `new-message` (`Routes.NEW_MESSAGE`) is New message, opened with `openNewMessage()` from the list's bottom bar. A chat picked there opens in its place (`openChatFromNewMessage`), so Back from the chat returns to the list.
  - `archived` (`Routes.ARCHIVED`) lists every platform's archived chats. The picker's Archived row opens it with `openArchived()`, in the picker's place, so Back returns to the list.
  - `pickedRoute` gates pairing: WhatsApp pairs only when the bridge reports `logged_out`, and Signal when the device isn't linked.
  - A notification's extras become a `ChatDeepLink`, which opens the chat over the root list.
- **`MessageStatus`** (`data/model/`): one enum for every platform. Your own messages are `SENDING → SENT → DELIVERED → READ`, or `FAILED`. Incoming ones are `UNREAD`, `SEEN` (Signal: read here, not yet synced) or `READ`. A deleted message is `DELETED`. Each store keeps its own strings and maps them when it reads a row (`MessageStatus.outgoing`, `smsStatus`, the Signal and WhatsApp cursor mappers), so nothing stored changed.
- **Refresh flows** (`data/repository/RefreshFlows.kt`): `conversationsFlow` and `messagesFlow` serve all three platforms. They re-query on change signals, conflated, plus a 45s fallback tick.
  - A read error keeps the last good value and retries.
  - A suspect empty list is re-checked for a few seconds before it's believed.
  - Queries stop 5s after the last subscriber leaves.
  - WhatsApp (`PhotonDatabase.notExpired`) and Signal (`notExpired` in `SignalMessages.kt`) leave out rows past their `expires_at`, so a disappearing message goes on time even before its sweep deletes it. A chat whose messages have all gone drops off the list.
- **WhatsApp**: Go binary (`libgobridge.so`) run as a child process (`GoBridge`). `BridgeClient` (formerly `WsClient`) talks to it with one JSON envelope per line over the process's stdin/stdout. There's no socket or port.
  - stderr goes to logcat under `GoBridge` (thread `gobridge-log`). The stdout reader thread is `gobridge-stdout`.
  - The bridge exits when its stdin closes. `GoBridge` closes stdin to stop it, and kills it after 1.5s.
  - `WhatsAppSession` restarts a bridge that exits, backing off from 1s to 60s, and stops it for certain before a reset. Two bridges must never share the WhatsApp session.
  - `WhatsAppSession.reset()` first asks the bridge to `logout`, so the phone drops Photon from Linked devices. It's best effort: after 10s, or on an error, it logs `WhatsApp logout before reset failed` and wipes anyway. Then it deletes `messages.db` and `whatsmeow.db` with the bridge stopped. Settings only calls it after Reset is confirmed on a `ConfirmPage`.
  - `send_media` and media retries only accept staged files: `filesDir/send_media_*`, `filesDir/voice_*`, or the bridge's own downloads in `filesDir/media/`. No relative paths or symlinks. Pickers' files are copied to `send_media_*` with an extension from their MIME type. `send_media` also takes `file_name`, the picked file's own name, which a document goes out under (`DocumentMessage.FileName` and `Title`). Other media ignore it.
  - Shared SQLite (`files/messages.db`): Go writes, Kotlin reads. WAL, `busy_timeout=5000` and foreign keys are set per connection, and the DSN adds `_txlock=immediate`. Foreign keys mean a message needs its conversation row first (see WhatsApp quirks).
  - `check_on_whatsapp` (`reach.go`, `BridgeClient.checkOnWhatsApp`) answers New message's reach check, `{phones}` → `results` of `{phone, jid, on_whatsapp, existing}`. A number with a chat here is answered from the database. A chat still keyed by the person's LID is merged into their phone JID's first, so the chat opened is the one sends use. The rest go to WhatsApp in one whatsmeow `IsOnWhatsApp` query, which needs WhatsApp connected; offline the request fails with `WhatsApp disconnected`. Ask about one person at a time, as the official app does. Never check numbers in bulk, which WhatsApp treats as contact scraping.
- **Signal**: In-process Java library (Turasa signal-service-java v143 + libsignal-android 0.92.1 AAR), with its own SQLite databases for the protocol store and messages. Code layout:
  - **Receiver**: `SignalMessageReceiver` is a facade (connection, reconnect, upkeep) over the handlers in `signal/receiver/`: `EnvelopeProcessor`, `DataMessageHandler`, `SyncMessageHandler`, `CallHandler`, `Receipts`, `RemoteEdits`, `Attachments`, `ContactsSync`, `ContactNames`, `GroupMetadata`, `PreKeyRefill`, `SessionPings`, `NetworkMonitor`, `MessageNotifications`, `SignalNotifier` and `Expiry`.
  - **Message DB**: `db/SignalMessageDatabase.kt` holds the schema, migrations and change notifications. Queries are extension functions grouped by concern: `SignalConversations`, `SignalMessages`, `SignalReadState`, `SignalContacts`, `SignalReactions`, `SignalCallRows`, `SignalRetention`, `SignalServiceIdMerge`, `SignalStorageMutes`, `SignalTimers` and `SignalEdits`.
  - **Pure rules**, tested on their own: `IncomingRows.kt` (`plannedRows`: the rows a DataMessage becomes) and `DisappearingMessages.kt` (`ChatTimer` and the timer-version rules).
  - **Sender pipeline**: `SignalMessageSender` is the facade. A `Recipient` (`Dm` / `Group`) and `OutgoingContent` (`Message` / `Reaction` / `RemoteDelete`) go through a `SignalTransport` (all in `SignalOutgoing.kt`). `LibraryTransport` (`SignalLibraryTransport.kt`) does the library sends and uploads. `SenderConnection` owns the library sender and attaches to the receiver's sockets.
- **SMS**: `SmsRepository` reads Android's SMS provider (`content://sms`) through the shared refresh flows, triggered by a `ContentObserver` and LP3 contact changes.
  - The list scan records each conversation's provider threads, and a chat's messages are read by `thread_id`.
  - Photon isn't the default SMS app, so read state lives in its own preferences, and it can't write to the provider.
  - Sends pass one `sentIntent` per part (broadcast `app.photon.sms.SENT`, heard while `SmsSession` runs), and no delivery intent. `PendingSends` shows a send as sending until every part has reported. Android stores the message (sent or failed) just before the last report, so the send is then forgotten and the provider row shows instead. Until then, a sending row is hidden if the provider already holds its match: same text, and newer than any row the chat showed before the send.
  - A send `SmsManager` refuses outright never reaches the provider. It shows as failed and retries as itself. A failed row in the provider is sent again as a new message.
- All three share the same data models (`Conversation`, `Message`, `MessageStatus`).
  - `ContentTypes` (`data/model/`) names the newer content types: `SYSTEM` (a centred line, such as a timer change: never unread, never notified, no actions), `VIEW_ONCE` (`text_body` "photo", "video" or "voice"), `UNAVAILABLE` (a WhatsApp message not decrypted yet) and `LOCATION` (`text_body` the label, `media_url` a `geo:lat,lng` URI). The older types are still string literals.
  - `Conversation.isArchived` (archived on the primary) and `disappearingSeconds` (the chat's timer, 0 = off) come from each platform's conversation row.
  - `formatTimerDuration` (`data/model/TimerDuration.kt`) is the one wording for a timer, in its largest whole unit: "30 seconds", "1 day", "1 week", "90 days", "36 hours". The chat header and Signal's timer lines use it, and the bridge's `durationText` mirrors it.
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
- `ui/chat/ChatListScreen.kt`: `ChatListScreen(filter)`. With no filter it's All Chats: every platform's chats by recency, with platform icons. Filtered, it's one platform's chats. Archived chats are left out of both. The filter in the bottom bar opens the platform picker (`ui/home/PlatformsScreen.kt`: Back, a "Chats" title, the platforms, and an Archived row last).
  - `ArchivedChatsScreen` (same file) is the Archived page: every platform's archived chats, with platform icons, under Back and an "Archived" title ("no archived chats" when empty).
  - Both go through `ListedChats`, framed by a `ChatListFrame` (`ChatListContent.kt`): `Main` is the main list's (no top bar; the bottom bar with Settings, the filter and New message), and `Page` a secondary page's (Back and a title, no bottom bar).
  - Notifications are reconciled against every chat, archived or not (`reconcileWith`), since archived chats still notify.
- `ui/shared/ChatScreenContent.kt`: top bar, message list and input, and the pages over the thread.
  - The top bar's title is `chatTitle` (`ChatTitle.kt`). While the chat has a timer, it's the SDK's `LightTopBarCenter.TwoLineDetail`, with "disappearing · 1 week" under the name in lower case.
  - Compose: Inline puts `MessageInputBar` (`components/MessageInput.kt`) under the thread. Full screen puts a `ThreadBottomBar` (attach, voice note, compose) under it, with a full-screen editor overlay (`components/FullScreenCompose.kt`).
  - Keyboard: System uses the installed keyboard. Light uses the SDK's LP3 keyboard (`components/LightKeyboard.kt`, `LightInputField.kt`).
  - Tapping the thread or turning the dial hides the keyboard. There's no keyboard toggle.
  - One draft per conversation, shared by both input styles.
  - Attach (+) opens `AttachSheet`. A long-press opens `MessageActions`. Swipe-to-reply works only where the menu would offer Reply.
- `ui/shared/components/MessageActions.kt`: the long-press pages. `messageActions(msg, support)` decides the rows: Reply, React (a page of `REACTION_EMOJIS`), Retry, Delete (this phone only) and Delete for Everyone (a `ConfirmPage`, DELETE in the bottom bar). React is never offered on your own messages. Reply, React and Delete for Everyone need a message the others have: not a call row, an unsent message or a deleted one. A system line offers nothing, and a message only the phone has (`isPhoneOnly`: view-once or unavailable) offers only Delete. Back on any page returns to the thread.
- `ui/shared/components/AttachSheet.kt` and `MediaInput.kt` (`rememberMediaInput`): the attachment sheet and what it hands over to.
  - Camera capture is not offered. Do not add a camera app fallback or a new capture UI without an explicit user request; the current attachment sheet has only Photos and Files. The app manifest explicitly removes sdk-ui's inherited CAMERA permission and camera feature; the release workflow verifies the signed APK has no CAMERA permission.
  - Photos is Android's photo picker (photos and videos). Files is the document picker for `ATTACHABLE_FILE_TYPES` (images, video, `application/*`, `text/*`). Not audio, which both platforms would send as a voice note.
  - Picked files go with their own name (`pickedFileName`, `sanitizeFileName`).
  - SMS sends no media, so it has no attach (+) and no sheet.
  - Voice notes are recorded from the mic in the inline bar and the full-screen bottom bar (`VoiceNoteIcon`), not from the sheet.
- `ui/shared/components/MediaViewer.kt`: full-screen media. Save is the top bar's right-hand icon (`DOWNLOAD_ARROW`, then `DOWNLOADED_ARROW` "Saved"). There's no bottom bar, and Back is the only way out.
  - `fileName` is a document's name (its `text_body`, passed by `ChatScreen`). `saveTarget` (`FileTypes.kt`) gives what Save writes: a document keeps its real name (`sanitizeFileName`), with the type's extension added when it has none; other media keep their id-based download name, with the type's extension in place of the platform's.
  - Images go to Pictures/Photon, videos to Movies/Photon and everything else to Download/Photon.
  - `FileTypes.kt` maps Office, OpenDocument, iWork, EPUB and archive types to their extensions (`mimeToExt`, before `MimeTypeMap`), so nothing is saved as `.bin`. `stagedExtension` uses it for staged sends too.
- `ui/signal/SignalIdentityNotice.kt`: Signal's safety-number notice in the chat header. Tapping it opens the review page, a full-screen `Dialog` with no animation or dim, with ACCEPT CHANGE in the bottom bar.
- `ui/shared/components/MessageLayouts.kt`: `MessageRow` lays out DMs and groups (groups pass `senderName`), `CallRow` is the single centred call line, and `SystemRow` the centred, inert `SYSTEM` line. It also has the burst rule (`startsBurst`: a sender change or a gap over 15 minutes; calls and system lines stand alone), `formatContent` and `quoteLabel`.
  - `formatContent` writes `VIEW_ONCE` as "[ view-once photo · open on your phone ]", `UNAVAILABLE` as "[ waiting for this message · open on your phone ]" (both Faint), and `LOCATION` as "Location · <label>". Notifications and quotes use the same words (`notificationPreview`).
  - A location shows its coordinates beneath (`parseGeo`, `formatCoordinates`). A tap opens `locationIntent`, `geo:lat,lng?q=lat,lng(label)`, which Organic Maps answers on the LP3. A location without a usable point taps like text.
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
- **Content-free messages** (sender-key distribution and the like) are dropped. Unknown types are stored as `[ unsupported message ]`. Business templates, interactive messages, lists and polls are rendered as text (`extract.go`). Messages that can't be decrypted are stored as placeholders (below).
- **Sent documents**: a sent document's `text_body` is its file name, as a received one's is, so the thread shows `[ document: <name> ]` and a retry sends it under the same name (`mediaText`, `mediaTextOf`). Other media keep their caption there.
- **Redeliveries**: messages are inserted with `INSERT OR IGNORE`, so a redelivered message isn't counted or notified twice.
- **Foreign keys**: a message row references its conversation, and foreign keys have been on for every connection since June (32e0654). `InsertMessage` and `InsertMessages` create an empty conversation row first (`ensureConversation`), and callers upsert its name and last message after the insert. Until 271a63e every path inserted the message first, so the insert failed: the first message of every new chat (sent, received, or a missed call) was dropped, and a re-pair's history sync stored only empty chat rows. Recovering that history needs a re-pair.
- **`is_group`** is set from the JID on every conversation write, so there's no connect-time repair.
- **Group info**: `groupEntry()` (`groups.go`) fetches a group's info via `GetGroupInfo()`, caches its subject and admins in memory for an hour, and records its disappearing timer on every fetch. It's fetched when needed: on an incoming group message or placeholder, on group calls, and before every send, retry, media send or edit (`refreshGroupTimer`). There's no hourly loop. An `events.GroupInfo` rename updates the stored name and marks the cache fresh, and a bridge restart empties the cache. Participant names are stored in the participants table.
- **Migrations** are versioned with `PRAGMA user_version` (`db.go`): 1 `muted_until`, 2 legacy read state, 3 name ranks, 4 disappearing timers and `expires_at` (rebuilt from `raw_proto`), 5 view-once rows and stripped quotes, 6 locations, 7 `is_archived`/`archived_at` with a `ChatSettings` backfill. Each logs `Applied message DB migration N`. The `settings` table comes from `InitMessageDB`, not a migration.
- **Disappearing timers** (`disappearing.go`): `conversations.disappearing_seconds` (0 = off) and `disappearing_set_at` (unix seconds the setting was made; 0 = unknown). Photon follows the timer and never sets it.
  - Sources: `EPHEMERAL_SETTING` protocol messages (DMs only; one without a setting time counts as set at its sent time), the `ContextInfo.Expiration` and `EphemeralSettingTimestamp` on every message, history's `EphemeralExpiration`, and group info (a fetch, `JoinedGroup`, or a `GroupInfo` change). Status updates, broadcasts and channels never have a timer (`hasTimer`).
  - Precedence (`timerReport.supersedes`): a newer setting time wins. A fetched group info or a join replaces any timer set before the fetch began. A report without a setting time only replaces a stored timer that has no setting time either.
  - A change adds a `SYSTEM` line (`timer_…` id, never sent a receipt; `WithoutCallRows`), unless it falls outside retention, logs `Disappearing timer of <jid> is now Ns`, and fires `conversation_updated`. A line found by a group fetch doesn't move the chat up.
  - Migration 4 gave each stored row its expiry, and each chat the timer its newest stored proto showed (`Backfilled disappearing timers for N chats`). A DM with no such proto stays at 0 until a message carries its timer, and until then Photon's sends there don't disappear (README Limitations).
  - Not handled: `afterReadDuration` ("disappear after read") and `EPHEMERAL_SYNC_RESPONSE`.
- **Sends with a timer**: `outgoingContext` puts `Expiration` on text, media, retries and edits (inside the edited message), with `EphemeralSettingTimestamp` only when it's known, and nothing when the timer is off. Reactions and revokes carry none, as in mautrix. A DM whose rows are still keyed by its LID: `sendTimer` uses the LID row's timer only when it's newer, or when the phone-number row has none.
- **Expiry**: a received message's `expires_at` (unix seconds) is its timestamp plus its own `ContextInfo.Expiration` (history: the payload's expiry); a sent one's is the server timestamp plus the chat's timer. A message that arrives already past it isn't stored. `expireMessages` runs at startup and then every minute (`StartPruning`), logging `Deleting N disappeared messages`; `removeMessages` deletes the rows with their reactions and media, fixes unread counts and previews, and emits `messages_removed`. The 5-minute prune keeps an empty chat row while it has a timer, an archive or a mute in force.
- **Placeholders** (`placeholders.go`): `events.UndecryptableMessage` is stored under its ID as `VIEW_ONCE` (linked devices only ever get a notice for view-once media) or `UNAVAILABLE`, counted unread and announced (`new_message`), logging `Message <id> in <chat> couldn't be decrypted (…); stored as …`. Messages the sender hides on failure, and non-chats, aren't stored.
  - `AutomaticMessageRerequestFromPhone` is on, so the phone is asked to resend. The copy replaces the row in place (`replacePlaceholder`, emitting `message_replaced`); in a group, only the original sender's. The phone's own resend may replace any row it was asked for, but view-once stays a notice. A resend with nothing to show deletes the placeholder (`messages_removed`, reason `placeholder`).
  - View-once media that does arrive (`applyViewOnce`) is stored as `VIEW_ONCE` with no proto, MIME type or media, and never downloaded or thumbnailed. Migration 5 converted stored ones and deleted their files (`Marked N stored messages view-once`).
- **Stripped quotes**: stored protos drop `ContextInfo.QuotedMessage` (`withoutQuote`), because quotes kept view-once media keys and the text of disappeared messages. Migration 5 stripped existing rows.
- **Locations** (`locations.go`): `LOCATION` rows. `text_body` is the place's name, else its address; a live location's is its caption, else "Live location", at its starting point (updates aren't followed). `media_url` is `geo:lat,lng`. Migration 6 relabelled stored rows (`Gave N stored places their name and map link`).
- **Archive** (`archive.go`): `is_archived` and `archived_at` (unix seconds; 0 = untimed). Archived chats still notify unless muted.
  - Sources: `events.Archive` (timed), whatsmeow's `ChatSettings.Archived` (migration 7 and after a full `regular_low` app-state sync; untimed), and history's `Archived` (untimed). A timed state replaces one set no later; an untimed one only fills in a chat nothing timed has set. Changes log `Chat <jid> archived=<bool>` and fire `conversation_updated`.
  - "Keep chats archived" is the `settings` row `keep_chats_archived`, from `UnarchiveChatsSetting` and history's `AutoUnarchiveChats`. It counts as on until WhatsApp says otherwise. With it off, a new incoming message sent after the archive unarchives the chat (`unarchiveForMessage`, `Chat <jid> unarchived by a new message`).
  - A LID merge keeps the newer timer and archive state of the two rows.
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
  - A group call starting posts once at the Calls level (`SignalNotifier.showGroupCallStarted`), not in a muted group. One that arrives more than about 2 minutes late, by the same rule as an offer (`SignalCalls.isStaleOffer`), is recorded without alerting.
  - A hangup without an offer, outgoing calls from the primary, and call-log CLEAR are ignored.
- **Reconnect**: backoff from 5s, doubling to 300s. `NetworkMonitor` resets it when a validated network appears, which needs `ACCESS_NETWORK_STATE` in the manifest. Two credential rejections (401/403) stop the receiver as `logged_out`.
- **New chats**: contact sync doesn't create DM rows, so someone known only from it (an ACI mapped to their number) has no conversation. `SignalMessageSender` creates the row when it stores an outgoing message to an ACI without one, whether or not the send then goes out, so a first DM from New message is listed (and a failed one can be retried). A group without its master key, or a non-ACI, still starts nothing.
- **Documents**: a sent one is uploaded under the picked file's name (`attachmentFileName`, through `SignalServiceAttachment.Builder.withFileName`), with no caption. The row's `text_body` is that name, as on WhatsApp, so the thread shows it and a retry uploads under it again. A received one's `text_body` is its `AttachmentPointer.fileName`, cleaned by `documentName` (no folders, control or bidi characters, at most 200 characters). DB v7 named the ones stored before from `raw_proto`.
- **No contact discovery**: `SignalBackend.reach` finds only an ACI the contacts table already maps to the number (`getAciForPhone`, from contact sync or past chats). Photon doesn't use Signal's contact discovery service (CDSI), so it can't start a chat with anyone else.
- **PNI**: a conversation keyed by a contact's PNI is merged into their ACI conversation (`SignalServiceIdMerge`). The merge runs at receiver start, after a contacts sync, and when an envelope's PNI resolves.
- **Message ids** are `{authorAci}_{timestampMs}_{rand}`. Reactions, deletes and read syncs match by the `{author}_{timestamp}` prefix, resolved through edit aliases (`resolveMessagePrefix`). A message stored as several rows keeps the prefix on each: the first row is the id, the rest `{id}.2`, `{id}.3` (`MessageKeys.partId`).
- **DB migrations**: v4 added per-row read state (`received` = unread, `seen` = read here but not synced, `read`, `deleted`). v5 cleared stored resolved names. v6 added `conversations.muted_until` and the storage-sync tables. There's no `onDowngrade`, so an older build can't open the database.
  - v7 added `conversations.archived`, `expire_timer`, `expire_timer_version` and `timer_revision`; `messages.expires_in` and `expires_at`; the contacts' `expire_timer` and `expire_timer_version`; and `storage_records.archived` and `keep_muted_archived`. It created `message_aliases`, named received documents from their pointers (`Named N received documents from their pointers`), and cleared `storage_records` and `storage_manifest`, so the next connect reads every record again with the key Photon has.
  - v8 makes a DM's timer and version nullable (unknown). It rebuilds the conversations table only for a database made by the unreleased first v7, which stored every chat as off at version 1. After either path, every DM's timer is unknown and every group has `timer_revision = -1`, so it's fetched once.
- **Incoming rows** (`plannedRows`, `IncomingRows.kt`): a DataMessage becomes one row per thing it carries, in thread order. Every attachment gets a row; a document's `text_body` is its name. The caption goes on the first photo or video; with none, the body is a text row of its own after the others. Each shared contact is a `contact` row ("Name · number"), and a poll is a text row ("Poll: question" and "• option" lines; votes and terminations are ignored). A sticker shows only when there's nothing else.
  - Each row counts unread, but the message notifies once, and a read sync sends one entry per message. Reactions show on the first row. Deleting your own message for everyone deletes every row (`deleteMessagesByPrefix`).
  - A long message (body plus a `text/x-signal-plain` attachment) is one row with the truncated body. The full text isn't downloaded.
- **View-once**: a view-once DataMessage is one `VIEW_ONCE` row naming its kind, with its pointer dropped, so it can't be downloaded or saved. A `viewOnceOpen` sync marks it read. View-once media stored before v7 can't be found, since its pointer doesn't record it.
- **Edits** (`SignalEdits.kt`, `RemoteEdits.applyEdit`): an edit, from someone else (`processEditMessage`) or from the user's other devices (`sentEdit`), replaces the text of the row holding it (the text row, or the captioned photo or video) in place. The message keeps its position, read state and disappearing clock, and an unread one's notification is corrected quietly. A document-only message edited to say something gets a text row after its documents. As on Signal-Android, an edit can't change view-once media, a voice note, a shared contact or a deleted message, and must come from the same chat and author.
  - Each applied edit's own `{author}_{timestamp}` goes in `message_aliases`, pointing at the row it changed, so later edits, reactions, deletes, read syncs and quotes that name the edit still find the message.
- **Disappearing timers** (rules in `DisappearingMessages.kt`, storage in `db/SignalTimers.kt`): a chat's `expire_timer` (seconds, 0 = off) and `expire_timer_version` are NULL until Photon learns them. Photon never sends a timer update.
  - Sends carry the chat's timer and version (`timerToSend`), including retries (with the timer as it is now) and sync transcripts. Reactions and deletes carry none, as in Signal-Android. An unknown timer sends `ChatTimer.UNKNOWN`: 0 at version 0, which current Signal Android, Desktop and iOS all ignore as a timer change. So Photon can't change anyone's timer, and its messages there don't disappear until the timer is learned (README Limitations).
  - The version rules follow Signal-Android `main` (`incomingTimerUpdate`, `syncedTimerUpdate`, `contactSyncTimerUpdate`). Incoming 1:1: the same timer is a no-op, a lower version is ignored, otherwise the timer applies, and a message without a version sets the timer and keeps the stored version. A sent transcript at or above the stored version applies and bumps it. Version 0 never changes anything. A contact sync applies only when the timer is unknown or its version is strictly higher; a version without a timer means off, and a timer without a version is ignored.
  - The first value learned (from a message, a transcript or a contact sync) is stored silently. After that, a change adds a `SYSTEM` line, and an explicit `EXPIRATION_TIMER_UPDATE` adds its line even as the first value. A story reply never sets a timer, and a redelivered message changes nothing.
  - Groups take their timer only from group state (`disappearingMessagesTimer`), read at `timer_revision`. A send to a group whose `timer_revision` is behind its `group_revision` fetches it first. If that fails, the last timer read goes; a group never read and not fetchable sends `UNKNOWN`. A 403/404 (`GroupFetch.Unavailable`) marks the group's state as read at its revision (`markGroupStateUnavailable`), so it isn't refetched until a later revision. Connect upkeep also fetches groups whose timer is behind (`Refreshing metadata for N Signal group(s)`). The first read of a group is silent.
  - A PNI merge keeps the timer with the higher version (or the known one) and the archive state if either was archived.
- **Clocks**: `messages.expires_in` is the timer a row was sent with, and `expires_at` the epoch **ms** it goes (WhatsApp's is unix seconds); 0 means its clock hasn't started. Our own messages start on a successful send (`markOutgoingSent`), and sync transcripts at their `expirationStartTimestamp`. Incoming ones start when read: seen in Photon (`markConversationSeen`, before any read sync is tried), by a read sync or `viewOnceOpen`, by a transcript (below), or on arrival after an early read. Unread messages never expire.
- **Transcript read rule**: a sent transcript counts as "read on the primary" only when it stores a row or carries a timer change, and then only for incoming rows at or before its timestamp (`readOnPrimaryUpTo`). Content-less transcripts (a profile key, a group-call join, a poll vote, a pin) read nothing and start no clock.
- **Expiry loop**: `SignalSession.expireOnTime` runs `Expiry.sweep`, which deletes expired rows with their reactions, aliases and media (inside `filesDir` only), fixes unread counts and `last_message_id`, corrects notifications (`afterExpiry`), and logs `Disappearing messages: deleted N (and M media files)`. It then sleeps until the next expiry (`expiryWaitMs`: at least 1s, at most 5 minutes), waking early when a clock starts (`expiryScheduled`) or the screen turns on (`ACTION_SCREEN_ON`, as the wait's clock stops in deep sleep).
- **Mutes and archive** (`signal/storage/`): read-only from the primary's Storage Service. Photon never writes storage records. `db/SignalStorageMutes.kt` and `storage/StorageMuteSync.kt` kept their names.
  - **Key**: `AccountEntropyPool.deriveMasterKey().deriveStorageServiceKey()`. The pool is stored at link time from the provisioning message. Existing installs send one KEYS sync request and store the pool from the primary's `Keys` reply. It's never logged.
  - **Reads**: on connect (inside the 10-minute upkeep throttle) and on `FetchLatest(STORAGE_MANIFEST)`. The manifest is downloaded only when its version changes, and then only new contact, GroupV2 and account records are read (`carriesChatState`). Each read logs `Storage manifest vN: read R chat records, … A archived`.
  - **Storage**: `muted_until` uses WhatsApp's encoding (0 / -1 / unix seconds), so `isMutedAt` serves both. PNI-only records reach the ACI chat after a contacts sync.
  - **Archive**: a record new to Photon sets its chat's `conversations.archived`: `ContactRecord` and `GroupV2Record` `archived`, and the account record's `noteToSelfArchived` for Note to Self. The account record also gives `keepMutedChatsArchived`.
  - A new incoming data message unarchives its chat, unless the chat is muted and "keep muted chats archived" is on (`unarchiveForNewMessage`, in `insertIncomingRows`). Group-call rows, sent transcripts, Photon's own sends and timer-only updates don't unarchive. The chat stays out until the primary's next record for it says otherwise.
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

- **Levels**: Settings › Chat › Messages and Calls, each Sound / Vibrate / Silent / Off. Messages (`messageLevel`) keeps the old Notifications key, `notification_level`, migrated from the older On/Off. Calls (`callLevel`, `call_level`) resolves to the Messages level until it's set, and `setMessageLevel` first stores that starting value for Calls, so Calls never moves with Messages. `NotificationHelper.followLevels(messages, calls, scope)` caches both through a collector `PhotonApp` starts. At Off nothing is posted, and a correction cancels its notification instead.
- **Channels** (`PhotonApp`): channel sound and importance are fixed once created, so each kind has a channel per level, and the level picks it at post time (`channelFor`, `callChannelFor`).
  - Messages: `photon_messages` (SOUND, IMPORTANCE_HIGH), `photon_messages_vibrate` (VIBRATE, high, no sound) and `photon_messages_silent` (SILENT, IMPORTANCE_LOW).
  - Calls (ringing, missed and group calls): `photon_calls`, `photon_calls_vibrate` and `photon_calls_silent`, all IMPORTANCE_HIGH, so even a silent call shows heads-up. Sound is the default notification sound, not a ringtone.
  - `photon_service_v3` (IMPORTANCE_NONE) for the foreground service, hidden by default. The old `photon_service_v2` channel is deleted at startup.
- **Ids**: `NotificationHelper` tags each notification with its conversation JID, with an id per kind: message 1, incoming call 2, missed call 3, group call 4. A chat's notifications replace each other rather than stacking, and can be cancelled after a restart. `cancelForConversation` and `reconcile` clear 1, 3 and 4. A ringing call (2) isn't cleared by a read; it ends with the call.
- **Ringing**: at Sound or Vibrate, `showIncomingCall` sets `FLAG_INSISTENT` on the built notification (`ringsInsistently`; `NotificationCompat` has no setter), which repeats the channel's sound or vibration until it's cancelled: the call ends (`cancelIncomingCall`), the missed call replaces it, or `setTimeoutAfter` passes 60s. At Silent it shows without either.
- **Group calls**: `showGroupCallStarted` (Signal, through `SignalNotifier`) posts "<Name> started a group call" once on the Calls channel, id 4, never insistent. It isn't posted for the visible chat, and the caller skips muted groups and late starts.
- **Tap**: opens the chat. The intent carries `EXTRA_PLATFORM` and `EXTRA_CONVERSATION`, which `ChatDeepLink` turns into `chat/{platform}/{jid}` over the chat list.
- **Visible chat**: `VisibleConversationEffect` marks the chat on screen while it's resumed and clears its notifications. Message, missed-call and group-call notifications aren't posted for it, but a ringing call is.
- **Reconcile**: `ChatListContent` calls `NotificationHelper.reconcile` on every list update, which clears ids 1, 3 and 4 for chats whose unread count is 0. Archived chats are reconciled too, since they still notify.
- **Text**: one formatter, `messageNotificationText` (`service/MessageNotificationText.kt`). A DM is titled with the person, and a group with the group, with a body of "Sender: text". The platform label is appended to the title (`· WhatsApp`). `notificationPreview` names view-once media ("view-once photo"), unavailable messages ("waiting for this message") and locations ("Location · <label>"), whatever their text.
- **Corrections** (`update = true`, e.g. after a delete-for-everyone, an edit, an expiry or a replaced placeholder) use `setOnlyAlertOnce`, so they don't sound again.
- **WhatsApp**: `WhatsAppSession` subscribes losslessly to the bridge events `new_message`, `read_self`, `message_revoked`, `messages_removed`, `message_replaced`, `call_incoming`, `call_ended` and `call_missed`. Muted chats don't notify messages. Calls ring regardless of mute.
  - `new_message` also fires for `unavailable` and `view_once` placeholders, which count and notify.
  - `messages_removed` `{conversation_jid, message_ids, reason: expired|placeholder, was_unread, unread_count, newest_removed, newest_unread?}`: messages deleted outright. `removedNotification` keeps the notification, cancels it (nothing unread, or nothing left to show), or quietly shows `newest_unread` when the message it showed went.
  - `message_replaced` (shaped like `new_message`, plus `newest_unread`): a placeholder became its message. If it's the newest unread and the notification is still up, the notification quietly shows the real message (`replaceShownMessage`); a dismissed one isn't brought back.
  - `conversation_updated` fires on timer and archive changes. It refreshes the lists and notifies nothing.
- **Signal**: `signal/receiver/MessageNotifications` handles messages and corrections (deletes, edits through `afterEdit`, expiry through `afterExpiry`), and `CallHandler` handles calls, both through `SignalNotifier`. A message stored as several rows notifies once. Muted chats (see Signal › Mutes and archive) don't notify messages or group-call starts. Calls ring regardless of mute.
- **SMS**: not notified. The system SMS app handles notifications.

## Logs

Logcat tags: `GoBridge` (the bridge's own output, at debug level), `BridgeClient`, `PhotonService` (the service and the WhatsApp and Signal sessions), `SignalMessageReceiver` (the receiver and every `signal/receiver/` handler), `SignalMessageSender` (the sender, transport and connection), `SignalRepository`, `SignalGroupV2Manager`, `SignalAccountManager`, `SmsRepository` and `LocalContacts`.
