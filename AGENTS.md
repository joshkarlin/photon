# Photon

Minimalist multi-protocol messaging app for the Light Phone III. WhatsApp (whatsmeow Go bridge) + Signal (libsignal-service-java) + SMS.

## Device

- **Light Phone III**: Nearly square screen — 1080x1240 @ 480dpi, 3.92" matte AMOLED. Do NOT assume standard tall smartphone proportions.
- Android 14+ (API ≥34; confirm with `adb shell getprop ro.build.version.sdk`), arm64-v8a only
- Physical: scroll dial, two-stage camera button, home/menu button
- **Scroll dial**: Pixart pat9126ja optical rotary encoder (`/dev/input/event4`). LightOS maps to custom keycodes: `WHEEL_CW` (318, clockwise/down) and `WHEEL_CCW` (317, counter-clockwise/up). Raw Linux events are KEY_T/KEY_R but Generic.kl remaps them. Intercepted in `MainActivity.dispatchKeyEvent` → `ScrollDialState` → `ScrollDialEffect` composable.

## LP3 Style Guide

Photon should feel native to the LP3. Understand these rules, then make deliberate exceptions when needed — don't deviate by accident.

### Font
- LightOS uses **Akkurat**, which ships as a system font on the LP3. The Light SDK's `lightFontFamily()` (`com.thelightphone.sdk.ui`, `LightFont.kt`) loads it from system fonts. If those are missing, it uses a locally bundled copy (licence permitting), and after that the Compose default.
- Photon currently uses the Compose default (Roboto) and is moving to the SDK's font and type scale (`LightText` / `LightTextVariant`) as part of the Light SDK UI restyle. Public Sans is not the LightOS font, so don't switch to it.

### Color Palette (monochrome + semantic)
| Role | Hex | Notes |
|------|-----|-------|
| Background | `#000000` | Pure black, always |
| Surface | `#0D0D0D` | Subtle elevation (input fields) |
| Primary text | `#FFFFFF` | Pure white |
| Divider (major) | `#1A1A1A` | Section separators |
| Divider (minor) | `#111111` | Between list items |
| Secondary text | `#666666` | Labels, timestamps, metadata, back button |
| Faint text | `#444444` | De-emphasized content, platform icons in All Chats |
| Very faint | `#333333` | Version numbers, tertiary |

**Rule: Color is semantic only in settings.** No decorative color, no brand colors. Status colors (connected/destructive) were removed from settings for monochrome aesthetic.

### Typography
| Use | Size | Weight | Spacing | Case |
|-----|------|--------|---------|------|
| Hero title (PHOTON) | 42sp | Bold | 14sp | ALL CAPS |
| Menu/nav items | 18sp | Normal | 1-2sp | ALL CAPS |
| Conversation name | 18sp | Normal | 1sp | Mixed case |
| Body/messages | 13-15sp | Normal | default | Mixed case |
| Header (back + title) | 13sp/18sp | Normal | 3sp | ALL CAPS |
| Sub-labels | 9-10sp | Normal | 2sp | ALL CAPS |

### Layout
- **Horizontal padding**: 20-24dp consistently
- **Row vertical padding**: 22dp — generous touch targets, spacious feel
- **Dividers**: 0.5-1dp, hair-thin lines
- **Back navigation**: `<` character (18sp, #666666) left-aligned
- **Screen title**: Centered (13sp, 3sp spacing, #666666)
- **No transitions** between pages (frame-by-frame, LP3 style)
- **Loading states**: Show nothing (null initial) until data loads — no flash of empty state

### Photon's deliberate exceptions from LightOS
- **Home screen uses icons** (SMS/WhatsApp/Signal) — visual identity for the three platforms
- **Message layouts** (Terminal/Clean/Transcript) — richer than typical LightOS tools
- **All Chats view** — platform icons in conversation list (not standard LP3)

## Architecture

- Kotlin/Compose, single module (`app`)
- **WhatsApp**: Go binary (`libgobridge.so`) as subprocess, localhost WebSocket IPC, shared SQLite (Go writes, Kotlin reads). SQLite uses WAL + busy_timeout=5000.
- **Signal**: In-process Java library (Turasa signal-service-java v143 + libsignal-android 0.92.1 AAR), own SQLite databases for protocol store and messages
- **SMS**: Android ContentProvider (`content://sms`) + ContentObserver for real-time updates
- All three share same data models (`Conversation`, `Message`) and shared UI components (`ChatScreenContent`, `ChatListContent`, `ConversationRow`)
- Service singleton pattern — no DI framework
- Kotlin 2.1.0, AGP 8.5.2, core library desugaring enabled

## Build

```sh
# Go bridge (only if gobridge/ changed)
cd gobridge
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -buildvcs=false -trimpath \
  -ldflags="-s -w" -o ../app/src/main/jniLibs/arm64-v8a/libgobridge.so .

# APK (set JAVA_HOME and ANDROID_HOME — see CLAUDE.local.md for machine-specific paths)
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Use `./gradlew clean assembleDebug` when Go bridge binary changed — Gradle may cache the old native lib. Machine-specific build paths are in `CLAUDE.local.md` (gitignored).

## Screenshots

Take screenshots to verify UI changes — don't guess what the screen looks like:
```sh
adb shell screencap -p /data/local/tmp/s.png && adb pull /data/local/tmp/s.png /tmp/lp3_screen.png && adb shell rm /data/local/tmp/s.png
```
Then use Read tool on `/tmp/lp3_screen.png` to view. Always clean up screenshots on device after.

## Shared UI components

Chat screens and chat lists are shared across all platforms:
- `ui/shared/ChatScreenContent.kt` — header, message list, layout selection, input bar, imePadding
- `ui/shared/ChatListContent.kt` — header, loading/empty/list states
- `ui/shared/ConversationRow.kt` — name, timestamp, unread badge, optional platform icon

Platform-specific screens are thin wrappers (~20-30 lines) that provide data source and callbacks.

## WhatsApp quirks

- **LID JIDs**: WhatsApp uses `@lid` JIDs for many conversations. The message handler resolves LID→phone via `GetPNForLID` for consistent conversation keys.
- **Empty messages**: Sender key distribution messages in groups have no content — filtered out in handler.
- **Conversation naming**: Only use push name from non-self messages (`!isFromMe`). Push name events use `UpdateConversationNameIfEmpty` to avoid overwriting contact names.
- **Group names**: When a group message arrives, the name is fetched via `GetGroupInfo()` and cached per group for an hour (`groupName()` in `handlers.go`). `events.GroupInfo` rename events update the cache and the conversation name immediately. Participant push names are stored in the participants table.
- **History sync**: Only happens during initial pairing. Re-pairing needed for full refresh (Reset in settings).

## Signal quirks

- **Java Records**: Turasa v143 uses `LinkDeviceRequest` as a Java Record. Android desugaring can't serialize via Jackson. Fixed with `AndroidRecordFix.kt` which patches JsonUtil's ObjectMapper via reflection to handle specific Record classes.
- **Self-signed CA**: Signal uses its own CA. Bundled as `res/raw/signal_ca.pem`, loaded into BKS keystore at runtime via `SignalConfig.init()`.
- **Kyber pre-keys**: The last-resort Kyber key, generated at registration and on every refill, must be stored via `storeLastResortKyberPreKey` (`is_last_resort=1`), or `markKyberPreKeyUsed()` deletes it. One-time Kyber pre-keys are stored with `is_last_resort=0` and deleted on use.
- **Pre-key refill**: `uploadPreKeysIfNeeded` (in `SignalMessageReceiver`) runs when the server's ACI EC or Kyber count drops below 10. It rotates the signed pre-key and the last-resort Kyber key, then uploads fresh batches via `KeysApi.setPreKeys()`. Always rotate the signed pre-key: re-uploading the registration-time one was rejected with 422 "Invalid signature", which starved the server of one-time keys. If `KeysApi` fails, the upload falls back to `PushServiceSocket.makeServiceRequest()` via reflection (`serviceRequest()` in `SignalConfig.kt`).
- **Sync message destinations**: Use `destinationServiceIdBinary` field, not the string `destinationServiceId`.
- **AccountAttributes.Capabilities**: All must be `true` for linked device registration.
- **Device name**: Set via `store.DeviceProps.Os` before client creation in Go bridge (WhatsApp) and `AccountAttributes.name` (Signal). The Signal name must be encrypted with `DeviceNameUtil.encryptDeviceName`; plaintext shows up garbled in the primary's linked-devices list.

## Speech/voice on LP3

- **Text-to-speech**: `com.lightos.speech` provides TTS engine (standard LP3).
- **Speech-to-text**: LP3 has no built-in STT. No Google Speech Services.
- **Dictation approach**: The app discovers any available `RecognitionService` via `queryIntentServices` + known service fallback list. Requires `<queries>` block in manifest with `<action android:name="android.speech.RecognitionService" />`.
- **Current status**: Needs a working RecognitionService on the device. See `CLAUDE.local.md` for device-specific speech services.

## SMS quirks

- **Alphanumeric senders**: "HSBC UK", "Specsavers" etc are kept as-is. `normalizeAddress` only strips formatting from phone numbers.
- **Contact resolution**: Uses `ContactsContract.PhoneLookup` with caching.
- **No MMS**: Only SMS supported currently.

## Known issues and verification

Two files track problems. Keep both accurate.

- **`README.md` → Known issues** lists open bugs and by-design limitations only, one line each: a bold title and one sentence giving the symptom or limitation and its current state. Group entries under Bugs (including Inconsistencies) and Limitations.
- **`docs/VERIFY.md`** lists fixes that are implemented but not yet confirmed on the LP3, grouped by platform (WhatsApp, Signal, SMS, App). Each entry gives what was wrong, what changed, exact on-device verification steps (actions, expected result, and the useful `adb logcat` lines or DB queries), and caveats such as "needs a re-pair".
- Neither file holds fix narratives. Root-cause stories go in commit messages.

Lifecycle:

1. **New bug or limitation found**: add a one-liner to README Known issues.
2. **Fix implemented**: move the entry to `docs/VERIFY.md` with verification steps and remove it from the README. If the fix is partial, keep a one-line README entry for what's still open and mark the VERIFY entry *(partial)*.
3. **User confirms on device**: delete the VERIFY entry, and its partial README line if it has one. Only do this when the user explicitly confirms. A code change or a passing test is not confirmation. The fix history stays in git.
4. **Verification fails**: move the entry back to README Known issues as an open bug, saying what you saw.

## Notifications

- Separate channel `photon_messages` (high importance) from service channel `photon_service_v2` (min importance)
- WhatsApp: listens for `new_message` WebSocket events in PhotonService
- Signal: triggered in SignalMessageReceiver after storing incoming message
- Configurable via Settings > Chat > Notifications (ON/OFF)
- SMS: not notified (existing SMS app handles notifications)
