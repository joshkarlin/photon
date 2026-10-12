package app.photon.signal

import android.util.Log
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.chatTimer
import app.photon.signal.db.deleteMessage
import app.photon.signal.db.deleteMessagesByPrefix
import app.photon.signal.db.getConversation
import app.photon.signal.db.getGroupMeta
import app.photon.signal.db.getMessage
import app.photon.signal.db.groupTimerKnown
import app.photon.signal.db.insertMessage
import app.photon.signal.db.markOutgoingSent
import app.photon.signal.db.setExpiresIn
import app.photon.signal.db.updateConversationLastMessage
import app.photon.signal.db.updateGroupTimer
import app.photon.signal.db.updateMessageStatus
import app.photon.signal.db.upsertConversation
import app.photon.signal.db.upsertReaction
import app.photon.signal.receiver.contentTypeForMime
import app.photon.signal.store.PhotonProtocolStore
import java.io.File
import java.util.Optional
import java.util.UUID
import org.signal.core.models.ServiceId
import org.signal.libsignal.protocol.message.DecryptionErrorMessage
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.messages.multidevice.ReadMessage
import org.whispersystems.signalservice.api.messages.multidevice.RequestMessage
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage
import org.whispersystems.signalservice.internal.push.SyncMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.websocket.SignalWebSocket

internal fun signalReactionFor(messageId: String, emoji: String): SignalServiceDataMessage.Reaction? {
    if (emoji.isBlank()) return null
    val (author, timestamp) = MessageKeys.parse(messageId) ?: return null
    val authorAci = ServiceId.ACI.parseOrNull(author) ?: return null
    return SignalServiceDataMessage.Reaction(emoji, false, authorAci, timestamp)
}

/**
 * @param groupManager the receiver's GroupV2 manager while it's connected,
 *   for a first send into a group whose members aren't stored yet
 */
class SignalMessageSender internal constructor(
    private val credentials: SignalCredentials,
    private val protocolStore: PhotonProtocolStore,
    private val messageDb: SignalMessageDatabase,
    // The network side of sends; replaced in tests.
    transport: SignalTransport? = null,
    private val groupManager: () -> SignalGroupV2Manager? = { null },
) {
    companion object {
        private const val TAG = "SignalMessageSender"
    }

    private val config by lazy { SignalConfig.createConfiguration() }
    val identityStore get() = protocolStore.identityStore
    private val connection = SenderConnection(credentials, protocolStore) { config }

    private val transport: SignalTransport = transport ?: LibraryTransport(
        credentials, messageDb, protocolStore.identityStore,
        config = { config },
        sender = { connection.getOrCreateSender() },
        groupManager = groupManager,
    )

    /**
     * The conversation a send goes to: a DM (ACI-shaped jid) or a GroupV2
     * group with a stored master key. Null for anything else; a group's
     * master key can't be synthesized, so sending waits until an incoming
     * group message has seeded it.
     */
    private fun recipientFor(conversationJid: String): Recipient? =
        ServiceId.ACI.parseOrNull(conversationJid)?.let { Recipient.Dm(conversationJid, it) }
            ?: messageDb.getGroupMeta(conversationJid)?.let { Recipient.Group(conversationJid, it) }

    /**
     * The one send path. [content] is built inside it, so an attachment
     * upload failing counts as the send failing. Exceptions aren't rethrown,
     * so callers' coroutines don't have to catch them to keep the UI alive;
     * a failure drops the cached library sender so the next send rebuilds it.
     */
    private fun deliver(
        recipient: Recipient,
        timestamp: Long,
        what: String,
        content: () -> OutgoingContent,
    ): Boolean = try {
        transport.send(recipient, content(), timestamp)
        Log.i(TAG, "Sent $what to ${recipient.conversationJid}")
        true
    } catch (e: Exception) {
        Log.w(TAG, "Sending $what failed", e)
        invalidate()
        false
    }

    /**
     * Send an outgoing row: it ends "sent", with its disappearing clock
     * started, or "failed" for the user to retry.
     */
    private fun deliverRow(messageId: String, recipient: Recipient, timestamp: Long, content: () -> OutgoingContent): Boolean {
        val sent = deliver(recipient, timestamp, messageId, content)
        if (sent) messageDb.markOutgoingSent(messageId) else messageDb.updateMessageStatus(messageId, "failed")
        return sent
    }

    /**
     * Store an outgoing row before the network attempt, so the UI shows it
     * at once; it starts "sending", or "failed" when it can't be sent.
     */
    private fun storeOutgoing(
        conversationJid: String,
        timestamp: Long,
        status: String,
        contentType: String,
        text: String?,
        replyPrefix: String?,
        mediaPath: String? = null,
        mediaMime: String? = null,
        mediaSize: Long? = null,
    ): String {
        // A first DM to someone (a chat started from New message) has no
        // conversation row: contact sync doesn't make one. Without it the
        // chat isn't listed and has no last message.
        if (ServiceId.ACI.parseOrNull(conversationJid) != null && messageDb.getConversation(conversationJid) == null) {
            messageDb.upsertConversation(jid = conversationJid, name = null, isGroup = false)
        }
        // Prefix "{authorAci}_{timestampMs}" must stay stable so quote lookups
        // via findMessageByPrefix still match. The random suffix only exists
        // to avoid local PK collisions on rapid-fire sends with the same ms.
        val messageId = "${credentials.aciString}_${timestamp}_${UUID.randomUUID().toString().take(8)}"
        messageDb.insertMessage(
            id = messageId, conversationJid = conversationJid,
            senderJid = credentials.aciString ?: "",
            timestamp = timestamp / 1000, contentType = contentType,
            textBody = text, mediaUrl = mediaPath, mediaMime = mediaMime, mediaSize = mediaSize,
            replyToId = replyPrefix,
            isFromMe = true, status = status,
        )
        messageDb.updateConversationLastMessage(conversationJid, messageId, timestamp / 1000)
        return messageId
    }

    /** Send a text message; returns whether it went out (otherwise its row is "failed"). */
    fun sendTextMessage(conversationJid: String, text: String, replyToId: String? = null): Boolean {
        val timestamp = System.currentTimeMillis()
        val replyPrefix = replyPrefixFor(replyToId)
        val recipient = recipientFor(conversationJid)
        val messageId = storeOutgoing(
            conversationJid, timestamp, if (recipient != null) "sending" else "failed", "text", text, replyPrefix,
        )
        if (recipient == null) {
            Log.w(TAG, "Refusing send: $conversationJid is neither an ACI nor a known group")
            return false
        }
        return deliverRow(messageId, recipient, timestamp) {
            OutgoingContent.Message(text, replyPrefix, timer = sendTimer(messageId, recipient))
        }
    }

    /**
     * The disappearing-messages timer [messageId] goes to [recipient] with:
     * the chat's, as stored now (a retry too: an older timer at the current
     * version could change the chat's timer). The row disappears after it
     * once sent. A group whose timer hasn't been read since the v7 upgrade
     * (its state is fetched after connecting) is read first, so a message
     * sent meanwhile still disappears for everyone.
     */
    private fun sendTimer(messageId: String, recipient: Recipient): ChatTimer {
        if (recipient is Recipient.Group && !messageDb.groupTimerKnown(recipient.conversationJid)) {
            groupManager()?.fetchGroup(recipient.meta.masterKey)?.let { group ->
                messageDb.updateGroupTimer(
                    recipient.conversationJid, group.disappearingMessagesTimer?.duration ?: 0, group.revision,
                )
            }
        }
        val timer = messageDb.chatTimer(recipient.conversationJid)
        messageDb.setExpiresIn(messageId, timer.seconds)
        return timer
    }

    /**
     * Send a media message (voice note, image, etc). The row carries
     * media_url/media_mime so the bubble renders while the file is uploaded
     * to Signal's CDN; the pointer then rides a normal DataMessage. Audio
     * sends are flagged as voice notes — that's the only audio Photon records.
     * Returns whether it went out (otherwise its row is "failed").
     *
     * A document is uploaded under [fileName], the name the user's file had,
     * and carries no caption. Its row keeps that name as its body, as a
     * WhatsApp document's does, so the thread shows it and a retry reuses it.
     */
    fun sendMediaMessage(
        conversationJid: String,
        filePath: String,
        mimeType: String,
        caption: String?,
        replyToId: String? = null,
        fileName: String? = null,
    ): Boolean {
        val timestamp = System.currentTimeMillis()
        val replyPrefix = replyPrefixFor(replyToId)
        val contentType = contentTypeForMime(mimeType)
        val isDocument = contentType == "document"
        val text = caption.takeUnless { isDocument }
        val file = File(filePath)
        val recipient = recipientFor(conversationJid)?.takeIf { file.exists() }
        val messageId = storeOutgoing(
            conversationJid, timestamp, if (recipient != null) "sending" else "failed", contentType,
            (if (isDocument) fileName else text)?.takeIf { it.isNotBlank() }, replyPrefix,
            mediaPath = filePath, mediaMime = mimeType, mediaSize = file.length().takeIf { it > 0 },
        )
        if (recipient == null) {
            Log.w(TAG, "Refusing media send to $conversationJid (fileExists=${file.exists()})")
            return false
        }
        return deliverRow(messageId, recipient, timestamp) {
            val pointer = transport.upload(
                file, mimeType, voiceNote = contentType == "audio",
                fileName = attachmentFileName(contentType, fileName, file),
            )
            OutgoingContent.Message(text, replyPrefix, listOf(pointer), sendTimer(messageId, recipient))
        }
    }

    /**
     * Quote key for a reply. Call rows aren't messages other devices know
     * about (their ids aren't "{author}_{ts}" keys), so a reply to one is
     * sent as a plain message.
     */
    private fun replyPrefixFor(replyToId: String?): String? {
        if (replyToId == null) return null
        if (messageDb.getMessage(replyToId)?.contentType == SignalMessageDatabase.CONTENT_CALL) return null
        return MessageKeys.prefixOf(replyToId)
    }

    fun sendReaction(conversationJid: String, messageId: String, emoji: String): Boolean {
        val row = messageDb.getMessage(messageId)
        val reaction = signalReactionFor(messageId, emoji)
        val myAci = credentials.aciString
        if (row == null || row.conversationJid != conversationJid ||
            row.contentType == SignalMessageDatabase.CONTENT_CALL ||
            row.status.isUnsent || reaction == null || myAci == null
        ) return false
        val recipient = recipientFor(conversationJid) ?: return false

        val timestamp = System.currentTimeMillis()
        if (!deliver(recipient, timestamp, "reaction to $messageId") { OutgoingContent.Reaction(reaction) }) return false
        messageDb.upsertReaction(MessageKeys.prefixOf(messageId), myAci, emoji, timestamp)
        return true
    }

    /**
     * Retry a previously failed message (text or media). Reuses the local
     * row's conversation_jid + content + reply_to_id and flips the status
     * back to "sending" while a fresh send is attempted. The local row keeps
     * its original ID so the chat list doesn't grow new bubbles per retry.
     * Media rows re-upload from the original local file; a document's body
     * is its file name, which it's uploaded under again.
     */
    fun retryMessage(messageId: String): Boolean {
        val row = messageDb.getMessage(messageId)
        if (row == null || !row.isFromMe) {
            Log.w(TAG, "retryMessage: row $messageId missing or not ours")
            return false
        }
        val text = row.textBody
        val mediaFile = row.mediaUrl?.let { File(it) }
        val isText = row.contentType == "text"
        if (isText && text.isNullOrBlank()) {
            Log.w(TAG, "retryMessage: row $messageId has empty body")
            return false
        }
        if (!isText && (mediaFile == null || !mediaFile.exists())) {
            Log.w(TAG, "retryMessage: media file for $messageId is gone (${row.mediaUrl})")
            return false
        }
        val recipient = recipientFor(row.conversationJid) ?: run {
            Log.w(TAG, "Retry refused: ${row.conversationJid} is not an ACI nor a known group")
            messageDb.updateMessageStatus(messageId, "failed")
            return false
        }

        messageDb.updateMessageStatus(messageId, "sending")
        // Keep the same wire timestamp by reusing the original — a retry
        // that actually went through the first time would otherwise bypass
        // recipient-side dedupe and produce a duplicate. The exact ms value
        // lives in the id; the timestamp column only has seconds.
        val timestamp = MessageKeys.sentTimestampMs(messageId, row.timestamp)
        val isDocument = row.contentType == "document"
        return deliverRow(messageId, recipient, timestamp) {
            val attachments = if (isText) emptyList() else listOf(
                transport.upload(
                    mediaFile!!, row.mediaMime ?: defaultMimeFor(row.contentType), voiceNote = row.contentType == "audio",
                    fileName = attachmentFileName(row.contentType, text.takeIf { isDocument }, mediaFile),
                ),
            )
            OutgoingContent.Message(text.takeUnless { isDocument }, row.replyToId, attachments, sendTimer(messageId, recipient))
        }
    }

    /**
     * Delete a message. Local-only (forEveryone=false) just drops the row.
     * "Delete for everyone" sends a RemoteDelete targeting the original
     * message's sent timestamp — parsed from the local id, which encodes
     * "{authorAci}_{timestampMs}_{rand}" — on a fresh-timestamped DataMessage
     * to the recipient/group, plus a sent transcript so our other linked
     * devices remove it too. Returns false (leaving the local row intact) if
     * the network delete couldn't be sent, so the user can retry.
     */
    fun deleteMessage(conversationJid: String, messageId: String, forEveryone: Boolean): Boolean {
        // Call rows exist only in Photon: there's nothing to delete remotely,
        // and their ids don't carry a sent timestamp to target.
        val isCallRow = messageDb.getMessage(messageId)?.contentType == SignalMessageDatabase.CONTENT_CALL
        if (forEveryone && !isCallRow) {
            val targetTs = MessageKeys.parse(messageId)?.second
                ?: messageDb.getMessage(messageId)?.let { it.timestamp * 1000 }
                ?: run {
                    Log.w(TAG, "deleteMessage: can't derive target timestamp for $messageId")
                    return false
                }
            val recipient = recipientFor(conversationJid) ?: run {
                Log.w(TAG, "deleteMessage: $conversationJid is neither ACI nor known group")
                return false
            }
            val sent = deliver(recipient, System.currentTimeMillis(), "remote delete of $messageId") {
                OutgoingContent.RemoteDelete(targetTs)
            }
            if (!sent) return false
            // The other devices delete the whole message: every attachment
            // row stored for it goes here too.
            messageDb.deleteMessagesByPrefix(MessageKeys.prefixOf(messageId))
            return true
        }
        messageDb.deleteMessage(messageId)
        return true
    }

    /**
     * Send an empty NullMessage to a contact. NullMessage is a Signal protocol
     * primitive used purely for session housekeeping — it carries no body, no
     * attachment, no metadata, and the recipient's app processes it silently
     * (no notification, no chat entry, nothing user-visible). The valuable
     * side effect: the server enforces device-list consistency on the send,
     * so if the contact's app doesn't yet have our deviceId in their session
     * cache for this account, they'll be forced to fetch our pre-key bundle
     * and create a session for us. Subsequent messages from them then
     * encrypt for our deviceId too, so we start receiving their messages.
     */
    fun sendSessionPing(aci: String): Boolean {
        return try {
            val parsedAci = ServiceId.ACI.parseOrNull(aci)
                ?: throw IllegalArgumentException("Invalid ACI: $aci")
            val address = SignalServiceAddress(parsedAci)
            val sender = connection.getOrCreateSender()
            val result = sender.sendNullMessage(address, null)
            result.identityFailure?.let { identityStore.recordSendIdentityFailure(aci, it.identityKey) }
            if (!result.isSuccess) return false
            Log.i(TAG, "Sent session ping to $aci")
            true
        } catch (e: Exception) {
            Log.w(TAG, "sendSessionPing failed for $aci: ${e.javaClass.simpleName}: ${e.message}")
            if (e is org.whispersystems.signalservice.api.crypto.UntrustedIdentityException) {
                identityStore.recordSendIdentityFailure(aci, e.identityKey)
            }
            invalidate()
            false
        }
    }

    /**
     * Ask the sender to retry a message we could not decrypt. This is
     * especially important for group sender-key messages: if Photon missed
     * or previously discarded the sender-key distribution, the next group
     * ciphertext will fail until the sender is told which local device
     * needs a resend.
     */
    fun sendRetryReceiptForDecryptionFailure(
        senderServiceId: String,
        senderDeviceId: Int,
        groupId: ByteArray?,
        originalContent: ByteArray,
        originalType: Int,
        originalTimestamp: Long,
    ): Boolean {
        return try {
            val serviceId = ServiceId.parseOrNull(senderServiceId)
                ?: throw IllegalArgumentException("Invalid sender service id: $senderServiceId")
            // The device id here is originalSenderDeviceId — the remote member's
            // device that sent the message we failed to decrypt, NOT our own
            // device. The sender keys its Message Send Log lookup on (its own
            // sending device id + timestamp); passing our linked-device id makes
            // that lookup miss, so it never re-distributes the sender key and the
            // retry silently no-ops.
            val error = DecryptionErrorMessage.forOriginalMessage(
                originalContent,
                originalType,
                originalTimestamp,
                senderDeviceId,
            )
            connection.getOrCreateSender().sendRetryReceipt(
                SignalServiceAddress(serviceId),
                null,
                Optional.ofNullable(groupId),
                error,
            )
            Log.i(
                TAG,
                "Sent retry receipt to ${senderServiceId.take(8)}… " +
                    "senderDevice=$senderDeviceId localDevice=${credentials.deviceId} " +
                    "type=$originalType group=${groupId != null}",
            )
            true
        } catch (e: Exception) {
            Log.w(
                TAG,
                "sendRetryReceipt failed for ${senderServiceId.take(8)}…: " +
                    "${e.javaClass.simpleName}: ${e.message}",
            )
            invalidate()
            false
        }
    }

    /**
     * Tell our other devices (primary included) that we've read messages, so
     * their unread badges clear. Android primaries clear a thread when they
     * receive our sent transcript, but iOS primaries don't — and neither
     * clears on a read-without-reply — so this sync is what keeps read state
     * consistent across devices, mirroring what official clients send.
     *
     * Each entry is (authorAci, sentTimestampMs) of an incoming message —
     * the pair other devices use to locate it (see [planReadSync]). Returns
     * true only when a non-empty payload went out, so the caller marks
     * exactly those rows synced; anything else stays pending for a retry.
     *
     * Note: this is the self-sync only. Official clients also send a READ
     * receipt to the message author, but that's gated on the user's
     * read-receipts privacy setting, which Photon doesn't know — so we
     * deliberately don't.
     */
    fun sendReadSync(reads: List<Pair<ServiceId.ACI, Long>>): Boolean {
        if (reads.isEmpty()) return false
        val readMessages = reads.map { (aci, timestampMs) -> ReadMessage(aci, timestampMs) }
        return try {
            val result = connection.getOrCreateSender().sendSyncMessage(SignalServiceSyncMessage.forRead(readMessages))
            if (!result.isSuccess) {
                Log.w(TAG, "Read sync not delivered (${readMessages.size} message(s))")
                return false
            }
            Log.i(TAG, "Sent read sync for ${readMessages.size} message(s)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Read sync failed (${readMessages.size} message(s)): ${e.message}")
            invalidate()
            false
        }
    }

    /**
     * Ask our primary device to push us its contact book via sync.contacts.
     * The primary responds with a SyncMessage(contacts=AttachmentPointer)
     * which the receiver picks up and ingests. Linked devices use this to
     * bootstrap names + phone numbers since they don't have direct access
     * to the user's address book.
     */
    fun requestContactsSync() {
        requestSync(SyncMessage.Request.Type.CONTACTS, "contacts")
    }

    /**
     * Ask the primary for the account keys: it answers with a Keys sync
     * carrying the account entropy pool, from which the storage key comes.
     * Returns whether the request went out.
     */
    fun requestKeysSync(): Boolean = requestSync(SyncMessage.Request.Type.KEYS, "keys")

    private fun requestSync(type: SyncMessage.Request.Type, what: String): Boolean =
        try {
            val sender = connection.getOrCreateSender()
            sender.sendSyncMessage(SignalServiceSyncMessage.forRequest(RequestMessage.forType(type)))
            Log.i(TAG, "Requested $what sync from primary")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request $what sync: ${e.message}")
            invalidate()
            false
        }

    fun attachReceiverWebSockets(
        authWs: SignalWebSocket.AuthenticatedWebSocket,
        unauthWs: SignalWebSocket.UnauthenticatedWebSocket,
    ) = connection.attachReceiverWebSockets(authWs, unauthWs)

    fun detachReceiverWebSockets(
        authWs: SignalWebSocket.AuthenticatedWebSocket?,
        unauthWs: SignalWebSocket.UnauthenticatedWebSocket?,
    ) = connection.detachReceiverWebSockets(authWs, unauthWs)

    /** Drop the library sender (and any sockets of its own) so the next send rebuilds it. */
    fun invalidate() = connection.invalidate()

    fun shutdown() = connection.shutdown()
}
