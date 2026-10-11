# On-device verification queue

Fixes that are implemented (with tests where practical) but not yet confirmed on a real Light Phone III. Open bugs and limitations live in [README → Known issues](../README.md#known-issues).

Each check has an id (A1, W3, …) so a result can be reported in one line, for example "W3 fails: no missed-call row".

## Start here (v0.4.0-rc.1)

These cover the riskiest changes in v0.4.0-rc.1. Run them first, in this order:

1. [A1](#a1-upgrade-in-place-over-v034) Upgrade in place over v0.3.4 (minSdk 34, data intact)
2. [A2](#a2-database-migrations-ran) Database migrations ran on both platforms
3. [S1](#s1-signal-works-on-the-production-libsignal-library) Signal works on the production libsignal library
4. [W1](#w1-the-bridge-runs-over-stdinstdout) WhatsApp bridge runs over stdin/stdout
5. [W2](#w2-the-bridge-restarts-and-resets-cleanly) WhatsApp bridge restarts and resets cleanly
6. [W3](#w3-calls) WhatsApp calls
7. [S2](#s2-calls) Signal calls
8. [D1](#d1-lp3-contacts-name-people-everywhere) LP3 contacts name people on every platform
9. [N1](#n1-tapping-a-notification-opens-its-chat) Tapping a notification opens its chat
10. [A3](#a3-every-platforms-chats-open-and-go-back) Every platform's chats open and go back (one chat screen for all)
11. [SMS1](#sms1-sends-show-sent-or-failed-and-failed-ones-retry) SMS sends show sent or failed, and retry
12. [S11](#s11-mutes-follow-the-primary) and [W7](#w7-mutes) Mutes from the primary silence chats on both platforms
13. [N5](#n5-notification-levels) Notification levels: Sound, Vibrate, Silent and Off

## Tools

**Logs.** The Go bridge's output appears under the `GoBridge` tag at debug level:

```sh
adb logcat -v time -s SignalMessageReceiver SignalMessageSender SignalRepository SignalGroupV2Manager GoBridge BridgeClient PhotonService SmsRepository LocalContacts
```

**Databases** (debug build only). WhatsApp is `files/messages.db` and Signal is `databases/signal_messages.db`. Copy the `-wal` file too, or the copy will be missing recent writes:

```sh
db=files/messages.db   # or: db=databases/signal_messages.db
mkdir -p /tmp/photon
adb exec-out run-as app.photon cat "$db" > /tmp/photon/app.db
adb exec-out run-as app.photon sh -c "cat $db-wal 2>/dev/null" > /tmp/photon/app.db-wal
sqlite3 /tmp/photon/app.db
```

**Processes.** `adb shell pidof app.photon` gives Photon's pid. The bridge runs as a separate `libgobridge.so` process: `adb shell ps -A | grep libgobridge`.

**Re-pair.** Go to Settings → Connections → WhatsApp (or Signal) → RESET, then link again. WhatsApp history sync only runs at pairing, so a WhatsApp fix applied when a message is stored doesn't change rows stored before the fix until you re-pair.

"Other phone" means your primary phone or a contact's phone, whichever the step needs.

## v0.4.0-rc.1

### Install & App

#### A1. Upgrade in place over v0.3.4
- **Do:** with v0.3.4 installed and WhatsApp and Signal linked, run `adb install -r` with the rc.1 APK, then open Photon.
- **Expect:** it installs and opens on the chat list with every chat, unread badge and link intact. No re-pair is needed.
- **Caveats:** `INSTALL_FAILED_OLDER_SDK` means the phone is below Android 14 (check `adb shell getprop ro.build.version.sdk`, which needs to be 34 or higher). Going back to v0.3.4 afterwards breaks Signal, because v0.3.4 can't open the upgraded Signal database. It would need a Signal RESET.

#### A2. Database migrations ran
- **Do:** watch logcat during A1's first start. Afterwards, open the Signal database (see Tools).
- **Expect:**
  - `GoBridge` logs `Applied message DB migration 1`, `2` and `3`, once each, on the first start only.
  - Each WhatsApp chat's badge matches its unread messages, and opening the chat clears it.
  - `PRAGMA user_version;` on `signal_messages.db` returns 6. Signal unread badges survived the upgrade, and opening a Signal chat clears it on the primary too.
- **Caveats:** migration 1 also logs `Backfilled mute end time for N chats` when WhatsApp is paired. That line must not appear on later starts.

#### A3. Every platform's chats open and go back
- **Was:** each platform had its own chat screen, chat list and routes. There is now one chat screen and one chat list for all three.
- **Do:**
  1. From All Chats, open a WhatsApp DM, a WhatsApp group, a Signal DM, a Signal group and an SMS chat, pressing Back after each.
  2. Tap the title and pick SMS, WhatsApp, Signal and All Chats in turn, opening a chat from each.
  3. In a WhatsApp chat and a Signal chat: open a photo, swipe to reply, long-press to react, delete a message for you, and delete one of yours for everyone.
  4. Open an SMS chat from an alphanumeric sender (a bank, for example).
- **Expect:**
  1. Every chat opens with its messages, and Back returns to the list you came from.
  2. A platform's list shows only that platform's chats. Only All Chats shows platform icons. Settings (top right) opens, and Back returns to the list.
  3. Each action works. The delete for everyone also goes from the other phone.
  4. SMS chats offer no attach, voice note, reply, reaction or delete. An alphanumeric sender's title isn't tappable.

#### A4. Pairing only when unlinked
- **Do:**
  1. With WhatsApp linked, force-stop Photon, reopen it, and pick WhatsApp from the title picker straight away, while it's still connecting.
  2. After a WhatsApp RESET (A5), pick WhatsApp again.
  3. With Signal unlinked, pick Signal.
- **Expect:**
  1. A cold start always lands on the chat list. Picking a linked WhatsApp shows its chats (or "Connecting" while the list is empty), never the QR screen.
  2. An unlinked WhatsApp opens the WhatsApp pairing screen.
  3. An unlinked Signal opens the Signal pairing screen.
  
  After pairing, you land on that platform's chats.

#### A5. Reset each platform
- **Do:** Settings → Connections → WhatsApp → RESET, then link again. Then do the same for Signal.
- **Expect:**
  - Each RESET opens that platform's pairing screen, and Back from it returns to the chat list.
  - After the WhatsApp RESET, `adb shell ps -A | grep -c libgobridge` prints 1 and the UI stays responsive.
  - The Signal RESET doesn't crash. It used to crash, because it navigated to pairing from a background thread.
  - The Signal re-link is clean: messages flow both ways, and Settings shows CONNECTED.
- **Caveats:** RESET wipes the platform's messages. WhatsApp re-syncs history at pairing, but Signal has no history. Run this check late.

#### A6. One status vocabulary
- **Was:** each platform stored message status in its own words. Incoming WhatsApp messages were `sent`, and Signal and SMS ones were `received`.
- **Do:** on each platform, tap one of your sent messages. Then send one with airplane mode on.
- **Expect:**
  - The tapped message shows `sent`, `delivered` or `read` (SMS shows `sent`).
  - A send in progress shows `sending…`, and a failed one shows `failed · tap to retry`.
  - Incoming messages never show a status.

### Notifications

#### N1. Tapping a notification opens its chat
- **Do:** with Photon in the background, have the other phone send a WhatsApp DM and a Signal group message, then call and hang up before you answer. Tap each notification in turn. Repeat once with Photon force-stopped, once with a different chat open, and once while you're in a filtered list (the SMS list, for example).
- **Expect:** Photon opens straight into the chat the notification is for, over the list you were on. Back returns to that list, not to a second copy of it.

#### N2. No notification for the chat on screen
- **Do:** keep a WhatsApp chat open while that contact messages you. Then do the same in a Signal chat. Then have someone else message you.
- **Expect:** messages in the open chat appear with no sound and no banner. Other chats still notify. A ringing call still notifies, even in its own chat.

#### N3. Read elsewhere clears the notification
- **Do:** get a WhatsApp notification and a Signal notification, then read both chats on the primary phone without touching Photon.
- **Expect:** both notifications and badges clear within a few seconds.

#### N4. Group notifications name the group
- **Do:** receive a group message on WhatsApp and on Signal, then a WhatsApp group call.
- **Expect:**
  - A group message is titled `<Group> · WhatsApp` (or `· Signal`), and its body reads `Alice: …`.
  - A WhatsApp group call is titled `Alice · <Group> · WhatsApp`.
  - A DM notification is titled with the person.

#### N5. Notification levels
- **Do:** after upgrading, open Settings › Chat. Then cycle NOTIFICATIONS through each level and receive a message, and a call, at each one.
- **Expect:**
  - It shows SOUND if notifications were on before, OFF if they were off.
  - SOUND: heads-up, sound and vibration, as before.
  - VIBRATE: heads-up and vibration, no sound.
  - SILENT: shade and badge only, no sound or buzz. A call still shows, silently.
  - OFF: nothing for messages or calls.
  - Android's notification settings for Photon list "Messages", "Messages (vibrate)" and "Messages (silent)", and any changes you'd made to "Messages" are kept.
  - After a Signal delete-for-everyone, the notification is corrected without alerting. At OFF it's removed.

### Display names

#### D1. LP3 contacts name people everywhere
- **Do:** in the LP3 Contacts app, save "Mum" for a number that reaches you on WhatsApp, Signal and SMS. Go back to Photon. Then rename her from Photon's own editor: tap her DM title.
- **Expect:** within a few seconds, both times, the list rows, chat titles, group sender labels, quotes and new notifications show the new name on all three platforms.

#### D2. No raw ids, and one fallback everywhere
- **Was:** unnamed WhatsApp DMs showed `…@s.whatsapp.net` in the list but `+<number>` in the header. Signal showed `aci[:8]` in the header but the full id in the list.
- **Do:** browse All Chats, a few groups and their quotes, and some notifications, including chats with unsaved numbers.
- **Expect:**
  - Unsaved numbers are formatted, for example `+44 7700 900123`, in the list and in the header alike.
  - No `@s.whatsapp.net`, `@lid`, ACI (uuid) or base64 group id appears anywhere.
  - A person with no name or number shows `WhatsApp contact` or `Signal contact`.

#### D3. WhatsApp names follow the phone's address book
- **Do:** on the primary phone, rename a contact who isn't saved on the LP3. Then look at a business chat (a bank or a delivery service).
- **Expect:**
  - The WhatsApp DM title and their name in group labels change within seconds, without reopening the chat.
  - The business chat keeps its verified name.

#### D4. Group member names arrive without reopening
- **Was:** a participant name learned after a group was opened could stay stale until the chat changed. Names now refresh as they're learned.
- **Do:** open a WhatsApp group that has members saved in your address book but known to WhatsApp only by LID. Wait about 60s after connecting.
- **Expect:** their sender labels change to address-book names while the group is open, and logcat shows `Backfill: updated N contact names`.

#### D5. Signal names stay put
- **Do:** open a Signal DM, send and receive a few messages, then go to Settings → Connections → Signal → REFRESH CONNECTION. Separately, take a contact saved only on the primary phone (not on the LP3) whose name there differs from their Signal profile name. Force-stop and reopen Photon, which requests a contact sync.
- **Expect:**
  - The DM title never changes.
  - After logcat shows `Sync message kinds: contacts`, the primary-only contact shows the primary's name.
  - The Signal list has no chats without messages.

#### D6. Quotes and the contact editor
- **Do:** reply to a message in a DM and in a group. Then tap a DM's title on each platform.
- **Expect:**
  - The DM quote reads `↳ text`, and the group quote reads `↳ Name: text` (or `↳ You: text` for your own message).
  - The editor's name field isn't prefilled with a number or a placeholder.
  - Tapping the title of an `@lid`-only WhatsApp chat, or of a Signal contact with no known number, does nothing. A Signal number learned while the chat is open works on the next tap.

### WhatsApp

#### W1. The bridge runs over stdin/stdout
- **Was:** Photon talked to the bridge over a WebSocket on `127.0.0.1:8765`, which any app on the phone could reach.
- **Do:** open Photon and send and receive a WhatsApp message, then run `adb shell netstat -ltn | grep 8765`.
- **Expect:** messages flow both ways. `netstat` prints nothing, because nothing listens on a port. Logcat never shows `(517)` (a SQLite busy error).

#### W2. The bridge restarts and resets cleanly
- **Do:**
  1. Find the bridge's pid (see Tools) and kill it: `adb shell run-as app.photon kill <pid>` (debug build).
  2. Tap Settings → Connections → WhatsApp → REFRESH CONNECTION a few times.
  3. RESET is checked in A5.
- **Expect:**
  1. Within about a second, `PhotonService` logs `WhatsApp bridge not running; restarting in 1000ms`, and WhatsApp reconnects.
  2. `adb shell ps -T -p $(adb shell pidof app.photon) | grep -c gobridge-stdout` prints 1. `ps -A | grep -c libgobridge` also prints 1.

#### W3. Calls
- **Was:** call events carried raw `@lid` JIDs (typing events too, but Photon shows no typing indicator).
- **Do:** from another phone, call the LP3's WhatsApp number:
  1. Let a voice call ring out.
  2. Answer one on the primary.
  3. Decline one on the primary.
  4. Make a video call.
  5. Call from a contact you have no messages with, then open that chat.
- **Expect:**
  1. An `Incoming voice call` notification, then `Missed voice call`. The chat gets one centred `Missed voice call · 14:02` row and unread 1.
  2. The row reads `Voice call`.
  3. The row reads `Declined voice call`.
  4. The rows and notifications say `video`.
  5. Opening the chat clears its badge.
  
  Each call has exactly one row: the `Call log message call_X in …` id in `adb logcat -s GoBridge | grep "Call "` matches the `Call terminate X` id. A call from a contact WhatsApp knows by LID lands in their existing chat, under their name.

#### W4. Read state
- **Do:**
  1. While a chat is unread in Photon, let someone read one of your messages in it. In a group: post from Photon, let another member post, then have a third member read yours.
  2. Read an unread chat on the primary.
  3. On the primary, mark a chat unread, then read.
- **Expect:**
  1. The chat stays unread.
  2. The badge and the notification clear.
  3. Photon follows each change: unread shows a badge of 1, then read clears it.

#### W5. Delete for everyone and edits
- **Do:** have a contact send a message and delete it for everyone before you read it. Then have them send and edit a message.
- **Expect:**
  - The deleted message shows `[ deleted ]`. If it was the only unread message, the badge and the notification clear.
  - The edit replaces the text.
  - Deletes and edits only apply when they come from the sender. In a group, an admin can also delete.

#### W6. Business chats and other message types
- **Do:** receive messages from a business (a bank, a delivery service), and a poll in a group.
- **Expect:** the business chat is listed with its verified name and readable text. The poll shows as `Poll: …` with its options. Anything Photon can't show reads `[ unsupported message ]`.
- **Caveats:** rows stored before rc.1 only gain these types after a re-pair.

#### W7. Mutes
- **Do:**
  1. On the primary, mute a chat for 8 hours.
  2. Mute another chat "always".
  3. Unmute a chat, then force-stop and reopen Photon.
- **Expect:**
  1. The chat is silent until the mute ends, then notifies again.
  2. The "always" chat stays silent.
  3. The unmuted chat stays unmuted across the restart, with no `Backfilled mute end time` line. Each change logs `Mute update: … muted_until=…`.
  4. Muted chats show a speaker-off mark by their time in the chat list and keep their unread badge. The mark goes when the mute ends or is removed (within about 45s for a timed mute). Calls in a muted chat still ring.

#### W8. Retention survives restarts
- **Do:** set Settings → Storage to 200 messages and 30 days. Force-stop and reopen Photon.
- **Expect:** `GoBridge` logs `Retention: {MaxMessages:200 MaxDays:30 …}`, and older history is still there after the 5-minute prune.

### Signal

#### S1. Signal works on the production libsignal library
- **Was:** the APK shipped libsignal's testing native library, and libsignal's loader picked it over the production one.
- **Do:** link Signal if needed. Send and receive DM text, group text, a photo each way and a reaction. Then delete one of your messages for everyone.
- **Expect:**
  - Everything arrives on both sides, and the delete shows as deleted on the other phone.
  - `unzip -l <apk> | grep signal_jni` lists only `lib/arm64-v8a/libsignal_jni.so`.
- **Caveats:** this also covers the rewritten send pipeline.

#### S2. Calls
- **Do:** from another phone, call the LP3's Signal account:
  1. Let one ring out.
  2. Answer one on the primary.
  3. Decline one.
  4. Make a video call.
  5. Turn the LP3's airplane mode on, call, wait more than 2 minutes, then turn airplane mode off.
  6. Ring and have the caller's network drop so no hangup arrives.
  7. In the primary's Calls tab, mark a missed call read, then delete one.
  8. Start a group call in a group.
- **Expect:**
  1. A ringing notification, then a missed one, a `Missed voice call` row and unread 1.
  2. `Voice call`.
  3. `Declined voice call`.
  4. The rows and notifications say `video`.
  5. A missed row and notification with no ringing: `Call offer … is stale; recording as missed without ringing`.
  6. After 2 minutes it turns missed: `Call …: RINGING -> MISSED (RING_EXPIRED)`.
  7. Marking read clears the notification if the chat has nothing else unread. Deleting removes the row.
  8. A `Group call started` row and a notification titled with the group, reading `<Name> started a group call`.

#### S3. Connection and reconnects
- **Do:**
  1. Turn airplane mode on until logcat shows `Reconnecting in 160s`, then turn it off.
  2. Tap REFRESH CONNECTION three times.
  3. Unlink Photon on the primary.
- **Expect:**
  1. `Network validated; reconnecting now` within seconds, without waiting out the 160s.
  2. Each tap logs one `WebSocket opened, reading messages...`, and a new message then arrives exactly once.
  3. Settings shows NOT LINKED, and logcat shows `Signal server rejected our credentials (HTTP 401/403) 2 times`, with no further retries.

#### S4. Notifications and deletes
- **Do:**
  1. Have a contact send one message and delete it for everyone before you read it.
  2. Have them send A, then B, and delete B for everyone.
- **Expect:**
  1. The badge goes to 0 and the notification clears.
  2. The notification now shows A, without sounding or buzzing again.

#### S5. Retry sends exactly once
- **Do:** turn airplane mode on, send a DM, and let it fail. Turn airplane mode off, tap `failed · tap to retry`, then have the recipient react to it.
- **Expect:** the recipient gets exactly one message, and their reaction shows on Photon's message.

#### S6. Reaction removal
- **Do:** have a contact react to a message, then remove the reaction.
- **Expect:** the reaction disappears in Photon.

#### S7. Mark read once per new message
- **Was:** Signal sent a mark-read on every message-list update, not only for new messages.
- **Do:**
  1. Keep a Signal chat open while the other phone sends three messages a few seconds apart.
  2. In a group open in Photon, have member A turn airplane mode on and send a message, have member B send one, then have A turn airplane mode off. A's message lands above B's in the thread, because Signal orders messages by send time.
- **Expect:**
  1. `SignalMessageSender` logs one `Sent read sync for N message(s)` per new message and nothing in between. The primary shows the messages read.
  2. A's late message is read-synced too, and the group's badge clears on the primary.
  
  A read sync that fails while offline goes out once Signal reconnects, if the chat is still open.

#### S8. Retention
- **Do:** set Messages per chat to 25 and open a Signal chat with more than 25 messages.
- **Expect:** within about 5 minutes, logcat shows `Pruned N Signal messages (cap=25/chat, …)`, and the chat keeps its newest 25.

#### S9. PNI conversations merge into the ACI
- **Was:** a contact known by their phone-number identity (PNI) could get a second chat, separate from their account-id (ACI) chat.
- **Do:** after an upgrade, or after a contact messages you for the first time, check logcat and the Signal database.
- **Expect:** any PNI chat merges into the ACI chat, with the log `Merged conversation PNI:… into ACI …`. Once contacts have synced, `SELECT jid FROM conversations WHERE jid LIKE 'PNI:%';` returns nothing.
- **Caveats:** until a mapping is known, Photon keeps the raw id, so no message is lost: `non-ACI service id … has no ACI mapping`.

#### S10. Pre-key refill uses KeysApi only
- **Was:** if `KeysApi` failed, pre-keys were uploaded through a reflection fallback.
- **Do:** watch logcat on connect.
- **Expect:**
  - Logcat shows `Server ACI pre-key counts: ec=…, kyber=… (refill threshold=10)`.
  - When a count drops below 10, it shows `Uploaded fresh keys via KeysApi: …`.
  - `Failed to upload pre-keys` never appears. A failed refill would be retried on the next connect.

#### S11. Mutes follow the primary
- **Do:**
  1. After upgrading, keep the primary online for a minute.
  2. On the primary, mute a Signal DM and a group for 1 hour.
  3. Change the mute to forever, then unmute.
  4. Let a 1-hour mute run out.
  5. Force-stop Photon, change a mute on the primary, then reopen Photon.
- **Expect:**
  1. logcat shows `Requested keys sync from primary`, then `Stored the storage key…`, then a `Storage manifest v…` line.
  2. Within seconds a `fetchLatest(STORAGE_MANIFEST)` arrives and both chats show the muted mark. New messages raise unread but post no notification, and a group call start posts nothing. A 1:1 call in the muted DM still rings and shows as missed.
  3. Each change applies within seconds.
  4. Notifications resume and the mark clears within about 45s.
  5. The change applies on connect.

### SMS

#### SMS1. Sends show sent or failed, and failed ones retry
- **Do:**
  1. Send an SMS, then a long one (over 160 characters).
  2. Turn airplane mode on and send one. Turn airplane mode off and tap `failed · tap to retry`.
  3. Run `adb shell pm revoke app.photon android.permission.SEND_SMS`, reopen Photon and send one. Run `adb shell pm grant app.photon android.permission.SEND_SMS` and tap `failed · tap to retry`.
- **Expect:**
  1. Each shows `sending…`, then a single message that shows `sent` when tapped, with no duplicate. The long one arrives as one message.
  2. The message turns into a failed row. The retry sends it again as a new message, and the recipient gets one copy. The failed row stays in the thread, because Photon isn't the default SMS app (see README Limitations).
  3. The message shows `failed · tap to retry` at once. The retry sends that same message, with no second copy in the thread.
- **Caveats:** delivery reports aren't requested, so an SMS never shows `delivered`.

#### SMS2. Threads and the list stay live
- **Was:** the SMS list didn't use the shared refresh flow, so a provider error could blank or stall it.
- **Do:** open a long SMS conversation. Then have someone text you while the SMS list is open.
- **Expect:**
  - The chat opens quickly, showing its newest 50 messages in both directions, in the same order as the system SMS app.
  - The new text moves its chat to the top within a second or two.
  - `SmsRepository` logs no repeating `DB read error`.

#### SMS3. Mark read once
- **Was:** SMS marked a chat read on every message-list update, and each mark triggered another update.
- **Do:** open an unread SMS chat, wait for a new text to arrive in it, then go back.
- **Expect:** opening the chat clears its badge. The thread doesn't flicker or reload over and over, and the chat isn't unread when you go back.

### UI and settings

#### U1. Settings › Chat
- **Do:** open Settings › Chat. Tap SCROLL DIAL, COMPOSE and KEYBOARD to cycle each, and use the dial at each SCROLL DIAL value.
- **Expect:**
  - The rows read NOTIFICATIONS, THUMBNAILS, SCROLL DIAL, COMPOSE, KEYBOARD.
  - SLOW, MEDIUM and FAST move lists and threads further per turn.
  - OFF leaves the dial to LightOS, so turning it changes brightness.
  - After the upgrade, SCROLL DIAL shows your old Chat scroll speed.

#### U2. Full-screen compose
- **Do:** set COMPOSE to FULL SCREEN. In a WhatsApp chat:
  1. Tap compose with KEYBOARD set to LIGHT, then with it set to SYSTEM.
  2. Send.
  3. Swipe right on a message.
  4. Type, then press Back.
  5. Record a voice note and attach a photo from the bottom bar.
  6. Open an SMS chat.
- **Expect:**
  1. A full-screen editor opens, with the LP3 keyboard and no system keyboard (LIGHT), or with Gboard (SYSTEM).
  2. Send returns to the thread.
  3. Compose opens with the quote.
  4. The draft is kept.
  5. Both send.
  6. The bottom bar shows only compose.

#### U3. Inline compose
- **Do:** set COMPOSE to INLINE and KEYBOARD to LIGHT. Tap the text field, then tap the thread. Show the keyboard again and turn the dial, then press Back. Show it again, long-press a message, swipe one, and send a message.
- **Expect:**
  - The LP3 keyboard docks under the field.
  - Tapping the thread, turning the dial or pressing Back hides it.
  - A long-press or swipe leaves it up, and so does sending.

#### U4. Look and feel
- **Was:** long chat titles overlapped the back button.
- **Do:** browse the lists, a chat, Settings and a chat with a very long name.
- **Expect:**
  - Text is in Akkurat, the LightOS font.
  - Tapping a row or button gives a haptic tick and no ripple.
  - Menus are large left-aligned rows without dividers.
  - Long titles end in `…` clear of the Back icon.

#### U5. Thread styles
- **Do:** open a busy DM and a busy group.
- **Expect:**
  - DM: a timestamp above the first message of each run (a sender change, or a gap of more than 15 minutes). Tapping any other message reveals its time.
  - Group: a `Name  12:36` line above each run of incoming messages, and your messages right-aligned without a name.
  - Calls: one centred line.

## Earlier fixes

Fixes from releases before v0.4.0-rc.1 that still await confirmation.

### WhatsApp

#### Received images not loading *(partial, open in README)*
- **Was:** tapping a received image opened the viewer, but no image appeared.
- **Changed:** the viewer now re-downloads when the cached file has been pruned, instead of drawing a broken fallback. When a download fails, it shows the bridge's error text.
- **Verify:** receive a photo, wait more than 5 minutes (the media TTL), then tap it. It should re-download and display. If it fails, write down the red error text in the viewer and capture `adb logcat -s GoBridge BridgeClient` around the tap. A fresh image (minutes old) that fails is the case still under investigation.
- **Caveats:** very old images can fail for a legitimate reason: WhatsApp's CDN copy expires.

#### Ghost conversations
- **Was:** history sync left message-less rows with blank names in the chat list (about 117 on device). The retention prune that should delete them never ran, because its query used the wrong column.
- **Changed:** the list only shows conversations that have a message. The prune query is fixed, so empty rows and messages over the per-chat cap are deleted every 5 minutes.
- **Verify:** the chat list has no message-less rows with blank or `…@s.whatsapp.net` names. After at least 5 minutes connected:
  - `SELECT COUNT(*) FROM conversations WHERE jid NOT IN (SELECT DISTINCT conversation_jid FROM messages);` returns 0.
  - `SELECT conversation_jid, COUNT(*) n FROM messages GROUP BY 1 ORDER BY n DESC LIMIT 3;` shows no chat above the Messages per chat setting (default 50).

#### Group switches to DM layout after sending
- **Was:** sending into a group set its `is_group` to 0, so the chat jumped from the group layout to the DM layout.
- **Changed:** every write to a conversation sets `is_group` from the JID (`@g.us`).
- **Verify:** in a group, send a text, then a photo. The thread keeps its group style (a sender name above incoming runs), including after you leave and reopen the chat. `SELECT is_group FROM conversations WHERE jid = '<group>@g.us';` stays 1.

#### Stale "last message" date and group/DM misclassification
- **Was:** a group could show an old preview date (6/6, while newer messages had arrived that day). A chat could also be stored as the wrong type (DM vs group).
- **Changed:** every write sets `is_group` from the JID, so a row an older build got wrong is corrected by the chat's next write. There's no connect-time repair any more. The history-sync preview comes from the newest inserted message, and deleting a chat's newest message moves the preview back.
- **Verify:** after connecting, every chat in the list shows the time of its newest message. Both of these return no rows:
  - `SELECT jid FROM conversations c WHERE last_timestamp < (SELECT MAX(timestamp) FROM messages m WHERE m.conversation_jid = c.jid);`
  - `SELECT jid, is_group FROM conversations WHERE (jid LIKE '%@g.us') <> (is_group = 1);`

#### @-mentions show a number instead of a name
- **Was:** mentions showed the raw `@447…` number from the message body.
- **Changed:** when it stores a message, the bridge resolves each mentioned JID (LID to phone, then address-book name or push name) and puts the name in its place.
- **Verify:** in a group, ask someone to @-mention a member who is in your contacts. Photon shows `@Name`.
- **Caveats:** only messages received after the fix are affected. Older messages keep the number until a re-pair. A mentioned LID with a known number but no name becomes the number. One with neither stays as digits (see README Limitations).

#### Push names win over address-book names
- **Was:** DM names came from WhatsApp push names, even for contacts saved in your address book. The push-name handler also wrote rows under malformed JID keys.
- **Changed:** names follow one ranking. An LP3 contact comes first, then the primary's address-book name (`FullName`), then a verified business name, then the push name. The bridge stores each name with its source's rank and never lets a lower rank replace a higher one. A backfill about 60s after connect upgrades existing rows.
- **Verify:** pick a contact who isn't saved on the LP3 and whose WhatsApp profile name differs from their name in your primary phone's address book. Photon's list and chat header show the address-book name. About 60s after connect, logcat shows `Backfill: updated N contact names`.

#### Duplicate conversations from `@lid` JIDs
- **Was:** one person could appear as two chats, one under their phone JID and one under an `@lid` JID.
- **Changed:** every write path (push names, history sync, sends) now resolves LIDs to phone JIDs. A merge about 60s after each connect folds existing `@lid` rows into the matching phone-JID rows.
- **Verify:** the list has no duplicate chats for the same person. `SELECT jid, name FROM conversations WHERE jid LIKE '%@lid';` returns nothing, or only contacts WhatsApp hasn't mapped to a number. If any duplicates existed, logcat shows `Merged N LID conversations into phone-based rows`.

#### Photo send silently vanished
- **Was:** picking a photo to send could do nothing at all when the file couldn't be read. No sending message and no failed message appeared.
- **Changed:** the picker copies the file off the main thread and only sends a non-empty file. If the copy fails, it shows a "Couldn't attach that file" toast.
- **Verify:** tap attach (+) in a WhatsApp chat and send a photo. It appears in the thread and the recipient gets the photo. If you can find a file the picker can't read (a cloud-only item, for example), the toast appears instead of nothing happening.

#### Unread badge stuck after opening a chat
- **Was:** the badge only cleared if the read receipt to WhatsApp went through, so offline and `@lid` chats stayed unread.
- **Changed:** opening a chat always clears the local unread state through the newest message shown. The read receipt is still sent; if it fails, Photon sends it again once WhatsApp reconnects while the chat is open.
- **Verify:** with airplane mode on, open a WhatsApp chat that has an unread badge, then go back. The badge is gone.

#### Group chats stay unread on the primary phone
- **Was:** reading a group in Photon never cleared it on your main WhatsApp. The group read receipt named you as the participant, so the server ignored it.
- **Changed:** `MarkRead` groups the message ids by sender and sends one correctly attributed receipt per sender.
- **Verify:** get unread messages from at least two different people in one group. Open the group in Photon without reading it on the primary. Within a few seconds, the group's unread badge clears on the primary phone.

#### Failed media couldn't be retried
- **Was:** tap-to-retry only worked for text. The bridge rejected retries of failed photos, videos and voice notes.
- **Changed:** retry re-reads the original local file, uploads it again, and re-sends it with the original message id so the recipient's app treats it as the same message.
- **Verify:** turn airplane mode on and send a photo, then a voice note, so that both end up failed. Turn airplane mode off and tap each failed message. Each one sends, and the recipient gets exactly one copy.
- **Caveats:** retry needs the staged source file, and staged files are deleted 24 hours after sending.

#### Reactions and mark-read used unresolved `@lid` JIDs
- **Was:** outgoing reactions, edits and mark-read used the raw JID, while incoming handlers used the resolved phone JID. As a result, group reactions could get detached from their message, edits didn't refresh, and unread badges could stick on `@lid` chats.
- **Changed:** all three now resolve LIDs to phone JIDs the same way the incoming path does. Edits also broadcast `message_updated`.
- **Verify:** in a group, long-press another member's message and react. The reaction shows on Photon and on the other phone. Then open a group that has unread messages: the badge clears in Photon and on the primary.
- **Caveats:** Photon has no edit UI yet, so the edit path can't be tested on the device.

### Signal

#### Send results not fully checked *(partial, open in README)*
- **Was:** sends ignored the library's per-recipient results, so identity failures and other failures could show as sent.
- **Changed:** DM sends check `SendMessageResult.isSuccess`, including identity failures, and mark the message failed when it's false. Group sends log each recipient that failed, and only fail the message when every recipient fails.
- **Verify:** send a DM to a contact whose verified safety number has changed, before accepting the change. The message shows `failed · tap to retry`, not sent. For groups, logcat `Group send had failures (n/m): …` lists who failed and why.
- **Still open:** the sent transcript to your own devices doesn't check per-device results (see README).

#### Group name shows a base64 id
- **Was:** every reconnect and contact sync overwrote group titles with null, so groups showed their base64 id instead.
- **Changed:**
  - Name re-resolution never writes null.
  - Connect upkeep (at most every 10 minutes) re-fetches the title of any group whose name is blank, showing "Signal group" until the fetch succeeds.
  - Titles go through the shared naming policy, which never shows a raw id.
- **Verify:** go to Settings → Connections → Signal → REFRESH CONNECTION. Groups show their real titles in the list and in chat headers, and logcat shows `Resolved group …: <title> (N members)`. Once the fetches succeed, `SELECT jid FROM conversations WHERE is_group = 1 AND (name IS NULL OR name = '');` returns nothing.

#### Group messages from others not received
- **Was:** in groups, Photon could send but never showed messages from other members, for two reasons:
  - Sender-key distribution messages were dropped.
  - The retry receipt for a failed decrypt named Photon's device instead of the sender's, so senders never re-sent their key.

  When decryption did work, every redelivery was stored again: one message appeared 36 times.
- **Changed:**
  - Sender-key distributions are stored.
  - A failed decrypt sends a retry receipt addressed to the sender's device.
  - Photon sends session pings to GroupV2 members so their messages include Photon's device.
  - Incoming messages are deduplicated on author + timestamp.
- **Verify:**
  1. Ask two other members of a group to send messages. Each message appears exactly once on Photon.
  2. If logcat shows `Protocol decrypt failed … ProtocolNoSessionException`, the next lines should be `Sent retry receipt to …` and later `Stored sender-key distribution from …`. That member's next messages should then decrypt.
  3. On the Signal DB, `SELECT substr(id, 1, 50) p, COUNT(*) n FROM messages GROUP BY p HAVING n > 1;` returns no rows. The first 50 characters of an id are the author ACI, `_`, and the timestamp in milliseconds.
- **Caveats:** messages that older builds acknowledged and discarded can't be recovered.

#### Linked-device name garbled
- **Was:** the primary's Linked devices list showed text like "Photog==", because Photon sent the device name as plaintext.
- **Changed:** registration encrypts the name with the identity key (`DeviceNameUtil.encryptDeviceName`).
- **Verify:** after linking, on the primary phone go to Signal → Settings → Linked devices. Photon is listed as "Photon".
- **Caveats:** only new links get the fix. A device linked before the fix needs Settings → Connections → Signal → RESET, then a new link.

#### Incoming media dropped
- **Was:** Photon dropped incoming Signal attachments, so photos never appeared.
- **Changed:**
  - Attachments are stored with their CDN pointer, and the caption becomes the message text.
  - Voice notes download automatically. Photos and videos download when tapped.
  - Downloads are deleted after the same 5-minute TTL as WhatsApp media.
- **Verify:**
  1. From another phone, send a photo with a caption, a video and a voice note. All three appear, and the caption shows as text.
  2. Tap the photo and the video. Each one downloads and plays or displays, and logcat shows `Downloaded attachment for … (NB)`. The voice note plays.
  3. After 5 minutes, logcat shows `Pruned N downloaded Signal media files`. Tap the photo again and it downloads again.
- **Caveats:** re-downloading only works while Signal's attachment pointer is valid (about 45 days).

#### Sent messages missing on the primary
- **Was:** messages sent from Photon never appeared on the primary or on other linked devices. The sent transcript carried no destination id.
- **Changed:** the message sender is built with both binary and string service ids enabled.
- **Verify:** send a DM and a group message from Photon. Both appear in the right threads on the primary phone, and on Signal Desktop if it's linked, and not in a separate "Unknown contact" thread. Logcat shows no `sync transcript failed` warnings.

#### Video send untested *(partial, open in README)*
- **Was:** Signal chats had no + attach button, so media couldn't be sent.
- **Changed:** the attach button calls `SignalRepository.sendMedia`, and attachments upload through a REST upload-form request. Photo send was confirmed on device in v0.3.2-rc.2.
- **Verify:** tap attach (+), pick a short video, and send it in a DM and in a group. It arrives and plays on the other phone.

#### Read state not synced to the primary
- **Was:** messages read in Photon stayed unread on the primary, because Photon never sent `SyncMessage.read`.
- **Changed:** opening a Signal chat sends a read sync for incoming messages that haven't been synced yet. Each message is synced once.
- **Verify:** receive messages that are unread on both devices, then open the chat in Photon. The primary's unread badge for that chat clears, and logcat shows `Sent read sync for N message(s)`.
- **Caveats:** Photon deliberately doesn't send read receipts to the message author (see README Limitations).

#### Reads on other devices not applied
- **Was:** reading a message on the primary left it unread, with its notification, in Photon.
- **Changed:** Photon matches incoming `sync.read` entries to stored messages by author and timestamp, and clears the unread count and notification for the affected chat only. Group messages are found through their stored conversation.
- **Verify:** receive a DM and a group message, then read both on the primary without opening Photon. Photon's badges and notifications for both chats clear, and logcat shows `Sync message kinds: read[N]`.

#### Replying on the primary left Photon unread
- **Was:** after you replied on the primary, Photon could get the sent transcript without the read sync, so the chat stayed unread.
- **Changed:** a valid sent transcript from the primary marks that conversation's incoming messages read and clears its notification, in DMs and groups.
- **Verify:** receive a message so it's unread in Photon, then reply from the primary. Photon shows your reply and the chat is no longer unread. Logcat shows `Sync message kinds: sent`.

#### Receiver socket dropped when sending
- **Was:** the receiver's authenticated WebSocket closed soon after the sender opened its own, and no incoming messages were decrypted after that.
- **Changed:** the sender reuses the receiver's sockets, so the linked device keeps a single authenticated connection. The sender never disconnects sockets it doesn't own.
- **Verify:** start Photon, send a few messages, then have someone message you. Their messages keep arriving. Logcat shows `Attached sender to receiver-owned Signal WebSockets`, and no `Reconnecting in …` line right after the sends.

#### Incoming reactions never displayed
- **Was:** incoming reactions were stored under the `{author}_{timestamp}` prefix but looked up by the full message id, so none showed.
- **Changed:** `getReactions` queries by prefix and maps the results back to full ids.
- **Verify:** from another phone, react to one message they sent and one you sent, in a DM and in a group. Each emoji shows under its message on Photon.

#### "Delete for everyone" ignored on receive
- **Was:** a remote delete looked for the message by its exact id, which never matched the stored id, so nothing was deleted.
- **Changed:** `markDeletedByPrefix` finds the message by prefix and clears its body and media.
- **Verify:** from another phone, send a message, then delete it for everyone. On Photon the message shows as `[ deleted ]`.

### SMS

#### Voice-note button did nothing
- **Was:** SMS chats showed a mic button, but SMS can't send audio, so recordings were thrown away.
- **Changed:** the chat only offers what the platform supports, so SMS chats have no mic and no attach button.
- **Verify:** with the text input empty, an SMS chat shows neither a mic nor an attach (+) icon in INLINE compose, and only compose in FULL SCREEN. WhatsApp and Signal chats still show both.

### App

#### Service notification badged the launcher icon
- **Was:** the always-on service notification showed up as an unread asterisk on launcher icons (inkOS, for example).
- **Changed:** the service channel is now `photon_service_v3` at `IMPORTANCE_NONE`, and the old `photon_service_v2` channel is deleted. This hides the notification from the shade, but the service keeps running.
- **Verify:**
  1. With no unread messages, the launcher shows no badge on Photon, and the shade has no Photon service notification.
  2. WhatsApp and Signal stay connected: have someone message you and check it arrives.
  3. `adb shell dumpsys notification | grep photon_service_v3` shows `mImportance=0`.
- **Caveats:** turning the "Background Service" channel back on in Android settings brings the status line back, which is expected. What still needs checking is whether this Android build shows foreground-service notifications in a minimal form anyway.

#### No way to link from Settings
- **Was:** the WhatsApp and Signal settings pages showed only a status when unlinked, with no way to link.
- **Changed:** both pages show a LINK DEVICE row when unlinked, which opens the pairing screen. Both show the status as NOT LINKED.
- **Verify:** the next time a platform is unlinked (during A5, for example), open Settings → Connections → WhatsApp, then Signal. Each shows STATUS NOT LINKED and a LINK DEVICE row that opens pairing.

#### Voice notes untested *(partial, open in README)*
- **State:** recording and sending are wired up for WhatsApp and Signal, but neither has been tested on device.
- **Verify:**
  1. In a WhatsApp chat, with the text input empty, tap the mic and grant microphone permission. A `● 0:07`-style timer appears between cancel (✕) and send icons. In FULL SCREEN compose, the bottom bar shows the same.
  2. Tap send. The other phone receives a voice note that plays. Tapping ✕ instead discards the recording.
  3. Repeat steps 1–2 in a Signal chat.
  4. Check that incoming voice notes play on both platforms.

## Maintaining this file

- **Fix lands**: its README Known issues line moves here with verification steps, under the release it ships in. A *(partial)* entry also keeps a one-line open entry in the README.
- **User confirms on device**: delete the entry, and any *(partial)* README line it has. Only delete on the user's explicit confirmation. A code change or a passing test doesn't count. The fix history stays in git.
- **Verification fails**: move it back to README Known issues as an open bug, saying what you saw.
- When a release section empties, delete it. Entries still open when the next release is cut move under Earlier fixes.
