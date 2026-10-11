package app.photon.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Settings › Chat › NOTIFICATIONS, and its migration from the old ON / OFF switch. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class NotificationLevelTest {

    @Test
    fun theOldSwitchBecomesSoundOrOff() {
        assertEquals(NotificationLevel.SOUND, NotificationLevel.resolve(null, legacyEnabled = true))
        assertEquals(NotificationLevel.OFF, NotificationLevel.resolve(null, legacyEnabled = false))
        assertEquals(NotificationLevel.SOUND, NotificationLevel.resolve(null, legacyEnabled = null))
    }

    @Test
    fun aStoredLevelWinsOverTheOldSwitch() {
        assertEquals(NotificationLevel.SILENT, NotificationLevel.resolve("SILENT", legacyEnabled = true))
        assertEquals(NotificationLevel.VIBRATE, NotificationLevel.resolve("VIBRATE", legacyEnabled = false))
        // Something unknown falls back as if nothing were stored.
        assertEquals(NotificationLevel.OFF, NotificationLevel.resolve("LOUD", legacyEnabled = false))
    }

    @Test
    fun settingCyclesSoundVibrateSilentOff() {
        assertEquals(
            listOf("VIBRATE", "SILENT", "OFF", "SOUND"),
            generateSequence(NotificationLevel.SOUND.next()) { it.next() }.take(4).map { it.label }.toList(),
        )
    }

    @Test
    fun migratesTheStoredSwitchAndDropsItOnTheNextWrite() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PhotonPreferences(context)
        // The key older builds wrote.
        val legacy = booleanPreferencesKey("notifications_enabled")
        try {
            context.dataStore.edit { it[legacy] = false }
            assertEquals(NotificationLevel.OFF, prefs.notificationLevel.first())

            context.dataStore.edit { it[legacy] = true }
            assertEquals(NotificationLevel.SOUND, prefs.notificationLevel.first())

            prefs.setNotificationLevel(NotificationLevel.VIBRATE)
            assertEquals(NotificationLevel.VIBRATE, prefs.notificationLevel.first())
            assertNull(context.dataStore.data.first()[legacy])
        } finally {
            context.dataStore.edit {
                it.remove(legacy)
                it.remove(stringPreferencesKey("notification_level"))
            }
        }
    }
}
