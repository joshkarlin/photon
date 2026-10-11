package app.photon.signal.receiver

import android.util.Log
import app.photon.signal.EnvelopeRetryTracker
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.envelopeRetryKey
import org.signal.libsignal.metadata.ProtocolDuplicateMessageException
import org.signal.libsignal.metadata.ProtocolException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.api.crypto.SignalGroupSessionBuilder
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher
import org.whispersystems.signalservice.api.messages.EnvelopeResponse
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.Envelope

private const val TAG = SignalMessageReceiver.TAG

// In-process retry delay for an envelope left un-acked after a local
// processing failure (see retryHeldEnvelopes).
private const val HELD_ENVELOPE_RETRY_MS = 10_000L

/**
 * One envelope from the read loop: decrypt, dispatch to the handlers, then
 * ack — or hold it for a retry, or send a retry receipt for a failed decrypt.
 */
internal class EnvelopeProcessor(
    private val senderKeySessionBuilder: SignalGroupSessionBuilder,
    private val receipts: Receipts,
    private val dataMessages: DataMessageHandler,
    private val syncMessages: SyncMessageHandler,
    private val calls: CallHandler,
) {
    /** A decrypted envelope, held across redeliveries if processing fails. */
    private class DecryptedEnvelope(val content: Content, val metadata: EnvelopeMetadata)

    // Envelopes left un-acked after a local processing failure.
    private val envelopeRetries = EnvelopeRetryTracker<DecryptedEnvelope>()
    // Their in-process retries, run by the read loop on its own thread.
    private class HeldRetry(
        val response: EnvelopeResponse,
        val socket: SignalWebSocket.AuthenticatedWebSocket,
        val dueAt: Long,
    )
    private val heldRetries = mutableListOf<HeldRetry>()

    /**
     * Decrypt, store, then ack. The ack tells the server to delete the
     * envelope, so it only goes out once the content is safely processed, or
     * when retrying can't help (decrypt failures, duplicates). If local
     * processing throws, the envelope is left un-acked for the server to
     * redeliver, with the decrypted payload held for that redelivery (see
     * [EnvelopeRetryTracker]) and an in-process retry queued; a poison
     * envelope is acked after 3 failures. Reprocessing is safe: inserts
     * dedupe on the (author, timestamp) key.
     */
    fun processEnvelope(
        envelopeResponse: EnvelopeResponse,
        cipher: SignalServiceCipher,
        ws: SignalWebSocket.AuthenticatedWebSocket,
    ) {
        val envelope = envelopeResponse.envelope
        val retryKey = envelopeRetryKey(envelope)
        var decrypted: DecryptedEnvelope? = envelopeRetries.pending(retryKey)
        val ack = try {
            if (decrypted == null) {
                val result = cipher.decrypt(envelope, envelopeResponse.serverDeliveredTimestamp)
                if (result == null) {
                    receipts.processContentlessEnvelope(envelope)
                } else {
                    decrypted = DecryptedEnvelope(result.content, result.metadata)
                }
            } else {
                Log.i(TAG, "Reprocessing held envelope ${retryKey.take(12)}… (decrypted on an earlier delivery)")
            }
            decrypted?.let { processContent(it.content, it.metadata, envelopeResponse) }
            envelopeRetries.clear(retryKey)
            true
        } catch (e: org.signal.libsignal.metadata.SelfSendException) {
            Log.d(TAG, "Self-send exception (normal for sync), acking")
            true
        } catch (e: ProtocolDuplicateMessageException) {
            Log.d(TAG, "Duplicate message, acking")
            true
        } catch (e: ProtocolException) {
            Log.w(
                TAG,
                "Protocol decrypt failed (type=${envelope.type}, sender=${e.sender}, " +
                    "senderDevice=${e.senderDevice}, clientTs=${envelope.clientTimestamp}): " +
                    "${e.javaClass.simpleName}: ${e.message}",
            )
            maybeSendRetryReceipt(e, envelopeResponse)
            true
        } catch (e: org.signal.libsignal.protocol.DuplicateMessageException) {
            Log.d(TAG, "Duplicate message, acking")
            true
        } catch (e: Exception) {
            val giveUp = envelopeRetries.recordFailure(retryKey, decrypted)
            val description = "envelope (type=${envelope.type}, sourceServiceId=${envelope.sourceServiceId}, " +
                "sourceDevice=${envelope.sourceDeviceId}, clientTs=${envelope.clientTimestamp}, " +
                "serverTs=${envelope.serverTimestamp}, urgent=${envelope.urgent}): " +
                "${e.javaClass.name}: ${e.message}"
            if (giveUp) {
                Log.e(TAG, "Giving up on $description — acking after repeated failures", e)
            } else {
                Log.w(TAG, "Failed to process $description — leaving un-acked, retrying", e)
                synchronized(heldRetries) {
                    heldRetries.add(HeldRetry(envelopeResponse, ws, System.currentTimeMillis() + HELD_ENVELOPE_RETRY_MS))
                }
            }
            giveUp
        }
        if (ack) ws.sendAck(envelopeResponse)
    }

    /**
     * The server only redelivers an un-acked envelope after a reconnect, which
     * can be hours away on a healthy connection. The read loop retries held
     * envelopes itself, between batches and on its own thread (so a retry
     * can't race other processing), through the same path: it counts towards
     * the poison limit and is acked once processed. Only envelopes received
     * on the current socket can be acked on it; the server redelivers the
     * others on the new connection, and one a redelivery already handled is
     * no longer held.
     */
    fun retryHeldEnvelopes(cipher: SignalServiceCipher, ws: SignalWebSocket.AuthenticatedWebSocket) {
        val now = System.currentTimeMillis()
        val due = synchronized(heldRetries) {
            heldRetries.filter { it.dueAt <= now }.also { heldRetries.removeAll(it) }
        }
        for (held in due) {
            if (held.socket !== ws) continue
            if (!envelopeRetries.isHeld(envelopeRetryKey(held.response.envelope))) continue
            processEnvelope(held.response, cipher, ws)
        }
    }

    /** Drop held envelopes and their retries (Signal reset). */
    fun clearHeld() {
        envelopeRetries.clearAll()
        synchronized(heldRetries) { heldRetries.clear() }
    }

    private fun processContent(content: Content, metadata: EnvelopeMetadata, envelopeResponse: EnvelopeResponse) {
        content.senderKeyDistributionMessage?.let {
            processSenderKeyDistribution(it.toByteArray(), metadata)
        }

        when {
            content.dataMessage != null -> dataMessages.processDataMessage(content.dataMessage!!, metadata)
            content.syncMessage != null -> syncMessages.processSyncMessage(content.syncMessage!!, metadata)
            content.callMessage != null -> calls.processCallMessage(content.callMessage!!, metadata, envelopeResponse)
            content.receiptMessage != null -> receipts.processReceipt(content.receiptMessage!!, metadata)
            content.typingMessage != null -> {} // ignore
            content.senderKeyDistributionMessage != null -> {} // already stored above
            else -> Log.d(TAG, "Unhandled content type")
        }
    }

    private fun processSenderKeyDistribution(serialized: ByteArray, metadata: EnvelopeMetadata) {
        if (serialized.isEmpty()) {
            Log.w(TAG, "Ignoring empty sender-key distribution from ${metadata.sourceServiceId}")
            return
        }
        try {
            val distribution = SenderKeyDistributionMessage(serialized)
            val senderAddress = SignalProtocolAddress(
                metadata.sourceServiceId.toString(),
                metadata.sourceDeviceId,
            )
            senderKeySessionBuilder.process(senderAddress, distribution)
            Log.i(
                TAG,
                "Stored sender-key distribution from ${metadata.sourceServiceId.toString().take(8)}… " +
                    "device=${metadata.sourceDeviceId} distribution=${distribution.distributionId}",
            )
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Failed to store sender-key distribution from ${metadata.sourceServiceId}: " +
                    "${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    private fun maybeSendRetryReceipt(error: ProtocolException, envelopeResponse: EnvelopeResponse) {
        if (!shouldSendRetryReceipt(error)) return

        val env = envelopeResponse.envelope
        val unidentified = error.unidentifiedSenderMessageContent.orElse(null)
        val originalContent = unidentified?.content ?: env.content?.toByteArray()
        if (originalContent == null || originalContent.isEmpty()) {
            Log.w(TAG, "Skipping retry receipt: no original ciphertext for ${error.sender}")
            return
        }

        val originalType = unidentified?.type ?: ciphertextTypeForEnvelope(env.type)
        if (originalType == null) {
            Log.w(TAG, "Skipping retry receipt: unsupported envelope type ${env.type}")
            return
        }

        val sender = error.sender.takeIf { it.isNotBlank() } ?: env.sourceServiceId
        if (sender.isNullOrBlank()) {
            Log.w(TAG, "Skipping retry receipt: missing sender")
            return
        }

        val timestamp = env.clientTimestamp ?: env.serverTimestamp ?: System.currentTimeMillis()
        val groupId = unidentified?.groupId?.orElse(null) ?: error.groupId.orElse(null)
        val sent = app.photon.service.PhotonService._signalSender?.sendRetryReceiptForDecryptionFailure(
            senderServiceId = sender,
            senderDeviceId = error.senderDevice,
            groupId = groupId,
            originalContent = originalContent,
            originalType = originalType,
            originalTimestamp = timestamp,
        ) ?: false
        if (!sent) {
            Log.w(TAG, "Retry receipt not sent for failed decrypt from ${sender.take(8)}…")
        }
    }

    private fun shouldSendRetryReceipt(error: ProtocolException): Boolean = when (error) {
        is org.signal.libsignal.metadata.ProtocolNoSessionException,
        is org.signal.libsignal.metadata.ProtocolInvalidMessageException,
        is org.signal.libsignal.metadata.ProtocolInvalidKeyIdException,
        is org.signal.libsignal.metadata.ProtocolInvalidKeyException,
        is org.signal.libsignal.metadata.ProtocolInvalidVersionException -> true
        else -> false
    }

    private fun ciphertextTypeForEnvelope(type: Envelope.Type?): Int? = when (type) {
        Envelope.Type.DOUBLE_RATCHET -> CiphertextMessage.WHISPER_TYPE
        Envelope.Type.PREKEY_MESSAGE -> CiphertextMessage.PREKEY_TYPE
        Envelope.Type.PLAINTEXT_CONTENT -> CiphertextMessage.PLAINTEXT_CONTENT_TYPE
        else -> null
    }
}
