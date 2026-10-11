package app.photon

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import app.photon.data.NotificationLevel
import app.photon.service.NotificationHelper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The channels PhotonApp creates for the NOTIFICATIONS levels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationChannelsTest {

    private val manager = ApplicationProvider.getApplicationContext<PhotonApp>()
        .getSystemService(NotificationManager::class.java)

    @Test
    fun soundKeepsTheOriginalChannel() {
        val sound = manager.getNotificationChannel("photon_messages")
        assertEquals("Messages", sound.name)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, sound.importance)
        assertNotNull(sound.sound)
        assertTrue(sound.shouldVibrate())
    }

    @Test
    fun vibrateIsHeadsUpWithoutSound() {
        val vibrate = manager.getNotificationChannel("photon_messages_vibrate")
        assertEquals("Messages (vibrate)", vibrate.name)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, vibrate.importance)
        assertNull(vibrate.sound)
        assertTrue(vibrate.shouldVibrate())
    }

    @Test
    fun silentOnlyShowsInTheShade() {
        val silent = manager.getNotificationChannel("photon_messages_silent")
        assertEquals("Messages (silent)", silent.name)
        assertEquals(NotificationManager.IMPORTANCE_LOW, silent.importance)
        assertNull(silent.sound)
        assertFalse(silent.shouldVibrate())
        assertTrue(silent.canShowBadge())
    }

    @Test
    fun everyLevelButOffHasAChannel() {
        for (level in NotificationLevel.entries) {
            val channel = NotificationHelper.channelFor(level)
            if (level == NotificationLevel.OFF) {
                assertNull(channel)
            } else {
                assertNotNull(manager.getNotificationChannel(channel), "$level posts to $channel")
            }
        }
    }
}
