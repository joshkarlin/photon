package app.photon.data.repository

import android.util.Log
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.Reaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * Shared 500ms DB-polling flows used by both ChatRepository (WhatsApp) and
 * SignalRepository. One implementation so the FBE-unlock resilience below is
 * fixed in one place.
 */
private const val POLL_MS = 500L

/**
 * Hot chat-list flow. The value survives navigation so screens never see a
 * transient null after first load; polling stops a few seconds after the last
 * subscriber leaves (battery: no standing queries with the screen off).
 *
 * Resilience rules, learned the hard way:
 *  - Exceptions are swallowed (the SQLite file can be briefly inaccessible
 *    during FBE unlock after the screen turns on); the flow re-emits the last
 *    good value and retries next tick.
 *  - An empty read when we previously had data is suspicious — a legit Reset
 *    looks the same as a transient WAL-checkpoint/FBE glitch, so the cached
 *    value is held for a few ticks before the empty is believed.
 */
fun pollingConversationsFlow(
    scope: CoroutineScope,
    tag: String,
    load: () -> List<Conversation>,
): StateFlow<List<Conversation>?> = flow<List<Conversation>?> {
    var last: List<Conversation>? = null
    var consecutiveErrors = 0
    var consecutiveEmpty = 0
    while (true) {
        try {
            val current = load()
            if (consecutiveErrors > 0) {
                Log.i(tag, "DB recovered after $consecutiveErrors errors; got ${current.size} convs")
                consecutiveErrors = 0
            }
            if (current.isEmpty() && !last.isNullOrEmpty()) {
                consecutiveEmpty++
                if (consecutiveEmpty < 5) {
                    Log.w(tag, "Suspect-empty #$consecutiveEmpty (last had ${last!!.size}); reusing cache")
                    emit(last!!)
                } else {
                    Log.i(tag, "Empty confirmed after $consecutiveEmpty ticks; accepting")
                    last = current
                    emit(current)
                }
            } else {
                consecutiveEmpty = 0
                last = current
                emit(current)
            }
        } catch (e: Throwable) {
            consecutiveErrors++
            if (consecutiveErrors == 1 || consecutiveErrors % 20 == 0) {
                Log.w(tag, "DB read error #$consecutiveErrors (last had ${last?.size} convs): ${e.message}")
            }
            last?.let { emit(it) }
        }
        delay(POLL_MS)
    }
}.flowOn(Dispatchers.IO)
    .distinctUntilChanged()
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

/**
 * Per-conversation message flow with reaction + reply-preview enrichment.
 * The reply cache is scoped to the collector: quoted messages don't change
 * content, so caching by reply key avoids the N+1 lookup every poll tick.
 */
fun pollingMessagesFlow(
    load: () -> List<Message>,
    loadReactions: (List<String>) -> Map<String, List<Reaction>>,
    lookupReply: (String) -> Message?,
): Flow<List<Message>> = flow {
    val replyCache = HashMap<String, Message?>()
    while (true) {
        try {
            val messages = load()
            val reactions = loadReactions(messages.map { it.id })
            val enriched = messages.map { msg ->
                val preview = msg.replyToId?.let { key ->
                    replyCache.getOrPut(key) { lookupReply(key) }
                }
                msg.copy(
                    reactions = reactions[msg.id] ?: emptyList(),
                    replyToPreview = preview,
                )
            }
            emit(enriched)
        } catch (_: Throwable) {
            // Transient DB error — keep the flow alive and retry next tick.
        }
        delay(POLL_MS)
    }
}.flowOn(Dispatchers.IO).distinctUntilChanged()
