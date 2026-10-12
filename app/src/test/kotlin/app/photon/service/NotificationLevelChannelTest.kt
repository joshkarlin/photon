package app.photon.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.photon.data.NotificationLevel
import app.photon.data.PhotonPreferences
import app.photon.data.model.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Messages post to the Messages level's channel and calls to the Calls
 * level's, and nothing is posted at OFF. A ringing call keeps alerting at
 * SOUND and VIBRATE. Replacing, corrections and reconcile work whichever
 * channel a notification went to.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: PhotonApp would also follow the stored levels into the
// shared cache, racing the levels each test sets.
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class NotificationLevelChannelTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val levels = MutableStateFlow(NotificationLevel.SOUND)
    private val callLevels = MutableStateFlow(NotificationLevel.SOUND)

    // Unconfined: a new level reaches the helper before the assignment returns.
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val alice = "alice@s.whatsapp.net"
    private val bob = "bob-aci"

    @Before
    fun followLevels() {
        NotificationHelper.followLevels(levels, callLevels, scope)
    }

    @After
    fun stopFollowing() {
        scope.cancel()
    }

    private fun posted(): List<Notification> =
        shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    private val Notification.alertsOnlyOnce get() = flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
    private val Notification.insistent get() = flags and Notification.FLAG_INSISTENT != 0

    private fun ringing() = posted().single { it.category == Notification.CATEGORY_CALL }

    private fun message(text: String, update: Boolean = false) =
        NotificationHelper.showMessageNotification(context, "Alice", text, Platform.WHATSAPP, alice, update)

    /** One of each kind: a message from Alice, Bob ringing, and a missed call from Alice. */
    private fun postEveryKind() {
        message("hi")
        NotificationHelper.showIncomingCall(context, "Bob", Platform.SIGNAL, bob, isVideo = false)
        NotificationHelper.showMissedCall(context, "Alice", Platform.WHATSAPP, alice, isVideo = true)
    }

    @Test
    fun eachLevelPostsToItsOwnChannels() {
        val expected = mapOf(
            NotificationLevel.SOUND to ("photon_messages" to "photon_calls"),
            NotificationLevel.VIBRATE to ("photon_messages_vibrate" to "photon_calls_vibrate"),
            NotificationLevel.SILENT to ("photon_messages_silent" to "photon_calls_silent"),
        )
        for ((level, channels) in expected) {
            levels.value = level
            callLevels.value = level
            postEveryKind()
            // Each kind replaces its own notification from the level before.
            assertEquals(3, posted().size, "at $level")
            assertEquals(
                mapOf(
                    Notification.CATEGORY_MESSAGE to channels.first,
                    Notification.CATEGORY_CALL to channels.second,
                    Notification.CATEGORY_MISSED_CALL to channels.second,
                ),
                posted().associate { it.category to it.channelId },
                "at $level",
            )
        }
    }

    @Test
    fun offPostsNothing() {
        levels.value = NotificationLevel.OFF
        callLevels.value = NotificationLevel.OFF
        postEveryKind()
        NotificationHelper.showGroupCallStarted(context, "Climbing", "Alice", Platform.SIGNAL, "group")
        assertTrue(posted().isEmpty())
    }

    /** Messages off, calls on vibrate: no ping for messages, but a call still comes through. */
    @Test
    fun callsComeThroughWithMessagesOff() {
        levels.value = NotificationLevel.OFF
        callLevels.value = NotificationLevel.VIBRATE
        postEveryKind()
        assertEquals(
            mapOf(
                Notification.CATEGORY_CALL to "photon_calls_vibrate",
                Notification.CATEGORY_MISSED_CALL to "photon_calls_vibrate",
            ),
            posted().associate { it.category to it.channelId },
        )
    }

    @Test
    fun callsOffLeaveMessagesAlone() {
        callLevels.value = NotificationLevel.OFF
        postEveryKind()
        assertEquals(listOf(Notification.CATEGORY_MESSAGE), posted().map { it.category })
    }

    /** A ringing call keeps sounding or buzzing until it ends, at the levels that alert. */
    @Test
    fun ringingIsInsistentOnlyAtSoundOrVibrate() {
        for (level in NotificationLevel.entries.filter { it != NotificationLevel.OFF }) {
            callLevels.value = level
            NotificationHelper.showIncomingCall(context, "Bob", Platform.SIGNAL, bob, isVideo = false)
            val ringing = ringing()
            assertEquals(level != NotificationLevel.SILENT, ringing.insistent, "at $level")
            assertEquals(60_000L, ringing.timeoutAfter, "at $level")
            // Swiping it away still silences it.
            assertEquals(0, ringing.flags and Notification.FLAG_ONGOING_EVENT, "at $level")
        }
        NotificationHelper.cancelIncomingCall(context, bob)
        assertTrue(posted().isEmpty(), "ending the call takes the ringing away")
    }

    @Test
    fun missedCallsAlertOnce() {
        NotificationHelper.showIncomingCall(context, "Bob", Platform.SIGNAL, bob, isVideo = false)
        NotificationHelper.showMissedCall(context, "Bob", Platform.SIGNAL, bob, isVideo = false)
        val missed = posted().single()
        assertEquals(Notification.CATEGORY_MISSED_CALL, missed.category)
        assertFalse(missed.insistent)
    }

    /** A Signal group call starting is a call: it follows Calls, alerts once, and clears when read. */
    @Test
    fun aGroupCallStartingFollowsCallsWithoutRinging() {
        val group = "group-id"
        levels.value = NotificationLevel.OFF
        callLevels.value = NotificationLevel.SOUND
        NotificationHelper.showGroupCallStarted(context, "Climbing", "Alice", Platform.SIGNAL, group)

        val started = posted().single()
        assertEquals("photon_calls", started.channelId)
        assertEquals(Notification.CATEGORY_CALL, started.category)
        assertFalse(started.insistent)
        assertEquals("Climbing · Signal", started.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Alice started a group call", started.extras.getString(Notification.EXTRA_TEXT))

        // It sits beside the chat's message notification rather than replacing it.
        levels.value = NotificationLevel.SOUND
        NotificationHelper.showMessageNotification(context, "Climbing", "Alice: join us", Platform.SIGNAL, group)
        assertEquals(2, posted().size)

        NotificationHelper.reconcile(context, mapOf(group to 0))
        assertTrue(posted().isEmpty())
    }

    @Test
    fun aGroupCallInTheChatOnScreenIsNotPosted() {
        NotificationHelper.setVisibleConversation("group-id")
        try {
            NotificationHelper.showGroupCallStarted(context, "Climbing", "Alice", Platform.SIGNAL, "group-id")
            assertTrue(posted().isEmpty())
        } finally {
            NotificationHelper.clearVisibleConversation("group-id")
        }
    }

    @Test
    fun aCorrectionStaysQuietOnTheNewLevelsChannel() {
        message("first")
        levels.value = NotificationLevel.SILENT
        message("second", update = true)

        val corrected = posted().single()
        assertEquals("photon_messages_silent", corrected.channelId)
        assertTrue(corrected.alertsOnlyOnce)
        assertEquals("second", corrected.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun aCorrectionAtOffTakesTheNotificationAway() {
        message("first")
        levels.value = NotificationLevel.OFF
        message("second", update = true)
        assertTrue(posted().isEmpty(), "a correction must not leave the corrected text showing")
    }

    @Test
    fun reconcileClearsReadChatsOnEveryChannel() {
        message("hi")
        callLevels.value = NotificationLevel.VIBRATE
        NotificationHelper.showMissedCall(context, "Bob", Platform.SIGNAL, bob, isVideo = false)
        levels.value = NotificationLevel.SILENT
        NotificationHelper.showMessageNotification(context, "Bob", "hey", Platform.SIGNAL, bob)
        assertEquals(3, posted().size)

        NotificationHelper.reconcile(context, mapOf(alice to 0, bob to 0))
        assertTrue(posted().isEmpty())
    }

    @Test
    fun readsTheStoredSettingsWhenNothingFollowsThem() = runBlocking {
        scope.cancel()
        val prefs = PhotonPreferences(context)
        try {
            prefs.setMessageLevel(NotificationLevel.OFF)
            prefs.setCallLevel(NotificationLevel.SILENT)
            message("hi")
            assertTrue(posted().isEmpty())
            NotificationHelper.showIncomingCall(context, "Bob", Platform.SIGNAL, bob, isVideo = true)
            assertEquals("photon_calls_silent", posted().single().channelId)

            prefs.setMessageLevel(NotificationLevel.VIBRATE)
            message("hi")
            assertEquals("photon_messages_vibrate", posted().single { it.category == Notification.CATEGORY_MESSAGE }.channelId)
        } finally {
            prefs.setMessageLevel(NotificationLevel.DEFAULT)
            prefs.setCallLevel(NotificationLevel.DEFAULT)
        }
    }
}
