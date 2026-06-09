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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn

/**
 * Event-driven DB-refresh flows shared by ChatRepository (WhatsApp) and
 * SignalRepository. A re-query runs on each change signal — conflated, so a
 * burst of events (history sync) collapses into one read — plus a slow
 * fallback tick so a missed event means a briefly-stale list rather than a
 * permanently-stale one. Like the 500ms poll this replaces, everything stops
 * a few seconds after the last subscriber leaves.
 */
private const val FALLBACK_REFRESH_MS = 45_000L

private fun triggers(changes: Flow<Unit>): Flow<Unit> = merge(
    changes,
    flow { while (true) { emit(Unit); delay(FALLBACK_REFRESH_MS) } },
).conflate()

/**
 * Hot chat-list flow. The value survives navigation so screens never see a
 * transient null after first load.
 *
 * Resilience rules, learned the hard way:
 *  - Exceptions are swallowed (the SQLite file can be briefly inaccessible
 *    during FBE unlock after the screen turns on); the flow re-emits the last
 *    good value and retries on the next trigger.
 *  - An empty read when we previously had data is suspicious — a legit Reset
 *    looks the same as a transient WAL-checkpoint/FBE glitch, so it's
 *    re-checked for a few seconds before the empty is believed.
 */
fun conversationsFlow(
    scope: CoroutineScope,
    tag: String,
    changes: Flow<Unit>,
    load: () -> List<Conversation>,
): StateFlow<List<Conversation>?> = flow<List<Conversation>?> {
    var last: List<Conversation>? = null
    var consecutiveErrors = 0
    triggers(changes).collect {
        try {
            var current = load()
            if (consecutiveErrors > 0) {
                Log.i(tag, "DB recovered after $consecutiveErrors errors; got ${current.size} convs")
                consecutiveErrors = 0
            }
            if (current.isEmpty() && !last.isNullOrEmpty()) {
                for (attempt in 1..4) {
                    Log.w(tag, "Suspect-empty re-check #$attempt (last had ${last!!.size})")
                    delay(1_000)
                    current = load()
                    if (current.isNotEmpty()) break
                }
            }
            last = current
            emit(current)
        } catch (e: Throwable) {
            consecutiveErrors++
            if (consecutiveErrors == 1 || consecutiveErrors % 20 == 0) {
                Log.w(tag, "DB read error #$consecutiveErrors (last had ${last?.size} convs): ${e.message}")
            }
            last?.let { emit(it) }
        }
    }
}.flowOn(Dispatchers.IO)
    .distinctUntilChanged()
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

/**
 * Per-conversation message flow with reaction + reply-preview enrichment.
 * The reply cache is scoped to the collector: quoted messages don't change
 * content, so caching by reply key avoids the N+1 lookup on every refresh.
 */
fun messagesFlow(
    changes: Flow<Unit>,
    load: () -> List<Message>,
    loadReactions: (List<String>) -> Map<String, List<Reaction>>,
    lookupReply: (String) -> Message?,
): Flow<List<Message>> = flow {
    val replyCache = HashMap<String, Message?>()
    triggers(changes).collect {
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
            // Transient DB error — keep the flow alive and retry on the
            // next trigger.
        }
    }
}.flowOn(Dispatchers.IO).distinctUntilChanged()
