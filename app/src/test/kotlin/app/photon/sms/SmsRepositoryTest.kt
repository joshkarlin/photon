package app.photon.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.MessageStatus
import app.photon.data.names.LocalContacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Reading SMS by thread, surviving provider errors, and what a send shows until Android stores it. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SmsRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val contacts = LocalContacts(context) { null }
    private lateinit var provider: FakeSmsProvider
    private val radio = FakeRadio()

    private val mum = "+447700900123"
    private val dad = "+447700900999"

    @Before
    fun setUp() {
        provider = FakeSmsProvider.install()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun repo() = SmsRepository(context, scope, contacts, radio)

    /** Sends one part per message and records them. */
    private class FakeRadio(var refuse: Boolean = false) : SmsRadio {
        val sent = mutableListOf<Pair<String, String>>()
        val sentIntents = mutableListOf<PendingIntent>()

        override fun divide(text: String) = listOf(text)

        override fun send(address: String, text: String, parts: List<String>, sentIntents: List<PendingIntent>) {
            if (refuse) throw SecurityException("SEND_SMS not granted")
            sent += address to text
            this.sentIntents += sentIntents
        }
    }

    /** The first value of [flow] that [until] accepts, letting the provider's change notifications through. */
    private fun <T> await(flow: Flow<T>, until: (T) -> Boolean): T = runBlocking {
        withTimeout(5_000) {
            var found: T? = null
            while (found == null) {
                ShadowLooper.idleMainLooper()
                found = withTimeoutOrNull(100) { flow.first(until) }
            }
            found
        }
    }

    private fun bodies(messages: List<Message>) = messages.map { it.textBody }

    @Test
    fun aChatReadsOnlyItsOwnThreads() {
        provider.add(mum, "Dinner?", 3_000, Telephony.Sms.MESSAGE_TYPE_INBOX, thread = 1, read = false)
        provider.add(dad, "Lift?", 2_000, Telephony.Sms.MESSAGE_TYPE_INBOX, thread = 2)
        // The same number written another way, in a thread of its own.
        provider.add("+44 7700 900123", "Yes please", 4_000, Telephony.Sms.MESSAGE_TYPE_SENT, thread = 3)
        val repo = repo()
        await(repo.conversations.filterNotNull()) { it.size == 2 }

        val messages = await(repo.messages(mum)) { it.isNotEmpty() }

        assertEquals(listOf("Yes please", "Dinner?"), bodies(messages))
        assertEquals(listOf(MessageStatus.SENT, MessageStatus.UNREAD), messages.map { it.status })
        val threadQuery = provider.selections.last()
        assertNotNull(threadQuery)
        assertTrue(threadQuery.startsWith("thread_id IN"), "messages read by $threadQuery")
    }

    @Test
    fun aChatOpenedBeforeTheListFindsItsThreads() {
        provider.add(mum, "Dinner?", 3_000, Telephony.Sms.MESSAGE_TYPE_INBOX, thread = 1)
        val messages = await(repo().messages(mum)) { it.isNotEmpty() }
        assertEquals(listOf("Dinner?"), bodies(messages))
    }

    @Test
    fun theListKeepsItsLastValueWhenTheProviderFails() {
        provider.add(mum, "Dinner?", 3_000, Telephony.Sms.MESSAGE_TYPE_INBOX, thread = 1)
        val repo = repo()
        val first = await(repo.conversations.filterNotNull()) { it.isNotEmpty() }

        provider.denied = true
        provider.add(dad, "Lift?", 4_000, Telephony.Sms.MESSAGE_TYPE_INBOX, thread = 2)
        ShadowLooper.idleMainLooper()
        Thread.sleep(300)
        assertEquals(first, repo.conversations.value)

        provider.denied = false
        provider.add(dad, "Still need that lift", 5_000, Telephony.Sms.MESSAGE_TYPE_INBOX, thread = 2)
        val recovered: List<Conversation> = await(repo.conversations.filterNotNull()) { it.size == 2 }
        assertEquals(listOf(dad, mum), recovered.map { it.jid })
    }

    @Test
    fun aSendShowsAsSendingThenAsAndroidStoresIt() {
        provider.add(mum, "Dinner?", 3_000, Telephony.Sms.MESSAGE_TYPE_INBOX, thread = 1)
        val repo = repo()
        val messages = repo.messages(mum)
        await(messages) { it.isNotEmpty() }

        repo.sendMessage(mum, "On my way")

        val sending = await(messages) { it.size == 2 }.first()
        assertEquals("On my way", sending.textBody)
        assertEquals(MessageStatus.SENDING, sending.status)
        assertTrue(sending.isFromMe)
        assertEquals(listOf(mum to "On my way"), radio.sent)
        assertEquals(1, radio.sentIntents.size, "the radio's result comes back")

        // Android stores it, then reports it sent: one bubble throughout.
        provider.add(mum, "On my way", System.currentTimeMillis(), Telephony.Sms.MESSAGE_TYPE_SENT, thread = 1)
        val stored = await(messages) { list -> list.size == 2 && list.none { it.id == sending.id } }
        assertEquals(MessageStatus.SENT, stored.first().status)
        repo.onSendResult(sending.id)
        assertEquals(2, await(messages) { it.size == 2 }.size)
    }

    @Test
    fun aSendTheRadioFailsShowsAndroidsFailedRowWhichRetriesAsANewSend() {
        provider.add(mum, "Dinner?", 3_000, Telephony.Sms.MESSAGE_TYPE_INBOX, thread = 1)
        val repo = repo()
        val messages = repo.messages(mum)
        await(messages) { it.isNotEmpty() }
        repo.sendMessage(mum, "On my way")
        val sending = await(messages) { it.size == 2 }.first()

        // No service: Android stores it as failed, then reports the failure.
        val failedId = provider.add(mum, "On my way", System.currentTimeMillis(), Telephony.Sms.MESSAGE_TYPE_FAILED, thread = 1)
        repo.onSendResult(sending.id)
        val failed = await(messages) { it.first().id == failedId }.first()
        assertEquals(MessageStatus.FAILED, failed.status)

        repo.retryMessage(failedId)
        assertEquals(listOf(mum to "On my way", mum to "On my way"), radio.sent)
        val retried = await(messages) { it.size == 3 }
        assertEquals(MessageStatus.SENDING, retried.first().status)
    }

    @Test
    fun aSendSmsManagerRefusesIsFailedAtOnce() {
        provider.add(mum, "Dinner?", 3_000, Telephony.Sms.MESSAGE_TYPE_INBOX, thread = 1)
        val radio = FakeRadio(refuse = true)
        val repo = SmsRepository(context, scope, contacts, radio)
        val messages = repo.messages(mum)
        await(messages) { it.isNotEmpty() }

        assertFailsWith<SecurityException> { repo.sendMessage(mum, "On my way") }

        val failed = await(messages) { it.size == 2 }.first()
        assertEquals("On my way", failed.textBody)
        assertEquals(MessageStatus.FAILED, failed.status)

        // It never reached Android, so it's retried as itself.
        radio.refuse = false
        repo.retryMessage(failed.id)
        assertEquals(listOf(mum to "On my way"), radio.sent)
        val retried = await(messages) { it.first().status == MessageStatus.SENDING }.first()
        assertEquals(failed.id, retried.id)
    }

    @Test
    fun theSystemRadioAsksForEachPartsResult() {
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent("test"), PendingIntent.FLAG_IMMUTABLE,
        )
        SystemSmsRadio(context).send(mum, "On my way", listOf("On my way"), listOf(intent))
        val sent = shadowOf(context.getSystemService(SmsManager::class.java))
            .lastSentTextMessageParams
        assertEquals(mum, sent.destinationAddress)
        assertEquals(intent, sent.sentIntent)
    }
}
