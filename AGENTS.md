# Photon

Minimalist multi-protocol messaging app for the Light Phone III. WhatsApp (whatsmeow Go bridge) + Signal (libsignal-service-java) + SMS.

## Device

- **Light Phone III**: Nearly square screen — 1080x1240 @ 480dpi, 3.92" matte AMOLED. Do NOT assume standard tall smartphone proportions.
- Android 13, arm64-v8a only
- Physical: scroll dial, two-stage camera button, home/menu button
- **Scroll dial**: Pixart pat9126ja optical rotary encoder (`/dev/input/event4`). LightOS maps to custom keycodes: `WHEEL_CW` (318, clockwise/down) and `WHEEL_CCW` (317, counter-clockwise/up). Raw Linux events are KEY_T/KEY_R but Generic.kl remaps them. Intercepted in `MainActivity.dispatchKeyEvent` → `ScrollDialState` → `ScrollDialEffect` composable.

## LP3 Style Guide

Photon should feel native to the LP3. Understand these rules, then make deliberate exceptions when needed — don't deviate by accident.

### Font
- **Public Sans** is the LP3 ecosystem font (LightOS, Luma launcher, Echo). Bundled in Echo at `assets/fonts/PublicSans-Regular.ttf`.
- We currently use Compose default (Roboto). Switching to Public Sans is a deliberate future choice.

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
- **Group names**: Fetched via `GetGroupInfo()` on each message. Participant push names stored in participants table.
- **History sync**: Only happens during initial pairing. Re-pairing needed for full refresh (Reset in settings).

## Signal quirks

- **Java Records**: Turasa v143 uses `LinkDeviceRequest` as a Java Record. Android desugaring can't serialize via Jackson. Fixed with `AndroidRecordFix.kt` which patches JsonUtil's ObjectMapper via reflection to handle specific Record classes.
- **Self-signed CA**: Signal uses its own CA. Bundled as `res/raw/signal_ca.pem`, loaded into BKS keystore at runtime via `SignalConfig.init()`.
- **Kyber pre-keys**: Must be stored as `is_last_resort=1` or `markKyberPreKeyUsed()` deletes them.
- **Pre-key upload**: `KeysApi.setPreKeys()` via WebSocket returns 422. Workaround: upload via `PushServiceSocket.makeServiceRequest()` using reflection.
- **Sync message destinations**: Use `destinationServiceIdBinary` field, not the string `destinationServiceId`.
- **AccountAttributes.Capabilities**: All must be `true` for linked device registration.
- **Device name**: Set via `store.DeviceProps.Os` before client creation in Go bridge (WhatsApp) and `AccountAttributes.name` (Signal).

## Speech/voice on LP3

- **Text-to-speech**: `com.lightos.speech` provides TTS engine (standard LP3).
- **Speech-to-text**: LP3 has no built-in STT. No Google Speech Services.
- **Dictation approach**: The app discovers any available `RecognitionService` via `queryIntentServices` + known service fallback list. Requires `<queries>` block in manifest with `<action android:name="android.speech.RecognitionService" />`.
- **Current status**: Needs a working RecognitionService on the device. See `CLAUDE.local.md` for device-specific speech services.

## SMS quirks

- **Alphanumeric senders**: "HSBC UK", "Specsavers" etc are kept as-is. `normalizeAddress` only strips formatting from phone numbers.
- **Contact resolution**: Uses `ContactsContract.PhoneLookup` with caching.
- **No MMS**: Only SMS supported currently.

## Known issues

The `Known issues` section in `README.md` is the source of truth for current bugs and limitations. Keep it accurate:

- **When you discover a new bug or limitation** during development, add it to the Known issues list in README.md.
- **When the user confirms an issue is fixed**, remove it from the Known issues list. Only remove issues when the user explicitly confirms the fix — don't remove them just because code was changed.
- **When a fix is partial** (e.g., mitigated but not fully resolved), update the description to reflect current state.

## Notifications

- Separate channel `photon_messages` (high importance) from service channel `photon_service_v2` (min importance)
- WhatsApp: listens for `new_message` WebSocket events in PhotonService
- Signal: triggered in SignalMessageReceiver after storing incoming message
- Configurable via Settings > Chat > Notifications (ON/OFF)
- SMS: not notified (existing SMS app handles notifications)
