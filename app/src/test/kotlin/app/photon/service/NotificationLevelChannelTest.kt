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
import kotlin.test.assertTrue

/**
 * Messages and calls post to the channel of the NOTIFICATIONS level, and
 * nothing is posted at OFF. Replacing, corrections and reconcile work
 * whichever channel a notification went to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class NotificationLevelChannelTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val levels = MutableStateFlow(NotificationLevel.SOUND)

    // Unconfined: a new level reaches the helper before the assignment returns.
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val alice = "alice@s.whatsapp.net"
    private val bob = "bob-aci"

    @Before
    fun followLevels() {
        NotificationHelper.followLevel(levels, scope)
    }

    @After
    fun stopFollowing() {
        scope.cancel()
    }

    private fun posted(): List<Notification> =
        shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    private val Notification.alertsOnlyOnce get() = flags and Notification.FLAG_ONLY_ALERT_ONCE != 0

    private fun message(text: String, update: Boolean = false) =
        NotificationHelper.showMessageNotification(context, "Alice", text, Platform.WHATSAPP, alice, update)

    /** One of each kind: a message from Alice, Bob ringing, and a missed call from Alice. */
    private fun postEveryKind() {
        message("hi")
        NotificationHelper.showIncomingCall(context, "Bob", Platform.SIGNAL, bob, isVideo = false)
        NotificationHelper.showMissedCall(context, "Alice", Platform.WHATSAPP, alice, isVideo = true)
    }

    @Test
    fun eachLevelPostsMessagesAndCallsToItsOwnChannel() {
        val expected = mapOf(
            NotificationLevel.SOUND to "photon_messages",
            NotificationLevel.VIBRATE to "photon_messages_vibrate",
            NotificationLevel.SILENT to "photon_messages_silent",
        )
        for ((level, channel) in expected) {
            levels.value = level
            postEveryKind()
            // Each kind replaces its own notification from the level before.
            assertEquals(3, posted().size, "at $level")
            assertEquals(
                mapOf(
                    Notification.CATEGORY_MESSAGE to channel,
                    Notification.CATEGORY_CALL to channel,
                    Notification.CATEGORY_MISSED_CALL to channel,
                ),
                posted().associate { it.category to it.channelId },
                "at $level",
            )
        }
    }

    @Test
    fun offPostsNothing() {
        levels.value = NotificationLevel.OFF
        postEveryKind()
        assertTrue(posted().isEmpty())
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
        levels.value = NotificationLevel.VIBRATE
        NotificationHelper.showMissedCall(context, "Bob", Platform.SIGNAL, bob, isVideo = false)
        levels.value = NotificationLevel.SILENT
        NotificationHelper.showMessageNotification(context, "Bob", "hey", Platform.SIGNAL, bob)
        assertEquals(3, posted().size)

        NotificationHelper.reconcile(context, mapOf(alice to 0, bob to 0))
        assertTrue(posted().isEmpty())
    }

    @Test
    fun readsTheStoredSettingWhenNothingFollowsIt() = runBlocking {
        scope.cancel()
        val prefs = PhotonPreferences(context)
        try {
            prefs.setNotificationLevel(NotificationLevel.OFF)
            message("hi")
            assertTrue(posted().isEmpty())

            prefs.setNotificationLevel(NotificationLevel.VIBRATE)
            message("hi")
            assertEquals("photon_messages_vibrate", posted().single().channelId)
        } finally {
            prefs.setNotificationLevel(NotificationLevel.DEFAULT)
        }
    }
}
