# On-device verification queue

Fixes that are implemented (with tests where practical) but not yet confirmed on a real Light Phone III. Open bugs and limitations live in [README → Known issues](../README.md#known-issues).

## Lifecycle

- **Fix lands**: its README Known issues line moves here with verification steps. A *(partial)* entry also keeps a one-line open entry in the README.
- **User confirms on device**: delete the entry, and any *(partial)* README line it has. Only delete on the user's explicit confirmation. A code change or a passing test doesn't count. The fix history stays in git.
- **Verification fails**: move it back to README Known issues as an open bug, saying what you saw.

## Tools

**Logs.** The Go bridge's output appears under the `GoBridge` tag at debug level:

```sh
adb logcat -v time -s SignalMessageReceiver SignalMessageSender SignalRepository SignalGroupV2Manager GoBridge PhotonService WsClient
```

**Databases** (debug build only). WhatsApp is `files/messages.db` and Signal is `databases/signal_messages.db`. Copy the `-wal` file too, or the copy will be missing recent writes:

```sh
db=files/messages.db   # or: db=databases/signal_messages.db
mkdir -p /tmp/photon
adb exec-out run-as app.photon cat "$db" > /tmp/photon/app.db
adb exec-out run-as app.photon sh -c "cat $db-wal 2>/dev/null" > /tmp/photon/app.db-wal
sqlite3 /tmp/photon/app.db
```

**Re-pair.** Go to Settings → Connections → WhatsApp (or Signal) → RESET, then link again. WhatsApp history sync only runs at pairing, so a WhatsApp fix applied when a message is stored doesn't change rows stored before the fix until you re-pair.

"Other phone" means your primary phone or a contact's phone, whichever the step needs.

## WhatsApp

### Received images not loading *(partial, open in README)*
- **Was:** tapping a received image opened the viewer, but no image appeared.
- **Changed:** the viewer now re-downloads when the cached file has been pruned, instead of drawing a broken fallback. When a download fails, it shows the bridge's error text.
- **Verify:** receive a photo, wait more than 5 minutes (the media TTL), then tap it. It should re-download and display. If it fails, write down the red error text in the viewer and capture `adb logcat -s GoBridge WsClient` around the tap. A fresh image (minutes old) that fails is the case still under investigation.
- **Caveats:** very old images can fail for a legitimate reason: WhatsApp's CDN copy expires.

### Ghost conversations with raw-JID names
- **Was:** history sync left message-less rows with blank names in the chat list (about 117 on device). The retention prune that should delete them never ran, because its query used the wrong column.
- **Changed:** the list only shows conversations that have a message. The prune query is fixed, so empty rows and messages over the per-chat cap are deleted every 5 minutes.
- **Verify:** the chat list has no message-less rows with blank or `…@s.whatsapp.net` names. After at least 5 minutes connected:
  - `SELECT COUNT(*) FROM conversations WHERE jid NOT IN (SELECT DISTINCT conversation_jid FROM messages);` returns 0.
  - `SELECT conversation_jid, COUNT(*) n FROM messages GROUP BY 1 ORDER BY n DESC LIMIT 3;` shows no chat above the Messages per chat setting (default 50).
- **Caveats:** a chat that has a real message but no resolved name still shows its raw JID. That's a separate open issue in the README.

### Group switches to DM layout after sending
- **Was:** sending into a group set its `is_group` to 0, so the chat jumped from the group layout to the DM layout.
- **Changed:** the send, media-send and send-confirm paths take `is_group` from the `@g.us` JID. A preview update that carries no name never changes a conversation's type.
- **Verify:** in a group (Transcript layout by default), send a text, then a photo. The layout stays Transcript, including after you leave and reopen the chat. `SELECT is_group FROM conversations WHERE jid = '<group>@g.us';` stays 1.

### Stale "last message" date and group/DM misclassification
- **Was:** a group could show an old preview date (6/6, while newer messages had arrived that day). A chat could also be stored as the wrong type (DM vs group).
- **Changed:** groups are classified by JID server alone. The history-sync preview now comes from the newest inserted message. A repair on connect fixes existing rows without a re-pair.
- **Verify:** after connecting, every chat in the list shows the time of its newest message. Both of these return no rows:
  - `SELECT jid FROM conversations c WHERE last_timestamp < (SELECT MAX(timestamp) FROM messages m WHERE m.conversation_jid = c.jid);`
  - `SELECT jid, is_group FROM conversations WHERE (jid LIKE '%@g.us') <> (is_group = 1);`

### @-mentions show a number instead of a name
- **Was:** mentions showed the raw `@447…` number from the message body.
- **Changed:** when it stores a message, the bridge resolves each mentioned JID (LID to phone, then address-book name or push name) and puts the name in its place.
- **Verify:** in a group, ask someone to @-mention a member who is in your contacts. Photon shows `@Name`.
- **Caveats:** only messages received after the fix are affected. Older messages keep the number until a re-pair. Mentions that can't be resolved stay as numbers.

### Push names win over address-book names
- **Was:** DM names came from WhatsApp push names, even for contacts saved in your address book. The push-name handler also wrote rows under malformed JID keys.
- **Changed:** the address-book name (`FullName`, synced from the primary) wins everywhere. A backfill on connect upgrades existing push-name rows. Push-name events only fill in empty names.
- **Verify:** pick a contact whose WhatsApp profile name differs from their name in your primary phone's address book. Photon's list and chat header show the address-book name. On connect, logcat shows `Backfill: updated N contact names`.

### Duplicate conversations from `@lid` JIDs
- **Was:** one person could appear as two chats, one under their phone JID and one under an `@lid` JID.
- **Changed:** every write path (push names, history sync, sends) now resolves LIDs to phone JIDs. A migration on connect merges existing `@lid` rows into the matching phone-JID rows.
- **Verify:** the list has no duplicate chats for the same person. `SELECT jid, name FROM conversations WHERE jid LIKE '%@lid';` returns nothing, or only contacts WhatsApp hasn't mapped to a number. If any duplicates existed, the first connect after the upgrade logs `Merged N LID conversations into phone-based rows`.

### Photo send silently vanished
- **Was:** picking a photo to send could do nothing at all when the file couldn't be read. No sending bubble and no failed bubble appeared.
- **Changed:** the picker copies the file off the main thread and only sends a non-empty file. If the copy fails, it shows a "Couldn't attach that file" toast.
- **Verify:** tap + in a WhatsApp chat and send a photo. A bubble appears and the recipient gets the photo. If you can find a file the picker can't read (a cloud-only item, for example), the toast appears instead of nothing happening.

### Unread badge stuck after opening a chat
- **Was:** the badge only cleared if the read receipt to WhatsApp went through, so offline and `@lid` chats stayed unread.
- **Changed:** opening a chat always clears the local badge. The read receipt is still sent, but failures are ignored.
- **Verify:** with airplane mode on, open a WhatsApp chat that has an unread badge, then go back. The badge is gone.

### Group chats stay unread on the primary phone
- **Was:** reading a group in Photon never cleared it on your main WhatsApp. The group read receipt named you as the participant, so the server ignored it.
- **Changed:** `MarkRead` groups the message ids by sender and sends one correctly attributed receipt per sender.
- **Verify:** get unread messages from at least two different people in one group. Open the group in Photon without reading it on the primary. Within a few seconds, the group's unread badge clears on the primary phone.

### Failed media couldn't be retried
- **Was:** tap-to-retry only worked for text. The bridge rejected retries of failed photos, videos and voice notes.
- **Changed:** retry re-reads the original local file, uploads it again, and re-sends it with the original message id so the recipient's app treats it as the same message.
- **Verify:** turn airplane mode on and send a photo, then a voice note, so that both end up failed. Turn airplane mode off and tap each failed bubble. Each one sends, and the recipient gets exactly one copy.
- **Caveats:** retry needs the staged source file, and staged files are deleted 24 hours after sending.

### Reactions and mark-read used unresolved `@lid` JIDs
- **Was:** outgoing reactions, edits and mark-read used the raw JID, while incoming handlers used the resolved phone JID. As a result, group reactions could get detached from their message, edits didn't refresh, and unread badges could stick on `@lid` chats.
- **Changed:** all three now resolve LIDs to phone JIDs the same way the incoming path does. Edits also broadcast `message_updated`.
- **Verify:** in a group, long-press another member's message and react. The reaction shows on Photon and on the other phone. Then open a group that has unread messages: the badge clears in Photon and on the primary.
- **Caveats:** Photon has no edit UI yet, so the edit path can't be tested on the device.

## Signal

### Safety-number changes stopped incoming messages
- **Was:** after a contact's safety number changed (after a reinstall, for example), Photon rejected their messages. Sends could also look successful when they had failed.
- **Changed:**
  - Photon accepts the new identity for decryption and shows a "Safety number changed: \<name\>" banner with a REVIEW button in DMs and groups.
  - If you had verified the old number, sending is blocked until you tap ACCEPT CHANGE or verify the new number on your main device. If you hadn't, the only block is Signal's five-second send guard.
  - Photon applies `sync.verified` updates from the primary device.
  - When a send fails on identity, Photon saves the new key and shows the banner.
- **Verify:**
  1. Ask a contact to reinstall or re-register Signal so their safety number changes, then message you. The message arrives on Photon and the banner appears in that chat.
  2. Reply from Photon. The reply goes out after the guard and the contact receives it.
  3. Repeat step 1 with a contact you marked verified on the primary. Sending is blocked: REVIEW says "Sending is blocked because the previous safety number was verified". It stays blocked until you tap ACCEPT CHANGE, or until you verify the new number on the primary.
  4. Mark a contact verified, then unverified, on the primary. Logcat shows `Sync message kinds: verified` and `Verification sync handled=true`.
- **Caveats:** messages that older builds acknowledged and discarded are gone, so ask the contact to resend them. Verification history that already existed on the primary isn't imported (see README Limitations). Accepting a change in Photon doesn't mark the number verified on your other devices.

### Send results not fully checked *(partial, open in README)*
- **Was:** sends ignored the library's per-recipient results, so identity failures and other failures could show as sent.
- **Changed:** DM sends check `SendMessageResult.isSuccess`, including identity failures, and mark the bubble failed when it's false. Group sends log each recipient that failed, and only fail the bubble when every recipient fails.
- **Verify:** safety-number step 3 covers DMs: a blocked or failed DM shows a failed bubble, not a sent one. For groups, logcat `Group send had failures (n/m): …` lists who failed and why.
- **Still open:** the sent transcript to your own devices doesn't check per-device results (see README).

### Group name shows a base64 id
- **Was:** every reconnect and contact sync overwrote group titles with null, so groups showed their base64 id instead.
- **Changed:**
  - Name re-resolution never writes null.
  - A repair on connect re-fetches the title of any group whose name is blank, showing "Signal group" until the fetch succeeds.
  - The chat header falls back to "Signal group" and never shows the raw id.
- **Verify:** go to Settings → Connections → Signal → REFRESH CONNECTION. Groups show their real titles in the list and in chat headers, and logcat shows `Resolved group …: <title> (N members)`. Once the fetches succeed, `SELECT jid FROM conversations WHERE is_group = 1 AND (name IS NULL OR name = '' OR name = 'Signal group');` returns nothing.

### Group messages from others not received
- **Was:** in groups, Photon could send but never showed messages from other members, for two reasons:
  - Sender-key distribution messages were dropped.
  - The retry receipt for a failed decrypt named Photon's device instead of the sender's, so senders never re-sent their key.

  When decryption did work, every redelivery was stored again: one message appeared 36 times.
- **Changed:**
  - Sender-key distributions are stored.
  - A failed decrypt sends a retry receipt addressed to the sender's device.
  - Photon sends session pings to GroupV2 members so their messages include Photon's device.
  - Incoming messages are deduplicated on author + timestamp, and a cleanup at startup merges duplicates already stored.
- **Verify:**
  1. Ask two other members of a group to send messages. Each message appears exactly once on Photon.
  2. If logcat shows `Protocol decrypt failed … ProtocolNoSessionException`, the next lines should be `Sent retry receipt to …` and later `Stored sender-key distribution from …`. That member's next messages should then decrypt.
  3. On the Signal DB, `SELECT substr(id, 1, 50) p, COUNT(*) n FROM messages GROUP BY p HAVING n > 1;` returns no rows. The first 50 characters of an id are the author ACI, `_`, and the timestamp in milliseconds. If duplicates existed, the first launch after the upgrade logs `Removed N duplicate message rows`.
- **Caveats:** messages that older builds acknowledged and discarded can't be recovered.

### PNI-keyed conversations
- **Was:** Signal sometimes identifies a contact by their phone-number identity (PNI) instead of their account id (ACI). Messages or primary-device transcripts that used the PNI could create a second conversation, separate from the contact's ACI chat.
- **Changed:** the sources of incoming messages and the destinations of transcripts are mapped to the ACI learned from contact sync. If no mapping exists yet, Photon keeps the raw id, so the message isn't lost, and logs it.
- **Verify:** this is hard to trigger on demand. Look for it when a contact messages you for the first time, or when you send from the primary to a contact known only by number. Logcat shows either `normalized non-ACI … to ACI …` (mapped) or `non-ACI service id … has no ACI mapping` (raw id kept). After a contacts sync, `SELECT jid, name FROM conversations WHERE jid LIKE 'PNI:%';` returns nothing.

### Linked-device name garbled
- **Was:** the primary's Linked devices list showed text like "Photog==", because Photon sent the device name as plaintext.
- **Changed:** registration encrypts the name with the identity key (`DeviceNameUtil.encryptDeviceName`).
- **Verify:** after linking, on the primary phone go to Signal → Settings → Linked devices. Photon is listed as "Photon".
- **Caveats:** only new links get the fix. A device linked before the fix needs Settings → Connections → Signal → RESET, then a new link.

### Incoming media dropped
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

### Sent messages missing on the primary
- **Was:** messages sent from Photon never appeared on the primary or on other linked devices. The sent transcript carried no destination id.
- **Changed:** the message sender is built with both binary and string service ids enabled.
- **Verify:** send a DM and a group message from Photon. Both appear in the right threads on the primary phone, and on Signal Desktop if it's linked, and not in a separate "Unknown contact" thread. Logcat shows no `sync transcript failed` warnings.

### Video send untested *(partial, open in README)*
- **Was:** Signal chats had no + attach button, so media couldn't be sent.
- **Changed:** the + button calls `SignalRepository.sendMedia`, and attachments upload through a REST upload-form request. Photo send was confirmed on device in v0.3.2-rc.2.
- **Verify:** tap +, pick a short video, and send it in a DM and in a group. It arrives and plays on the other phone.

### Read state not synced to the primary
- **Was:** messages read in Photon stayed unread on the primary, because Photon never sent `SyncMessage.read`.
- **Changed:** opening a Signal chat sends a read sync for incoming messages that haven't been synced yet. Each message is synced once.
- **Verify:** receive messages that are unread on both devices, then open the chat in Photon. The primary's unread badge for that chat clears, and logcat shows `Sent read sync for N message(s)`.
- **Caveats:** Photon deliberately doesn't send read receipts to the message author (see README Limitations).

### Reads on other devices not applied
- **Was:** reading a message on the primary left it unread, with its notification, in Photon.
- **Changed:** Photon matches incoming `sync.read` entries to stored messages by author and timestamp, and clears the unread count and notification for the affected chat only. Group messages are found through their stored conversation.
- **Verify:** receive a DM and a group message, then read both on the primary without opening Photon. Photon's badges and notifications for both chats clear, and logcat shows `Sync message kinds: read[N]`.

### Replying on the primary left Photon unread
- **Was:** after you replied on the primary, Photon could get the sent transcript without the read sync, so the chat stayed unread.
- **Changed:** a valid sent transcript from the primary marks that conversation's incoming messages read and clears its notification, in DMs and groups.
- **Verify:** receive a message so it's unread in Photon, then reply from the primary. Photon shows your reply and the chat is no longer unread. Logcat shows `Sync message kinds: sent`.

### Receiver socket dropped when sending
- **Was:** the receiver's authenticated WebSocket closed soon after the sender opened its own, and no incoming messages were decrypted after that.
- **Changed:** the sender reuses the receiver's sockets, so the linked device keeps a single authenticated connection. The sender never disconnects sockets it doesn't own.
- **Verify:** start Photon, send a few messages, then have someone message you. Their messages keep arriving. Logcat shows `Attached sender to receiver-owned Signal WebSockets`, and no `Reconnecting in 5s...` right after the sends.

### Incoming reactions never displayed
- **Was:** incoming reactions were stored under the `{author}_{timestamp}` prefix but looked up by the full message id, so none showed.
- **Changed:** `getReactions` queries by prefix and maps the results back to full ids.
- **Verify:** from another phone, react to one message they sent and one you sent, in a DM and in a group. Each emoji shows under its message on Photon.

### "Delete for everyone" ignored on receive
- **Was:** a remote delete looked for the message by its exact id, which never matched the stored id, so nothing was deleted.
- **Changed:** `markDeletedByPrefix` finds the message by prefix and clears its body and media.
- **Verify:** from another phone, send a message, then delete it for everyone. On Photon the message shows as `[ deleted ]`.

## SMS

### Voice-note button did nothing
- **Was:** SMS chats showed a mic button, but SMS can't send audio, so recordings were thrown away.
- **Changed:** the mic is hidden on platforms that can't send audio, the same way the + button is.
- **Verify:** with the text input empty, an SMS chat shows neither a mic nor a + button. WhatsApp and Signal chats still show both.

## App

### Service notification badged the launcher icon
- **Was:** the always-on service notification showed up as an unread asterisk on launcher icons (inkOS, for example).
- **Changed:** the service channel is now `photon_service_v3` at `IMPORTANCE_NONE`, and the old `photon_service_v2` channel is deleted. This hides the notification from the shade, but the service keeps running.
- **Verify:**
  1. With no unread messages, the launcher shows no badge on Photon, and the shade has no Photon service notification.
  2. WhatsApp and Signal stay connected: have someone message you and check it arrives.
  3. `adb shell dumpsys notification | grep photon_service_v3` shows `mImportance=0`.
- **Caveats:** turning the "Background Service" channel back on in Android settings brings the status line back, which is expected. What still needs checking is whether this Android build shows foreground-service notifications in a minimal form anyway.

### No way to link from Settings
- **Was:** the WhatsApp and Signal settings pages showed only a status when unlinked, with no way to link.
- **Changed:** both pages show a LINK DEVICE row when unlinked, which opens the pairing screen. Both show the status as NOT LINKED.
- **Verify:** the next time a platform is unlinked (during a RESET for another check, for example), open Settings → Connections → WhatsApp, then Signal. Each shows STATUS NOT LINKED and a LINK DEVICE row that opens pairing.

### Voice notes untested *(partial, open in README)*
- **State:** recording and sending are wired up for WhatsApp and Signal, but neither has been tested on device.
- **Verify:**
  1. In a WhatsApp chat, with the text input empty, tap the mic and grant microphone permission. A red dot and a timer appear.
  2. Tap SEND. The other phone receives a voice note that plays. Tapping ✕ instead discards the recording.
  3. Repeat steps 1–2 in a Signal chat.
  4. Check that incoming voice notes play on both platforms.
