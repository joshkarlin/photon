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

/** The channels PhotonApp creates for the Messages and Calls levels. */
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
            for (channel in listOf(NotificationHelper.channelFor(level), NotificationHelper.callChannelFor(level))) {
                if (level == NotificationLevel.OFF) {
                    assertNull(channel)
                } else {
                    assertNotNull(manager.getNotificationChannel(channel), "$level posts to $channel")
                }
            }
        }
    }

    /** Calls have their own channels, every one heads-up so a ringing call is seen. */
    @Test
    fun callsHaveTheirOwnChannelPerLevel() {
        val sound = manager.getNotificationChannel("photon_calls")
        assertEquals("Calls", sound.name)
        assertNotNull(sound.sound)
        assertTrue(sound.shouldVibrate())

        val vibrate = manager.getNotificationChannel("photon_calls_vibrate")
        assertEquals("Calls (vibrate)", vibrate.name)
        assertNull(vibrate.sound)
        assertTrue(vibrate.shouldVibrate())

        val silent = manager.getNotificationChannel("photon_calls_silent")
        assertEquals("Calls (silent)", silent.name)
        assertNull(silent.sound)
        assertFalse(silent.shouldVibrate())

        listOf(sound, vibrate, silent).forEach {
            assertEquals(NotificationManager.IMPORTANCE_HIGH, it.importance, it.id)
        }
        assertEquals(
            mapOf(
                NotificationLevel.SOUND to "photon_calls",
                NotificationLevel.VIBRATE to "photon_calls_vibrate",
                NotificationLevel.SILENT to "photon_calls_silent",
            ),
            NotificationLevel.entries.filter { it != NotificationLevel.OFF }.associateWith { NotificationHelper.callChannelFor(it) },
        )
    }
}
