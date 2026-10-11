package app.photon.chat

import androidx.compose.runtime.Composable
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.Platform
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * A platform in memory: set [conversations] and [messages], read what was
 * asked of it. It keeps the interface's empty header unless given [header].
 */
internal class FakeChatBackend(
    override val platform: Platform,
    override val capabilities: ChatCapabilities = FULL,
    private val phone: String? = null,
) : ChatBackend {
    override val conversations = MutableStateFlow<List<Conversation>?>(null)
    val messages = MutableStateFlow<List<Message>>(emptyList())
    val marked = mutableListOf<List<String>>()
    val opened = mutableListOf<Pair<String, Boolean>>()

    override fun conversation(jid: String) = conversations.value?.firstOrNull { it.jid == jid }
    override fun messages(jid: String): Flow<List<Message>> = messages
    override fun participantNames(jid: String): Flow<Map<String, String>> = flowOf(emptyMap())
    override suspend fun sendText(jid: String, text: String, replyToId: String?) {}
    override suspend fun sendMedia(jid: String, path: String, mime: String, replyToId: String?) {}
    override suspend fun react(jid: String, messageId: String, senderJid: String, emoji: String) {}
    override suspend fun retry(messageId: String) {}
    override suspend fun delete(jid: String, messageId: String, forEveryone: Boolean) {}

    override suspend fun markRead(jid: String, incomingIds: List<String>) {
        marked += incomingIds
    }

    override suspend fun downloadMedia(messageId: String): String = throw ChatBackendException("none")

    override suspend fun onOpen(jid: String, isGroup: Boolean) {
        opened += jid to isGroup
    }

    override fun contactPhone(jid: String) = phone

    /** This one with a header, as Signal's safety-number notice. */
    fun withHeader(header: String): ChatBackend = object : ChatBackend by this {
        @Composable
        override fun Header(jid: String, isGroup: Boolean, title: String, participantNames: Map<String, String>) {
            LightText(header, variant = LightTextVariant.Detail)
        }
    }

    companion object {
        val FULL = ChatCapabilities(
            reply = true, media = true, audio = true, reactions = true,
            delete = true, deleteForEveryone = true, contactEditing = true,
        )
        val TEXT_ONLY = FULL.copy(
            reply = false, media = false, audio = false, reactions = false, delete = false, deleteForEveryone = false,
        )

        fun conversation(jid: String, name: String, lastTimestamp: Long = 0) =
            Conversation(jid, name, isGroup = false, null, lastTimestamp, 0, false, null, lastTimestamp)
    }
}
