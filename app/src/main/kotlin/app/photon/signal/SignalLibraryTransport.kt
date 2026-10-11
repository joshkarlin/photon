package app.photon.signal

import android.util.Log
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.findMessageByPrefix
import app.photon.signal.db.getContact
import app.photon.signal.db.getGroupMeta
import app.photon.signal.db.getParticipants
import app.photon.signal.db.updateGroupMeta
import app.photon.signal.db.upsertConversation
import app.photon.signal.db.upsertParticipant
import app.photon.signal.store.PhotonIdentityKeyStore
import com.fasterxml.jackson.databind.JsonNode
import org.signal.core.models.ServiceId
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.crypto.AttachmentCipherStreamUtil
import org.whispersystems.signalservice.api.crypto.ContentHint
import org.whispersystems.signalservice.api.crypto.SealedSenderAccess
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.messages.SignalServiceGroupV2
import org.whispersystems.signalservice.api.messages.multidevice.SentTranscriptMessage
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration
import org.whispersystems.signalservice.internal.crypto.PaddingInputStream
import org.whispersystems.signalservice.internal.push.AttachmentUploadForm
import org.whispersystems.signalservice.internal.push.PushServiceSocket
import org.whispersystems.signalservice.internal.util.JsonUtil
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Optional

// Logged under the sender's tag, which existing logcat filters use.
private const val TAG = "SignalMessageSender"

internal fun signalAttachmentUploadLength(fileLength: Long): Long =
    AttachmentCipherStreamUtil.getCiphertextLength(PaddingInputStream.getPaddedSize(fileLength))

internal fun parseSignalAttachmentUploadForm(response: String): AttachmentUploadForm {
    val json = JsonUtil.fromJson(response, JsonNode::class.java)
    val cdn = json.get("cdn")?.intValue() ?: error("Missing attachment CDN")
    require(cdn == 2 || cdn == 3) { "Unsupported attachment CDN: $cdn" }
    val key = json.get("key")?.textValue() ?: error("Missing attachment key")
    val location = json.get("signedUploadLocation")?.textValue()
        ?: error("Missing attachment upload location")
    val headersNode = json.get("headers") ?: error("Missing attachment headers")
    require(headersNode.isObject) { "Invalid attachment headers" }
    val headers = headersNode.properties().associate { (name, value) ->
        name to (value.textValue() ?: error("Invalid attachment header: $name"))
    }
    return AttachmentUploadForm(cdn, key, headers, location)
}

/**
 * Sends through signal-service-java: the DataMessage to the recipient (or,
 * for a group, each member), then a sent transcript so the user's other
 * devices show it in the right thread.
 */
internal class LibraryTransport(
    private val credentials: SignalCredentials,
    private val messageDb: SignalMessageDatabase,
    private val identityStore: PhotonIdentityKeyStore,
    private val config: () -> SignalServiceConfiguration,
    // The library sender, over the receiver's sockets when it has them.
    private val sender: () -> SignalServiceMessageSender,
    // The receiver's, while it's connected.
    private val groupManager: () -> SignalGroupV2Manager?,
) : SignalTransport {

    override fun send(recipient: Recipient, content: OutgoingContent, timestamp: Long) = when (recipient) {
        is Recipient.Dm -> sendDm(recipient, content, timestamp)
        is Recipient.Group -> sendGroup(recipient, content, timestamp)
    }

    private fun dataMessage(content: OutgoingContent, timestamp: Long, group: SignalServiceGroupV2?): SignalServiceDataMessage {
        val builder = SignalServiceDataMessage.newBuilder().withTimestamp(timestamp)
        if (group != null) builder.asGroupMessage(group)
        when (content) {
            is OutgoingContent.Reaction -> builder.withReaction(content.reaction)
            is OutgoingContent.RemoteDelete ->
                builder.withRemoteDelete(SignalServiceDataMessage.RemoteDelete(content.targetSentTimestamp))
            is OutgoingContent.Message -> {
                val quote = content.replyPrefix?.let { buildQuote(it) }
                if (!content.text.isNullOrBlank()) builder.withBody(content.text)
                if (content.attachments.isNotEmpty()) builder.withAttachments(content.attachments)
                if (quote != null) builder.withQuote(quote)
            }
        }
        return builder.build()
    }

    /**
     * One-to-one DataMessage send. Attaches the recipient's E.164 to the
     * address when known — Desktop / older clients index by (ACI ∪ E.164)
     * and otherwise spawn an "Unknown contact" thread. SentTranscript is
     * sent so other linked devices show the outgoing in the right thread.
     */
    private fun sendDm(recipient: Recipient.Dm, content: OutgoingContent, timestamp: Long) {
        val aci = recipient.aci
        val recipientPhone = messageDb.getContact(recipient.conversationJid)?.phone
        val recipientAddress = if (recipientPhone != null) {
            SignalServiceAddress(aci, recipientPhone)
        } else SignalServiceAddress(aci)
        val message = dataMessage(content, timestamp, group = null)

        val sender = sender()
        val result = try {
            sender.sendDataMessage(
                recipientAddress,
                null,
                ContentHint.RESENDABLE,
                message,
                SignalServiceMessageSender.IndividualSendEvents.EMPTY,
                false, false,
            )
        } catch (e: UntrustedIdentityException) {
            identityStore.recordSendIdentityFailure(aci.toString(), e.identityKey)
            throw e
        }
        result.identityFailure?.let {
            identityStore.recordSendIdentityFailure(aci.toString(), it.identityKey)
        }
        if (!result.isSuccess) throw IOException("Signal send failed; check safety number if it changed")

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
     */
    private fun sendGroup(recipient: Recipient.Group, content: OutgoingContent, timestamp: Long) {
        val conversationJid = recipient.conversationJid
        val groupMeta = recipient.meta
        val members = groupMembers(recipient)

        val masterKey = GroupMasterKey(groupMeta.masterKey)
        // The stored revision is either what we last saw on incoming or
        // what the bootstrap in groupMembers just fetched. Re-read from DB
        // so both paths use the same source.
        val currentRevision = messageDb.getGroupMeta(conversationJid)?.revision ?: groupMeta.revision
        val groupContext = SignalServiceGroupV2.newBuilder(masterKey)
            .withRevision(currentRevision)
            .build()
        val message = dataMessage(content, timestamp, groupContext)

        val recipientAddresses = members.map { SignalServiceAddress(it) }
        // Same-size list of nulls: signals non-sealed-sender for each recipient.
        val sealedSenderAccesses: List<SealedSenderAccess?> = List(recipientAddresses.size) { null }

        val sender = sender()
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
        for (result in results) {
            result.identityFailure?.let {
                identityStore.recordSendIdentityFailure(result.address.serviceId.toString(), it.identityKey)
            }
        }
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
     * The other members, from the cached participants table. Signal bumps
     * the group revision on every membership/title change, and the receiver
     * refetches whenever an incoming message carries a higher revision than
     * what we've stored — so the cache is current unless the group has
     * changed without us seeing a message yet (rare, and self-correcting on
     * the next inbound).
     *
     * First-send-to-group fallback: if the participants table is empty
     * (e.g. this group was seeded by an outgoing-only send and no incoming
     * message has populated members yet), do one fetch here to bootstrap
     * and write the result back. This is the only path that hits the
     * network — subsequent sends use the cache.
     */
    private fun groupMembers(recipient: Recipient.Group): List<ServiceId.ACI> {
        val conversationJid = recipient.conversationJid
        val myAci = credentials.aci
            ?: throw IllegalStateException("No local ACI available")
        val cachedMembers = messageDb.getParticipants(conversationJid)
            .mapNotNull { p ->
                try { ServiceId.ACI.parseOrNull(p.jid) } catch (_: Exception) { null }
            }
        val members = if (cachedMembers.isNotEmpty()) {
            cachedMembers.filter { it != myAci }
        } else {
            val groupManager = groupManager()
                ?: throw IllegalStateException("Group manager not ready — receiver not connected?")
            val group = groupManager.fetchGroup(recipient.meta.masterKey)
                ?: throw IllegalStateException("Couldn't fetch group state for $conversationJid")
            group.title?.takeIf { it.isNotBlank() }?.let { title ->
                messageDb.upsertConversation(jid = conversationJid, name = title, isGroup = true)
            }
            messageDb.updateGroupMeta(conversationJid, recipient.meta.masterKey, group.revision)
            for (memberAci in groupManager.memberAcis(group)) {
                messageDb.upsertParticipant(conversationJid, memberAci.toString())
            }
            groupManager.memberAcis(group).filter { it != myAci }
        }
        if (members.isEmpty()) {
            throw IllegalStateException("Group has no other members to send to")
        }
        return members
    }

    /**
     * Upload a local file to Signal's CDN and return the attachment pointer
     * to embed in a DataMessage. The pointer (not the stream) is reused in
     * the sync transcript, so linked devices download the same upload.
     */
    override fun upload(file: File, mimeType: String, voiceNote: Boolean): SignalServiceAttachmentPointer {
        val sender = sender()
        val pushSocket = PushServiceSocket(config(), credentials, SignalConfig.USER_AGENT, false)
        val uploadLength = signalAttachmentUploadLength(file.length())
        val response = pushSocket.serviceRequest(
            "/v4/attachments/form/upload?uploadLength=$uploadLength", "GET", null,
        ) ?: error("Empty attachment upload form")
        val form = parseSignalAttachmentUploadForm(response)
        val uploadSpec = pushSocket.getResumableUploadSpec(form)
        val stream = SignalServiceAttachment.newStreamBuilder()
            .withStream(FileInputStream(file))
            .withContentType(mimeType)
            .withLength(file.length())
            .withFileName(file.name)
            .withVoiceNote(voiceNote)
            .withUploadTimestamp(System.currentTimeMillis())
            .withResumableUploadSpec(uploadSpec)
            .build()
        return stream.use { sender.uploadAttachment(it) }
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
}
