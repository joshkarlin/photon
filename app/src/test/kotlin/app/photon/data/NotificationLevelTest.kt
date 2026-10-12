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

/**
 * Settings › Chat › Messages and Calls: Messages' migration from the old
 * ON / OFF switch, and Calls starting from Messages.
 */
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
            assertEquals(NotificationLevel.OFF, prefs.messageLevel.first())

            context.dataStore.edit { it[legacy] = true }
            assertEquals(NotificationLevel.SOUND, prefs.messageLevel.first())

            prefs.setMessageLevel(NotificationLevel.VIBRATE)
            assertEquals(NotificationLevel.VIBRATE, prefs.messageLevel.first())
            assertNull(context.dataStore.data.first()[legacy])
        } finally {
            clear(context)
        }
    }

    @Test
    fun callsStartAtTheStoredMessagesLevel() {
        assertEquals(NotificationLevel.SILENT, NotificationLevel.resolveCalls(null, NotificationLevel.SILENT))
        assertEquals(NotificationLevel.OFF, NotificationLevel.resolveCalls(null, NotificationLevel.OFF))
        assertEquals(NotificationLevel.VIBRATE, NotificationLevel.resolveCalls("VIBRATE", NotificationLevel.OFF))
        assertEquals(NotificationLevel.SOUND, NotificationLevel.resolveCalls("LOUD", NotificationLevel.SOUND))
    }

    /** An install from before Calls existed: Calls is what NOTIFICATIONS was, old switch included. */
    @Test
    fun callsMigrateFromWhatNotificationsWas() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PhotonPreferences(context)
        try {
            context.dataStore.edit { it[stringPreferencesKey("notification_level")] = "SILENT" }
            assertEquals(NotificationLevel.SILENT, prefs.callLevel.first())

            context.dataStore.edit {
                it.remove(stringPreferencesKey("notification_level"))
                it[booleanPreferencesKey("notifications_enabled")] = false
            }
            assertEquals(NotificationLevel.OFF, prefs.callLevel.first())
        } finally {
            clear(context)
        }
    }

    /** Once Messages changes, Calls keeps the level it started from instead of following. */
    @Test
    fun changingMessagesLeavesCallsWhereTheyStarted() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PhotonPreferences(context)
        try {
            context.dataStore.edit { it[stringPreferencesKey("notification_level")] = "VIBRATE" }
            prefs.setMessageLevel(NotificationLevel.OFF)
            assertEquals(NotificationLevel.OFF, prefs.messageLevel.first())
            assertEquals(NotificationLevel.VIBRATE, prefs.callLevel.first())

            prefs.setCallLevel(NotificationLevel.SOUND)
            prefs.setMessageLevel(NotificationLevel.SILENT)
            assertEquals(NotificationLevel.SOUND, prefs.callLevel.first())
            assertEquals(NotificationLevel.SILENT, prefs.messageLevel.first())
        } finally {
            clear(context)
        }
    }

    private suspend fun clear(context: Context) {
        context.dataStore.edit {
            it.remove(booleanPreferencesKey("notifications_enabled"))
            it.remove(stringPreferencesKey("notification_level"))
            it.remove(stringPreferencesKey("call_level"))
        }
    }
}
