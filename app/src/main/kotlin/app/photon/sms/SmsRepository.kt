package app.photon.sms

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

class SmsRepository(private val context: Context, scope: CoroutineScope) {

    private val resolver: ContentResolver = context.contentResolver
    private val contactCache = mutableMapOf<String, String>()

    // Tracks when the user last opened each SMS conversation in Photon.
    // Used to compute unread state without being the default SMS app
    // (the provider's `read` column can only be written by the default app).
    private val readPrefs = context.getSharedPreferences("sms_read_state", Context.MODE_PRIVATE)

    private fun lastReadMillis(address: String): Long =
        readPrefs.getLong(normalizeAddress(address), 0L)

    /**
     * Drop the cached phone→name lookups and nudge the SMS provider so the
     * conversations flow re-emits with refreshed contact names. Called after
     * Photon's in-app contact editor writes to ContactsContract — without it
     * the cached `+447...` would persist until the next SMS write.
     */
    fun refreshContactNames() {
        contactCache.clear()
        resolver.notifyChange(Telephony.Sms.CONTENT_URI, null)
    }

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
            Log.d("SmsRepository", "Could not mark provider read (not default SMS app): ${e.message}")
        }

        // Nudge observers so conversation list refreshes immediately.
        resolver.notifyChange(Telephony.Sms.CONTENT_URI, null)
    }

    val conversations: StateFlow<List<Conversation>?> = callbackFlow<List<Conversation>?> {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(loadConversations())
            }
        }
        resolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        send(loadConversations())
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.flowOn(Dispatchers.IO)
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun messages(address: String): Flow<List<Message>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(loadMessages(address))
            }
        }
        resolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        send(loadMessages(address))
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    fun getConversation(address: String): Conversation? {
        return loadConversations().find { it.jid == normalizeAddress(address) }
    }

    fun sendMessage(address: String, text: String) {
        val smsManager = context.getSystemService(SmsManager::class.java)
        val parts = smsManager.divideMessage(text)
        if (parts.size == 1) {
            smsManager.sendTextMessage(address, null, text, null, null)
        } else {
            smsManager.sendMultipartTextMessage(address, null, parts, null, null)
        }
    }

    /**
     * Re-attempt a failed SMS. Looks up the original row in the provider by
     * _id, grabs its address + body, and re-submits via SmsManager. The
     * original failed row stays in the provider (we can't delete it without
     * being the default SMS app), so the chat will briefly show both the
     * failed and the new outbox/sent row until the failed one rolls out of
     * the limit window. Acceptable trade-off vs. silent failure.
     */
    fun retryMessage(messageId: String) {
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

    private fun loadConversations(): List<Conversation> {
        val conversations = mutableMapOf<String, Conversation>()

        val cursor = resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ,
                Telephony.Sms.TYPE,
            ),
            null, null,
            "${Telephony.Sms.DATE} DESC",
        ) ?: return emptyList()

        cursor.use { c ->
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val iRead = c.getColumnIndexOrThrow(Telephony.Sms.READ)

            val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)

            while (c.moveToNext()) {
                val rawAddress = c.getString(iAddr) ?: continue
                val address = normalizeAddress(rawAddress)
                if (address.isEmpty()) continue
                val body = c.getString(iBody) ?: ""
                val date = c.getLong(iDate)
                val read = c.getInt(iRead) == 1
                val type = c.getInt(iType)
                val isInbox = type == Telephony.Sms.MESSAGE_TYPE_INBOX
                val lastRead = lastReadMillis(address)
                // Count as unread only if the provider hasn't marked it read, it was
                // received (not sent by us), and the user hasn't opened the chat in
                // Photon since the message arrived.
                val unread = isInbox && !read && date > lastRead
                val isAlphanumeric = address.firstOrNull()?.isDigit() != true && address.firstOrNull() != '+'

                if (address !in conversations) {
                    conversations[address] = Conversation(
                        jid = address,
                        name = if (isAlphanumeric) address else resolveContactName(rawAddress),
                        isGroup = false,
                        lastMessageId = null,
                        lastTimestamp = date / 1000,
                        unreadCount = if (unread) 1 else 0,
                        isMuted = false,
                        avatarUrl = null,
                        updatedAt = date / 1000,
                        lastMessagePreview = body,
                    )
                } else if (unread) {
                    val existing = conversations[address]!!
                    conversations[address] = existing.copy(
                        unreadCount = existing.unreadCount + 1,
                    )
                }
            }
        }

        return conversations.values.sortedByDescending { it.lastTimestamp }
    }

    private fun loadMessages(address: String): List<Message> {
        val normalized = normalizeAddress(address)
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
            null, null,
            "${Telephony.Sms.DATE} DESC",
        ) ?: return emptyList()

        cursor.use { c ->
            val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)

            while (c.moveToNext()) {
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
                val status = when (type) {
                    Telephony.Sms.MESSAGE_TYPE_INBOX -> "received"
                    Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
                    Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
                    Telephony.Sms.MESSAGE_TYPE_OUTBOX,
                    Telephony.Sms.MESSAGE_TYPE_QUEUED,
                    Telephony.Sms.MESSAGE_TYPE_DRAFT -> "sending"
                    else -> "sent"
                }

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

                if (messages.size >= 50) break
            }
        }

        return messages
    }

    private fun resolveContactName(phoneNumber: String): String? {
        contactCache[phoneNumber]?.let { return it }

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber),
        )
        val cursor = resolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null, null, null,
        )
        val name = cursor?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        if (name != null) contactCache[phoneNumber] = name
        return name
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
}
