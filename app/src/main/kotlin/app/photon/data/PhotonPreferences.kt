package app.photon.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("photon_prefs")

/**
 * What the LP3 scroll dial does in Photon. LightOS uses the dial for screen
 * brightness by default, so [OFF] hands it back to the system.
 *
 * @param listItems list rows moved per detent
 * @param chatLines message-body line heights scrolled per detent
 */
enum class ScrollDial(val label: String, val listItems: Int, val chatLines: Float) {
    OFF("OFF", 0, 0f),
    SLOW("SLOW", 1, 0.5f),
    MEDIUM("MEDIUM", 2, 1f),
    FAST("FAST", 3, 1.5f);

    /** Whether Photon consumes dial key events (otherwise the system gets them). */
    val consumesDial: Boolean get() = this != OFF

    fun next(): ScrollDial = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = MEDIUM

        /**
         * Resolves the stored setting. Before the single setting existed,
         * chats and menus had separate SLOW/MEDIUM/FAST speeds; the chat speed
         * wins (the dial is mostly used in chats), then the menu speed.
         */
        fun resolve(stored: String?, legacyChatSpeed: String?, legacyMenuSpeed: String?): ScrollDial =
            parse(stored) ?: parse(legacyChatSpeed) ?: parse(legacyMenuSpeed) ?: DEFAULT

        private fun parse(name: String?): ScrollDial? = entries.firstOrNull { it.name == name }
    }
}

class PhotonPreferences(private val context: Context) {

    private val maxMessagesKey = intPreferencesKey("max_messages")
    private val maxDaysKey = intPreferencesKey("max_days")
    private val showThumbnailsKey = booleanPreferencesKey("show_thumbnails")
    private val notificationsKey = booleanPreferencesKey("notifications_enabled")
    private val scrollDialKey = stringPreferencesKey("scroll_dial")
    // Superseded by scroll_dial; read for migration, removed on the next write.
    private val legacyChatScrollSpeedKey = stringPreferencesKey("chat_scroll_speed")
    private val legacyMenuScrollSpeedKey = stringPreferencesKey("menu_scroll_speed")

    val maxMessages: Flow<Int> = context.dataStore.data.map { it[maxMessagesKey] ?: 50 }
    val maxDays: Flow<Int> = context.dataStore.data.map { it[maxDaysKey] ?: 7 }
    val showThumbnails: Flow<Boolean> = context.dataStore.data.map { it[showThumbnailsKey] ?: true }
    val notificationsEnabled: Flow<Boolean> = context.dataStore.data.map { it[notificationsKey] ?: true }
    val scrollDial: Flow<ScrollDial> = context.dataStore.data.map {
        ScrollDial.resolve(it[scrollDialKey], it[legacyChatScrollSpeedKey], it[legacyMenuScrollSpeedKey])
    }

    suspend fun setMaxMessages(value: Int) {
        context.dataStore.edit { it[maxMessagesKey] = value }
    }

    suspend fun setMaxDays(value: Int) {
        context.dataStore.edit { it[maxDaysKey] = value }
    }

    suspend fun setShowThumbnails(value: Boolean) {
        context.dataStore.edit { it[showThumbnailsKey] = value }
    }

    suspend fun setNotificationsEnabled(value: Boolean) {
        context.dataStore.edit { it[notificationsKey] = value }
    }

    suspend fun setScrollDial(value: ScrollDial) {
        context.dataStore.edit {
            it[scrollDialKey] = value.name
            it.remove(legacyChatScrollSpeedKey)
            it.remove(legacyMenuScrollSpeedKey)
        }
    }

    fun getNotificationsEnabledSync(): Boolean {
        return runBlocking { context.dataStore.data.map { it[notificationsKey] ?: true }.first() }
    }
}
