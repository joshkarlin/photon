package app.photon.sms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.MessageStatus
import app.photon.data.model.Platform
import app.photon.data.names.DisplayNames
import app.photon.data.names.LocalContacts
import app.photon.data.names.PhoneNumbers
import app.photon.data.repository.conversationsFlow
import app.photon.data.repository.messagesFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.merge

/**
 * SMS chats and messages, read from the system SMS provider. Photon isn't
 * the default SMS app, so it can't write there: read state is kept in its
 * own preferences, and messages it sends show from [PendingSends] until
 * Android has written them to the provider.
 *
 * @param radio what sends go through; replaced in tests
 */
class SmsRepository internal constructor(
    private val context: Context,
    scope: CoroutineScope,
    private val localContacts: LocalContacts,
    private val radio: SmsRadio,
) {
    constructor(context: Context, scope: CoroutineScope, localContacts: LocalContacts = LocalContacts.get(context)) :
        this(context, scope, localContacts, SystemSmsRadio(context))


    private val resolver: ContentResolver = context.contentResolver

    // Tracks when the user last opened each SMS conversation in Photon.
    // Used to compute unread state without being the default SMS app
    // (the provider's `read` column can only be written by the default app).
    private val readPrefs = context.getSharedPreferences("sms_read_state", Context.MODE_PRIVATE)

    private val pending = PendingSends()

    // A change to the pending sends, which only the messages read.
    private val pendingChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // Each conversation's provider threads, from the last full scan, so a
    // chat's messages are read by thread_id rather than by scanning every
    // SMS on the phone.
    @Volatile private var threadIds: Map<String, Set<Long>> = emptyMap()

    // The newest provider row each chat has shown, which a send made from
    // it is newer than (see PendingSends).
    private val newestRowIds = ConcurrentHashMap<String, Long>()

    private fun lastReadMillis(address: String): Long =
        readPrefs.getLong(normalizeAddress(address), 0L)

    fun markAsRead(address: String) {
        val normalized = normalizeAddress(address)
        val now = System.currentTimeMillis()
        readPrefs.edit().putLong(normalized, now).apply()

        // Best-effort: also update the provider. Will silently fail if we're
        // not the default SMS app, but works if the user has granted us that role.
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
            }
            resolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(address),
            )
        } catch (e: Exception) {
            Log.d(TAG, "Could not mark provider read (not default SMS app): ${e.message}")
        }

        // Nudge observers so conversation list refreshes immediately.
        resolver.notifyChange(Telephony.Sms.CONTENT_URI, null)
    }

    // Emits on every SMS provider change. The ContentObserver callback runs
    // on the main looper, so it only signals; the refresh flows read the
    // provider on IO.
    private fun providerChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    // Names re-resolve when the LP3 address book changes, too.
    val conversations: StateFlow<List<Conversation>?> =
        conversationsFlow(scope, TAG, merge(providerChanges(), localContacts.changes)) { loadConversations() }

    fun messages(address: String): Flow<List<Message>> =
        messagesFlow(
            changes = merge(providerChanges(), pendingChanges),
            load = { loadMessages(address) },
            // SMS has neither reactions nor replies.
            loadReactions = { emptyMap() },
            lookupReply = { null },
        )

    fun getConversation(address: String): Conversation? {
        return loadConversations().find { it.jid == normalizeAddress(address) }
    }

    /**
     * The chat with [phone], however its number was written when the chat
     * began ("07400 123456" for "+447400123456"); null when there's none.
     */
    fun conversationWith(phone: String): Conversation? {
        val normalized = normalizeAddress(phone)
        return loadConversations().find { conversation ->
            conversation.jid == normalized ||
                (DisplayNames.phoneOf(conversation.jid, Platform.SMS) != null && PhoneNumbers.same(conversation.jid, phone))
        }
    }

    /** The address a new chat with [phone] is keyed by. */
    fun addressFor(phone: String): String = normalizeAddress(phone)

    /** [address] named as its chat would be: its LP3 contact, else the number formatted. */
    fun personName(address: String): String = displayName(normalizeAddress(address))

    /**
     * Send [text] to [address]. It shows as sending at once, then as the
     * radio reports back (see [startSendResults]). Throws if SmsManager
     * refuses it outright (no SEND_SMS permission, an address it can't send
     * to), leaving it failed for a retry.
     */
    fun sendMessage(address: String, text: String) {
        val normalized = normalizeAddress(address)
        val id = pending.add(address, normalized, text, System.currentTimeMillis(), newestRowIds[normalized] ?: 0)
        submit(id, address, text)
    }

    private fun submit(id: String, address: String, text: String) {
        try {
            val parts = radio.divide(text)
            val sentIntents = parts.map { sentIntent(id) }
            pending.submitted(id, parts.size)
            radio.send(address, text, parts, sentIntents)
        } catch (e: Exception) {
            pending.failed(id)
            throw e
        } finally {
            pendingChanges.tryEmit(Unit)
        }
    }

    /**
     * Re-attempt a failed SMS. One that never reached the provider is sent
     * again as itself. A failed row in the provider is sent again as a new
     * message: Photon can't delete or update the old row without being the
     * default SMS app, so the chat shows both until the failed one rolls out
     * of the limit window.
     */
    fun retryMessage(messageId: String) {
        pending.retry(messageId)?.let { (address, text) ->
            submit(messageId, address, text)
            return
        }
        val id = messageId.toLongOrNull() ?: return
        var address: String? = null
        var body: String? = null
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
            "${Telephony.Sms._ID} = ?",
            arrayOf(id.toString()),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                address = c.getString(0)
                body = c.getString(1)
            }
        }
        val addr = address ?: return
        val text = body ?: return
        sendMessage(addr, text)
    }

    // ─── Send results ───────────────────────────────────────────────

    private val requestCodes = AtomicInteger()

    private val sendResults = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Sent or failed, Android has stored it in the provider by now.
            onSendResult(intent.getStringExtra(EXTRA_SEND_ID) ?: return)
        }
    }

    private var hearingResults = false

    /** Start hearing the radio report each part sent. Until then sends stay "sending". */
    @Synchronized
    fun startSendResults() {
        if (hearingResults) return
        context.registerReceiver(sendResults, IntentFilter(ACTION_SENT), Context.RECEIVER_NOT_EXPORTED)
        hearingResults = true
    }

    @Synchronized
    fun stopSendResults() {
        if (!hearingResults) return
        context.unregisterReceiver(sendResults)
        hearingResults = false
    }

    internal fun onSendResult(id: String) {
        if (pending.partSent(id)) pendingChanges.tryEmit(Unit)
    }

    // One per part, each its own PendingIntent (extras don't tell them apart).
    private fun sentIntent(id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCodes.incrementAndGet(),
        Intent(ACTION_SENT).setPackage(context.packageName).putExtra(EXTRA_SEND_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT,
    )

    // ─── Reading the provider ───────────────────────────────────────

    private fun loadConversations(): List<Conversation> {
        val conversations = mutableMapOf<String, Conversation>()
        val threads = mutableMapOf<String, MutableSet<Long>>()

        val cursor = resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.DATE,
                Telephony.Sms.READ,
                Telephony.Sms.TYPE,
                Telephony.Sms.THREAD_ID,
            ),
            null, null,
            "${Telephony.Sms.DATE} DESC",
        ) ?: return emptyList()

        cursor.use { c ->
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val iRead = c.getColumnIndexOrThrow(Telephony.Sms.READ)
            val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val iThread = c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)

            while (c.moveToNext()) {
                val rawAddress = c.getString(iAddr) ?: continue
                val address = normalizeAddress(rawAddress)
                if (address.isEmpty()) continue
                threads.getOrPut(address) { mutableSetOf() } += c.getLong(iThread)
                val date = c.getLong(iDate)
                val read = c.getInt(iRead) == 1
                val type = c.getInt(iType)
                val isInbox = type == Telephony.Sms.MESSAGE_TYPE_INBOX
                val lastRead = lastReadMillis(address)
                // Count as unread only if the provider hasn't marked it read, it was
                // received (not sent by us), and the user hasn't opened the chat in
                // Photon since the message arrived.
                val unread = isInbox && !read && date > lastRead
                if (address !in conversations) {
                    conversations[address] = Conversation(
                        jid = address,
                        name = displayName(address),
                        isGroup = false,
                        lastMessageId = null,
                        lastTimestamp = date / 1000,
                        unreadCount = if (unread) 1 else 0,
                        isMuted = false,
                        avatarUrl = null,
                        updatedAt = date / 1000,
                    )
                } else if (unread) {
                    val existing = conversations[address]!!
                    conversations[address] = existing.copy(
                        unreadCount = existing.unreadCount + 1,
                    )
                }
            }
        }

        threadIds = threads
        return conversations.values.sortedByDescending { it.lastTimestamp }
    }

    /** The conversation's newest messages: what the provider has, and sends it doesn't have yet. */
    private fun loadMessages(address: String): List<Message> {
        val normalized = normalizeAddress(address)
        val stored = storedMessages(normalized)
        stored.maxOfOrNull(PendingSends::rowId)?.let { newest -> newestRowIds.merge(normalized, newest, ::maxOf) }
        // A send sorts above a stored row from the same second: it's newer.
        return (pending.messages(normalized, stored) + stored)
            .sortedByDescending { it.timestamp }
            .take(MESSAGE_LIMIT)
    }

    private fun storedMessages(normalized: String): List<Message> {
        val cached = threadIds[normalized]
        val rows = cached?.let { readThreads(normalized, it) }
        if (!rows.isNullOrEmpty()) return rows
        // Not scanned yet, or the thread was deleted and begun again in
        // another app: find its threads afresh.
        val threads = scanThreadIds()[normalized] ?: return emptyList()
        return if (threads == cached) rows.orEmpty() else readThreads(normalized, threads)
    }

    /** Every conversation's threads, without reading any message. */
    private fun scanThreadIds(): Map<String, Set<Long>> {
        val threads = mutableMapOf<String, MutableSet<Long>>()
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.THREAD_ID),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(0)?.let(::normalizeAddress)?.takeIf { it.isNotEmpty() } ?: continue
                threads.getOrPut(address) { mutableSetOf() } += c.getLong(1)
            }
        }
        threadIds = threads
        return threads
    }

    private fun readThreads(normalized: String, threads: Set<Long>): List<Message> {
        val messages = mutableListOf<Message>()

        val cursor = resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ,
            ),
            "${Telephony.Sms.THREAD_ID} IN (${threads.joinToString(",") { "?" }})",
            threads.map { it.toString() }.toTypedArray(),
            "${Telephony.Sms.DATE} DESC",
        ) ?: return emptyList()

        val lastRead = lastReadMillis(normalized)
        cursor.use { c ->
            val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val iRead = c.getColumnIndexOrThrow(Telephony.Sms.READ)

            while (c.moveToNext()) {
                // A thread can hold more than one spelling of a number.
                val rawAddress = c.getString(iAddr) ?: continue
                if (normalizeAddress(rawAddress) != normalized) continue

                val id = c.getLong(iId).toString()
                val body = c.getString(iBody) ?: ""
                val date = c.getLong(iDate)
                val type = c.getInt(iType)
                // Anything not INBOX is something we sent (SENT / OUTBOX /
                // FAILED / QUEUED / DRAFT all live on the outgoing side).
                // Previously only SENT counted as `isFromMe`, so a queued
                // or failed message showed up as if the OTHER party sent it.
                val isFromMe = type != Telephony.Sms.MESSAGE_TYPE_INBOX
                val status = smsStatus(type, read = c.getInt(iRead) == 1, isNewerThanLastRead = date > lastRead)

                messages.add(Message(
                    id = id,
                    conversationJid = normalized,
                    senderJid = if (isFromMe) "me" else rawAddress,
                    timestamp = date / 1000,
                    contentType = "text",
                    textBody = body,
                    mediaUrl = null,
                    mediaMime = null,
                    mediaSize = null,
                    thumbnailPath = null,
                    stickerPackId = null,
                    replyToId = null,
                    editVersion = 0,
                    isFromMe = isFromMe,
                    status = status,
                ))

                if (messages.size >= MESSAGE_LIMIT) break
            }
        }

        return messages
    }

    /**
     * An alphanumeric sender ("HSBC UK") as sent; a number by its LP3 contact,
     * else formatted. SMS has no other name sources.
     */
    private fun displayName(address: String): String =
        if (DisplayNames.phoneOf(address, Platform.SMS) == null) {
            address
        } else {
            DisplayNames.person(Platform.SMS, lp3 = localContacts.nameFor(address), phone = address)
        }

    private fun normalizeAddress(address: String): String {
        // Only normalize if it looks like a phone number (starts with + or digit)
        // Keep alphanumeric sender IDs (e.g., "HSBC UK", "Specsavers") as-is
        return if (address.firstOrNull()?.let { it == '+' || it.isDigit() } == true) {
            address.replace(Regex("[^+\\d]"), "")
        } else {
            address.trim()
        }
    }

    private companion object {
        const val TAG = "SmsRepository"
        const val MESSAGE_LIMIT = 50
        const val ACTION_SENT = "app.photon.sms.SENT"
        const val EXTRA_SEND_ID = "app.photon.sms.SEND_ID"
    }
}

/**
 * An SMS row's [MessageStatus]. Anything not in the inbox is your own:
 * sent, failed, or still in the outbox or queue. A received message is
 * unread until it's marked read in the provider or the chat was opened in
 * Photon after it arrived, as the chat list counts it.
 */
internal fun smsStatus(type: Int, read: Boolean, isNewerThanLastRead: Boolean): MessageStatus = when (type) {
    Telephony.Sms.MESSAGE_TYPE_INBOX ->
        if (!read && isNewerThanLastRead) MessageStatus.UNREAD else MessageStatus.READ
    Telephony.Sms.MESSAGE_TYPE_FAILED -> MessageStatus.FAILED
    Telephony.Sms.MESSAGE_TYPE_OUTBOX,
    Telephony.Sms.MESSAGE_TYPE_QUEUED,
    Telephony.Sms.MESSAGE_TYPE_DRAFT -> MessageStatus.SENDING
    else -> MessageStatus.SENT
}
