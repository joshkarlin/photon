package app.photon.signal

import android.util.Log
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.store.PhotonProtocolStore
import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.api.SignalServiceAccountDataStore
import org.whispersystems.signalservice.api.SignalServiceDataStore
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.SignalSessionLock
import org.whispersystems.signalservice.api.attachment.AttachmentApi
import org.whispersystems.signalservice.api.crypto.ContentHint
import org.whispersystems.signalservice.api.keys.KeysApi
import org.whispersystems.signalservice.api.message.MessageApi
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.messages.multidevice.RequestMessage
import org.whispersystems.signalservice.api.messages.multidevice.SentTranscriptMessage
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.util.SleepTimer
import org.whispersystems.signalservice.api.websocket.HealthMonitor
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.api.websocket.WebSocketFactory
import org.whispersystems.signalservice.internal.push.PushServiceSocket
import org.whispersystems.signalservice.internal.websocket.OkHttpWebSocketConnection
import java.io.File
import java.io.FileInputStream
import java.util.Optional
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

class SignalMessageSender(
    private val credentials: SignalCredentials,
    private val protocolStore: PhotonProtocolStore,
    private val messageDb: SignalMessageDatabase,
) {
    companion object {
        private const val TAG = "SignalMessageSender"
    }

    private val config = SignalConfig.createConfiguration()
    private val sessionLock = object : SignalSessionLock {
        private val lock = ReentrantLock()
        override fun acquire(): SignalSessionLock.Lock {
            lock.lock()
            return SignalSessionLock.Lock { lock.unlock() }
        }
    }
    private val executor = Executors.newCachedThreadPool()

    // Lazily initialized, reused across sends
    @Volatile private var sender: SignalServiceMessageSender? = null
    @Volatile private var authWs: SignalWebSocket.AuthenticatedWebSocket? = null

    private fun getOrCreateSender(): SignalServiceMessageSender {
        sender?.let { return it }

        synchronized(this) {
            sender?.let { return it }

            val pushSocket = PushServiceSocket(config, credentials, SignalConfig.USER_AGENT, false)

            val wsFactory = WebSocketFactory {
                OkHttpWebSocketConnection(
                    "photon-send", config, Optional.of(credentials), SignalConfig.USER_AGENT,
                    object : HealthMonitor {
                        override fun onKeepAliveResponse(sentTimestamp: Long, isIdentifiedWebSocket: Boolean) {}
                        override fun onMessageError(status: Int, isIdentifiedWebSocket: Boolean) {}
                    },
                    true,
                )
            }
            val unauthFactory = WebSocketFactory {
                OkHttpWebSocketConnection(
                    "photon-send-unauth", config, Optional.empty(), SignalConfig.USER_AGENT,
                    object : HealthMonitor {
                        override fun onKeepAliveResponse(sentTimestamp: Long, isIdentifiedWebSocket: Boolean) {}
                        override fun onMessageError(status: Int, isIdentifiedWebSocket: Boolean) {}
                    },
                    false,
                )
            }

            val sleepTimer = object : SleepTimer { override fun sleep(millis: Long) = Thread.sleep(millis) }
            val aws = SignalWebSocket.AuthenticatedWebSocket(wsFactory, { true }, sleepTimer, 30_000L)
            val uws = SignalWebSocket.UnauthenticatedWebSocket(unauthFactory, { true }, sleepTimer, 30_000L)
            aws.connect()
            authWs = aws

            val dataStore = object : SignalServiceDataStore {
                override fun get(serviceId: ServiceId): SignalServiceAccountDataStore = protocolStore
                override fun aci(): SignalServiceAccountDataStore = protocolStore
                override fun pni(): SignalServiceAccountDataStore = protocolStore
                override fun isMultiDevice(): Boolean = true
            }

            val s = SignalServiceMessageSender(
                pushSocket, dataStore, sessionLock,
                AttachmentApi(aws, pushSocket),
                MessageApi(aws, uws),
                KeysApi(aws, uws),
                Optional.empty(),
                executor,
                System.currentTimeMillis(),
                0,
                { true },
                false,
                false,
            )
            sender = s
            return s
        }
    }

    /**
     * Send a text message. The local DB row is written BEFORE the network
     * attempt so the UI sees the outgoing message instantly, with status
     * transitioning from "sending" → "sent" / "failed". Failures leave the
     * row in place with status="failed" so the user can retry from the UI;
     * exceptions are NOT re-thrown so the calling coroutine doesn't have to
     * catch them just to keep the UI alive.
     *
     * Routes to either DM (ACI-shaped jid) or GroupV2 (base64 group id with
     * a stored master_key). Groups without a stored master_key fail loudly —
     * we can't synthesize one, so the user has to wait for an incoming
     * group message to seed the master key before they can send.
     */
    fun sendTextMessage(conversationJid: String, text: String, replyToId: String? = null) {
        val timestamp = System.currentTimeMillis()
        val replyPrefix = replyToId?.substringBeforeLast("_")
        // Prefix "{authorAci}_{timestampMs}" must stay stable so quote lookups
        // via findMessageByPrefix still match. The random suffix only exists
        // to avoid local PK collisions on rapid-fire sends with the same ms.
        val messageId = "${credentials.aciString}_${timestamp}_${UUID.randomUUID().toString().take(8)}"

        val aci = ServiceId.ACI.parseOrNull(conversationJid)
        val groupMeta = if (aci == null) messageDb.getGroupMeta(conversationJid) else null

        if (aci == null && groupMeta == null) {
            Log.w(TAG, "Refusing send: $conversationJid is neither an ACI nor a known group")
            messageDb.insertMessage(
                id = messageId, conversationJid = conversationJid,
                senderJid = credentials.aciString ?: "",
                timestamp = timestamp / 1000, contentType = "text",
                textBody = text, replyToId = replyPrefix,
                isFromMe = true, status = "failed",
            )
            messageDb.updateConversationLastMessage(conversationJid, messageId, timestamp / 1000)
            return
        }

        messageDb.insertMessage(
            id = messageId, conversationJid = conversationJid,
            senderJid = credentials.aciString ?: "",
            timestamp = timestamp / 1000, contentType = "text",
            textBody = text, replyToId = replyPrefix,
            isFromMe = true, status = "sending",
        )
        messageDb.updateConversationLastMessage(conversationJid, messageId, timestamp / 1000)

        try {
            if (aci != null) {
                sendDmInternal(aci, conversationJid, text, timestamp, replyPrefix)
            } else {
                sendGroupInternal(conversationJid, groupMeta!!, text, timestamp, replyPrefix)
            }
            messageDb.updateMessageStatus(messageId, "sent")
            Log.i(TAG, "Sent $messageId to $conversationJid")
        } catch (e: Exception) {
            Log.e(TAG, "Send failed for $messageId; row marked failed", e)
            messageDb.updateMessageStatus(messageId, "failed")
            invalidate()
        }
    }

    /**
     * Send a media message (voice note, image, etc). Same local-row-first
     * pattern as sendTextMessage: the row is written with status="sending"
     * (carrying media_url/media_mime so the bubble renders), the file is
     * uploaded to Signal's CDN, and the resulting pointer rides a normal
     * DataMessage. Audio sends are flagged as voice notes — that's the only
     * audio Photon records.
     */
    fun sendMediaMessage(
        conversationJid: String,
        filePath: String,
        mimeType: String,
        caption: String?,
        replyToId: String? = null,
    ) {
        val timestamp = System.currentTimeMillis()
        val replyPrefix = replyToId?.substringBeforeLast("_")
        val messageId = "${credentials.aciString}_${timestamp}_${UUID.randomUUID().toString().take(8)}"
        val contentType = when {
            mimeType.startsWith("image/") -> "image"
            mimeType.startsWith("video/") -> "video"
            mimeType.startsWith("audio/") -> "audio"
            else -> "document"
        }
        val file = File(filePath)

        val aci = ServiceId.ACI.parseOrNull(conversationJid)
        val groupMeta = if (aci == null) messageDb.getGroupMeta(conversationJid) else null

        fun insertRow(status: String) {
            messageDb.insertMessage(
                id = messageId, conversationJid = conversationJid,
                senderJid = credentials.aciString ?: "",
                timestamp = timestamp / 1000, contentType = contentType,
                textBody = caption?.takeIf { it.isNotBlank() },
                mediaUrl = filePath, mediaMime = mimeType,
                mediaSize = file.length().takeIf { it > 0 },
                replyToId = replyPrefix,
                isFromMe = true, status = status,
            )
            messageDb.updateConversationLastMessage(conversationJid, messageId, timestamp / 1000)
        }

        if ((aci == null && groupMeta == null) || !file.exists()) {
            Log.w(TAG, "Refusing media send to $conversationJid " +
                "(knownTarget=${aci != null || groupMeta != null}, fileExists=${file.exists()})")
            insertRow("failed")
            return
        }

        insertRow("sending")

        try {
            val pointer = uploadAttachment(file, mimeType, voiceNote = contentType == "audio")
            if (aci != null) {
                sendDmInternal(aci, conversationJid, caption, timestamp, replyPrefix, listOf(pointer))
            } else {
                sendGroupInternal(conversationJid, groupMeta!!, caption, timestamp, replyPrefix, listOf(pointer))
            }
            messageDb.updateMessageStatus(messageId, "sent")
            Log.i(TAG, "Sent media $messageId ($mimeType, ${file.length()}B) to $conversationJid")
        } catch (e: Exception) {
            Log.e(TAG, "Media send failed for $messageId; row marked failed", e)
            messageDb.updateMessageStatus(messageId, "failed")
            invalidate()
        }
    }

    /**
     * Upload a local file to Signal's CDN and return the attachment pointer
     * to embed in a DataMessage. The pointer (not the stream) is reused in
     * the sync transcript, so linked devices download the same upload.
     */
    private fun uploadAttachment(file: File, mimeType: String, voiceNote: Boolean): SignalServiceAttachmentPointer {
        val sender = getOrCreateSender()
        val stream = SignalServiceAttachment.newStreamBuilder()
            .withStream(FileInputStream(file))
            .withContentType(mimeType)
            .withLength(file.length())
            .withFileName(file.name)
            .withVoiceNote(voiceNote)
            .withUploadTimestamp(System.currentTimeMillis())
            .build()
        return stream.use { sender.uploadAttachment(it) }
    }

    /**
     * One-to-one DataMessage send. Attaches the recipient's E.164 to the
     * address when known — Desktop / older clients index by (ACI ∪ E.164)
     * and otherwise spawn an "Unknown contact" thread. SentTranscript is
     * sent so other linked devices show the outgoing in the right thread.
     */
    private fun sendDmInternal(
        aci: ServiceId.ACI,
        conversationJid: String,
        text: String?,
        timestamp: Long,
        replyPrefix: String?,
        attachments: List<SignalServiceAttachment> = emptyList(),
    ) {
        val recipientPhone = messageDb.getContact(conversationJid)?.phone
        val recipientAddress = if (recipientPhone != null) {
            SignalServiceAddress(aci, recipientPhone)
        } else SignalServiceAddress(aci)

        val quote = replyPrefix?.let { buildQuote(it) }
        val builder = SignalServiceDataMessage.newBuilder()
            .withTimestamp(timestamp)
        if (!text.isNullOrBlank()) builder.withBody(text)
        if (attachments.isNotEmpty()) builder.withAttachments(attachments)
        if (quote != null) builder.withQuote(quote)
        val message = builder.build()

        val sender = getOrCreateSender()
        sender.sendDataMessage(
            recipientAddress,
            null,
            ContentHint.RESENDABLE,
            message,
            SignalServiceMessageSender.IndividualSendEvents.EMPTY,
            false, false,
        )

        // Sync transcript best-effort: the recipient already got the
        // message above. If the sync fails, our linked devices won't see
        // the outgoing in the right thread until storage-service sync
        // next runs, but we shouldn't flip the local row to failed —
        // that's misleading and would tempt the user into a duplicate
        // resend.
        try {
            // unidentifiedStatusBySid must agree with the actual send mode
            // (non-sealed → false); a `true` here makes Desktop create a
            // separate "Unknown contact" thread per message.
            // expirationStartTimestamp is an absolute time, not a duration.
            val transcript = SentTranscriptMessage(
                Optional.of(recipientAddress), timestamp, Optional.of(message), 0L,
                mapOf<ServiceId, Boolean>(aci to false),
                false, Optional.empty(), emptySet(), Optional.empty(),
            )
            sender.sendSyncMessage(SignalServiceSyncMessage.forSentTranscript(transcript))
        } catch (e: Exception) {
            Log.w(TAG, "DM sync transcript failed (recipient still got the message): ${e.message}")
        }
    }

    /**
     * GroupV2 send via legacy per-recipient fan-out. We don't yet do the
     * sender-key + DistributionId path — fan-out is simpler, the server
     * does the heavy lifting for us, and it works without endorsement
     * tokens. The DataMessage carries `groupV2{masterKey, revision}` so
     * recipients route into the right thread.
     *
     * Member list comes from a fresh server fetch — small cost, fully
     * correct, and we re-use the credential cache across calls.
     */
    private fun sendGroupInternal(
        conversationJid: String,
        groupMeta: SignalMessageDatabase.GroupMeta,
        text: String?,
        timestamp: Long,
        replyPrefix: String?,
        attachments: List<SignalServiceAttachment> = emptyList(),
    ) {
        val myAci = credentials.aci
            ?: throw IllegalStateException("No local ACI available")

        // Use the cached member list from the participants table. Signal
        // bumps the group revision on every membership/title change, and
        // the receiver refetches whenever an incoming message carries a
        // higher revision than what we've stored — so the cache is current
        // unless the group has changed without us seeing a message yet
        // (rare, and self-correcting on the next inbound).
        //
        // First-send-to-group fallback: if the participants table is empty
        // (e.g. this group was seeded by an outgoing-only send and no
        // incoming message has populated members yet), do one fetch here
        // to bootstrap and write the result back. This is the only path
        // that hits the network — subsequent sends use the cache.
        val cachedMembers = messageDb.getParticipants(conversationJid)
            .mapNotNull { p ->
                try { ServiceId.ACI.parseOrNull(p.jid) } catch (_: Exception) { null }
            }
        val members = if (cachedMembers.isNotEmpty()) {
            cachedMembers.filter { it != myAci }
        } else {
            val groupManager = app.photon.service.PhotonService._signalGroupManager
                ?: throw IllegalStateException("Group manager not ready — receiver not connected?")
            val group = groupManager.fetchGroup(groupMeta.masterKey)
                ?: throw IllegalStateException("Couldn't fetch group state for $conversationJid")
            group.title?.takeIf { it.isNotBlank() }?.let { title ->
                messageDb.upsertConversation(jid = conversationJid, name = title, isGroup = true)
            }
            messageDb.updateGroupMeta(conversationJid, groupMeta.masterKey, group.revision)
            for (memberAci in groupManager.memberAcis(group)) {
                val aciString = memberAci.toString()
                val contact = messageDb.getContact(aciString)
                val displayName = contact?.profileName?.takeIf { it.isNotBlank() }
                    ?: contact?.phone
                    ?: aciString.take(8)
                messageDb.upsertParticipant(conversationJid, aciString, displayName, "member")
            }
            groupManager.memberAcis(group).filter { it != myAci }
        }
        if (members.isEmpty()) {
            throw IllegalStateException("Group has no other members to send to")
        }

        val masterKey = org.signal.libsignal.zkgroup.groups.GroupMasterKey(groupMeta.masterKey)
        // The stored revision is either what we last saw on incoming or
        // what we just fetched in the bootstrap branch above. Re-read from
        // DB so both paths use the same source.
        val currentRevision = messageDb.getGroupMeta(conversationJid)?.revision ?: groupMeta.revision
        val groupContext = org.whispersystems.signalservice.api.messages.SignalServiceGroupV2
            .newBuilder(masterKey)
            .withRevision(currentRevision)
            .build()

        val quote = replyPrefix?.let { buildQuote(it) }
        val builder = SignalServiceDataMessage.newBuilder()
            .withTimestamp(timestamp)
            .asGroupMessage(groupContext)
        if (!text.isNullOrBlank()) builder.withBody(text)
        if (attachments.isNotEmpty()) builder.withAttachments(attachments)
        if (quote != null) builder.withQuote(quote)
        val message = builder.build()

        val recipientAddresses = members.map { SignalServiceAddress(it) }
        // Same-size list of nulls: signals non-sealed-sender for each recipient.
        val sealedSenderAccesses: List<org.whispersystems.signalservice.api.crypto.SealedSenderAccess?> =
            List(recipientAddresses.size) { null }

        val sender = getOrCreateSender()
        val results = sender.sendDataMessage(
            recipientAddresses,
            sealedSenderAccesses,
            false,                                              // isRecipientUpdate
            ContentHint.RESENDABLE,
            message,
            SignalServiceMessageSender.LegacyGroupEvents.EMPTY,
            null, null, false,
        )

        // Report per-recipient outcomes so partial failures don't get
        // silently rolled into a generic "failed" status. The wire send
        // returns a list; an entry being non-success doesn't itself throw.
        val successes = results.count { it.isSuccess }
        val failures = results.size - successes
        if (failures > 0) {
            val reasons = results.filter { !it.isSuccess }.joinToString { r ->
                val who = r.address.serviceId.toString().take(8)
                val why = when {
                    r.isNetworkFailure -> "network"
                    r.isUnregisteredFailure -> "unregistered"
                    r.identityFailure != null -> "identity"
                    r.proofRequiredFailure != null -> "proof-required"
                    r.rateLimitFailure != null -> "rate-limit"
                    r.isInvalidPreKeyFailure -> "invalid-prekey"
                    r.isCanceledFailure -> "canceled"
                    else -> "unknown"
                }
                "$who:$why"
            }
            Log.w(TAG, "Group send had failures ($failures/${results.size}): $reasons")
        }
        if (successes == 0) {
            throw RuntimeException("Group send: all $failures recipients failed")
        }

        // Sync transcript so our other linked devices route into the same
        // group thread. Best-effort: if it fails we still consider the send
        // successful since the recipients got the message — the user's
        // primary/desktop just won't see it in the right thread until
        // storage-service sync next runs.
        try {
            val statusBySid: Map<ServiceId, Boolean> = members.associateWith { false }
            val transcript = SentTranscriptMessage(
                Optional.empty(),
                timestamp,
                Optional.of(message),
                0L,
                statusBySid,
                false,
                Optional.empty(),
                emptySet(),
                Optional.empty(),
            )
            sender.sendSyncMessage(SignalServiceSyncMessage.forSentTranscript(transcript))
        } catch (e: Exception) {
            Log.w(TAG, "Group sync transcript failed (recipients still got the message): ${e.message}")
        }
    }

    /**
     * Retry a previously failed message (text or media). Reuses the local
     * row's conversation_jid + content + reply_to_id and flips the status
     * back to "sending" while a fresh send is attempted. The local row keeps
     * its original ID so the chat list doesn't grow new bubbles per retry.
     * Media rows re-upload from the original local file.
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
        val conversationJid = row.conversationJid
        val aci = ServiceId.ACI.parseOrNull(conversationJid)
        val groupMeta = if (aci == null) messageDb.getGroupMeta(conversationJid) else null

        if (aci == null && groupMeta == null) {
            Log.w(TAG, "Retry refused: $conversationJid is not an ACI nor a known group")
            messageDb.updateMessageStatus(messageId, "failed")
            return false
        }

        messageDb.updateMessageStatus(messageId, "sending")
        // Keep the same wire timestamp by reusing the original — a retry
        // that actually went through the first time would otherwise bypass
        // recipient-side dedupe and produce a duplicate.
        val timestamp = row.timestamp * 1000
        return try {
            val attachments = if (isText) emptyList() else listOf(
                uploadAttachment(mediaFile!!, row.mediaMime ?: defaultMimeFor(row.contentType), voiceNote = row.contentType == "audio"),
            )
            if (aci != null) {
                sendDmInternal(aci, conversationJid, text, timestamp, row.replyToId, attachments)
            } else {
                sendGroupInternal(conversationJid, groupMeta!!, text, timestamp, row.replyToId, attachments)
            }
            messageDb.updateMessageStatus(messageId, "sent")
            Log.i(TAG, "Retry succeeded for $messageId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Retry failed for $messageId", e)
            messageDb.updateMessageStatus(messageId, "failed")
            invalidate()
            false
        }
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
            val sender = getOrCreateSender()
            sender.sendNullMessage(address, null)
            Log.i(TAG, "Sent session ping to $aci")
            true
        } catch (e: Exception) {
            Log.w(TAG, "sendSessionPing failed for $aci: ${e.javaClass.simpleName}: ${e.message}")
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
        try {
            val sender = getOrCreateSender()
            val request = RequestMessage.forType(
                org.whispersystems.signalservice.internal.push.SyncMessage.Request.Type.CONTACTS,
            )
            sender.sendSyncMessage(SignalServiceSyncMessage.forRequest(request))
            Log.i(TAG, "Requested contacts sync from primary")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request contacts sync: ${e.message}")
            invalidate()
        }
    }

    /**
     * Build a Signal quote from a local message prefix ("{authorAci}_{timestampMs}").
     * The quote transmits author + original sent timestamp; the text is a preview.
     * For media replies, attach a content-type-only QuotedAttachment (no thumbnail)
     * so the recipient renders it as a media quote rather than plain text.
     */
    private fun buildQuote(prefix: String): SignalServiceDataMessage.Quote? {
        val quoted = messageDb.findMessageByPrefix(prefix) ?: return null
        val lastUnderscore = prefix.lastIndexOf('_')
        if (lastUnderscore <= 0) return null
        val authorAciStr = prefix.substring(0, lastUnderscore)
        val timestampMs = prefix.substring(lastUnderscore + 1).toLongOrNull() ?: return null
        val author = ServiceId.parseOrNull(authorAciStr) ?: return null

        val attachments = when (quoted.contentType) {
            "image", "video", "audio", "document", "sticker" -> listOf(
                SignalServiceDataMessage.Quote.QuotedAttachment(
                    quoted.mediaMime ?: defaultMimeFor(quoted.contentType),
                    null,   // fileName (optional)
                    null,   // thumbnail — we don't re-upload the original as a thumbnail
                )
            )
            else -> emptyList()
        }

        return SignalServiceDataMessage.Quote(
            timestampMs,
            author,
            quoted.textBody ?: "",
            attachments,
            emptyList(),
            SignalServiceDataMessage.Quote.Type.NORMAL,
            emptyList(),
        )
    }

    private fun defaultMimeFor(contentType: String): String = when (contentType) {
        "image" -> "image/jpeg"
        "video" -> "video/mp4"
        "audio" -> "audio/ogg"
        "sticker" -> "image/webp"
        else -> "application/octet-stream"
    }

    fun invalidate() {
        synchronized(this) {
            try { authWs?.disconnect() } catch (_: Exception) {}
            authWs = null
            sender = null
        }
    }

    fun shutdown() {
        invalidate()
        executor.shutdown()
    }
}
