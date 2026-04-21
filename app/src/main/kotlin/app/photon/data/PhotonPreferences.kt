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

enum class MessageLayout(val label: String) {
    TERMINAL("TERMINAL"),
    CLEAN("CLEAN"),
    TRANSCRIPT("TRANSCRIPT");

    fun next(): MessageLayout = entries[(ordinal + 1) % entries.size]
}

enum class VoiceInputMode(val label: String) {
    SPLIT("SPLIT"),
    COMBINED("COMBINED");

    fun next(): VoiceInputMode = entries[(ordinal + 1) % entries.size]
}

enum class ScrollSpeed(val label: String, val dp: Int) {
    SLOW("SLOW", 12),
    MEDIUM("MEDIUM", 20),
    FAST("FAST", 32);

    fun next(): ScrollSpeed = entries[(ordinal + 1) % entries.size]
}

enum class ListScrollSpeed(val label: String, val items: Int) {
    SLOW("1 ITEM", 1),
    MEDIUM("2 ITEMS", 2),
    FAST("3 ITEMS", 3);

    fun next(): ListScrollSpeed = entries[(ordinal + 1) % entries.size]
}

class PhotonPreferences(private val context: Context) {

    private val maxMessagesKey = intPreferencesKey("max_messages")
    private val maxDaysKey = intPreferencesKey("max_days")
    private val showThumbnailsKey = booleanPreferencesKey("show_thumbnails")
    private val dmLayoutKey = stringPreferencesKey("dm_layout")
    private val groupLayoutKey = stringPreferencesKey("group_layout")
    private val voiceInputModeKey = stringPreferencesKey("voice_input_mode")
    private val notificationsKey = booleanPreferencesKey("notifications_enabled")
    private val chatScrollSpeedKey = stringPreferencesKey("chat_scroll_speed")
    private val menuScrollSpeedKey = stringPreferencesKey("menu_scroll_speed")

    val maxMessages: Flow<Int> = context.dataStore.data.map { it[maxMessagesKey] ?: 50 }
    val maxDays: Flow<Int> = context.dataStore.data.map { it[maxDaysKey] ?: 7 }
    val showThumbnails: Flow<Boolean> = context.dataStore.data.map { it[showThumbnailsKey] ?: true }
    val dmLayout: Flow<MessageLayout> = context.dataStore.data.map {
        try { MessageLayout.valueOf(it[dmLayoutKey] ?: "TERMINAL") } catch (_: Exception) { MessageLayout.TERMINAL }
    }
    val groupLayout: Flow<MessageLayout> = context.dataStore.data.map {
        try { MessageLayout.valueOf(it[groupLayoutKey] ?: "TRANSCRIPT") } catch (_: Exception) { MessageLayout.TRANSCRIPT }
    }
    val voiceInputMode: Flow<VoiceInputMode> = context.dataStore.data.map {
        try { VoiceInputMode.valueOf(it[voiceInputModeKey] ?: "SPLIT") } catch (_: Exception) { VoiceInputMode.SPLIT }
    }
    val notificationsEnabled: Flow<Boolean> = context.dataStore.data.map { it[notificationsKey] ?: true }
    val chatScrollSpeed: Flow<ScrollSpeed> = context.dataStore.data.map {
        try { ScrollSpeed.valueOf(it[chatScrollSpeedKey] ?: "MEDIUM") } catch (_: Exception) { ScrollSpeed.MEDIUM }
    }
    val menuScrollSpeed: Flow<ListScrollSpeed> = context.dataStore.data.map {
        try { ListScrollSpeed.valueOf(it[menuScrollSpeedKey] ?: "SLOW") } catch (_: Exception) { ListScrollSpeed.SLOW }
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

    suspend fun setDmLayout(value: MessageLayout) {
        context.dataStore.edit { it[dmLayoutKey] = value.name }
    }

    suspend fun setGroupLayout(value: MessageLayout) {
        context.dataStore.edit { it[groupLayoutKey] = value.name }
    }

    suspend fun setVoiceInputMode(value: VoiceInputMode) {
        context.dataStore.edit { it[voiceInputModeKey] = value.name }
    }

    suspend fun setNotificationsEnabled(value: Boolean) {
        context.dataStore.edit { it[notificationsKey] = value }
    }

    suspend fun setChatScrollSpeed(value: ScrollSpeed) {
        context.dataStore.edit { it[chatScrollSpeedKey] = value.name }
    }

    suspend fun setMenuScrollSpeed(value: ListScrollSpeed) {
        context.dataStore.edit { it[menuScrollSpeedKey] = value.name }
    }

    fun getNotificationsEnabledSync(): Boolean {
        return runBlocking { context.dataStore.data.map { it[notificationsKey] ?: true }.first() }
    }
}
