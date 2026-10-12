package app.photon.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@VisibleForTesting
internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore("photon_prefs")

/**
 * How notifications alert: Settings › Chat › Messages and Calls each pick
 * one. Each level but [OFF] posts to its own channel (`PhotonApp`), because a
 * channel's sound, vibration and importance are fixed once it exists.
 *
 * - [SOUND]: heads-up, with the default sound and vibration. A ringing call
 *   repeats them until it ends.
 * - [VIBRATE]: heads-up and vibration, no sound. A ringing call repeats the
 *   vibration until it ends.
 * - [SILENT]: no sound or vibration. Messages show in the shade and on the
 *   badge only; calls still show heads-up.
 * - [OFF]: nothing is posted.
 */
enum class NotificationLevel(val label: String) {
    SOUND("SOUND"),
    VIBRATE("VIBRATE"),
    SILENT("SILENT"),
    OFF("OFF");

    fun next(): NotificationLevel = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = SOUND

        /**
         * Resolves the stored setting. Before the levels, NOTIFICATIONS was
         * ON / OFF: ON becomes [SOUND] (what it did) and OFF stays [OFF].
         */
        fun resolve(stored: String?, legacyEnabled: Boolean?): NotificationLevel =
            parse(stored) ?: if (legacyEnabled == false) OFF else DEFAULT

        /**
         * Resolves the stored Calls setting. Calls had no setting of their
         * own before; they start at the Messages level they followed.
         */
        fun resolveCalls(stored: String?, messages: NotificationLevel): NotificationLevel =
            parse(stored) ?: messages

        private fun parse(name: String?): NotificationLevel? = entries.firstOrNull { it.name == name }
    }
}

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

/**
 * How messages are written in a chat. [INLINE] keeps a text field under the
 * thread; [FULL_SCREEN] follows LightOS Messages: the thread has a bottom bar
 * (attach, voice note, compose) and compose opens a full-screen editor with
 * the LP3 keyboard.
 */
enum class ComposeStyle(val label: String) {
    INLINE("INLINE"),
    FULL_SCREEN("FULL SCREEN");

    fun next(): ComposeStyle = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = INLINE

        fun resolve(stored: String?): ComposeStyle = entries.firstOrNull { it.name == stored } ?: DEFAULT
    }
}

/**
 * Which keyboard writes messages. [SYSTEM] is the phone's input method (e.g.
 * Gboard); [LIGHT] is the LP3 keyboard from the Light SDK, drawn by Photon,
 * so the system keyboard never shows.
 */
enum class KeyboardStyle(val label: String) {
    SYSTEM("SYSTEM"),
    LIGHT("LIGHT");

    fun next(): KeyboardStyle = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = SYSTEM

        fun resolve(stored: String?): KeyboardStyle = entries.firstOrNull { it.name == stored } ?: DEFAULT
    }
}

class PhotonPreferences(private val context: Context) {

    private val maxMessagesKey = intPreferencesKey("max_messages")
    private val maxDaysKey = intPreferencesKey("max_days")
    private val showThumbnailsKey = booleanPreferencesKey("show_thumbnails")
    // Messages keeps the key from when it was the one NOTIFICATIONS setting.
    private val messageLevelKey = stringPreferencesKey("notification_level")
    private val callLevelKey = stringPreferencesKey("call_level")
    private val scrollDialKey = stringPreferencesKey("scroll_dial")
    private val composeStyleKey = stringPreferencesKey("compose_style")
    private val keyboardStyleKey = stringPreferencesKey("keyboard_style")
    // Superseded by scroll_dial; read for migration, removed on the next write.
    private val legacyChatScrollSpeedKey = stringPreferencesKey("chat_scroll_speed")
    private val legacyMenuScrollSpeedKey = stringPreferencesKey("menu_scroll_speed")
    // Superseded by notification_level; read for migration, removed on the next write.
    private val legacyNotificationsKey = booleanPreferencesKey("notifications_enabled")

    val maxMessages: Flow<Int> = context.dataStore.data.map { it[maxMessagesKey] ?: 50 }
    val maxDays: Flow<Int> = context.dataStore.data.map { it[maxDaysKey] ?: 7 }
    val showThumbnails: Flow<Boolean> = context.dataStore.data.map { it[showThumbnailsKey] ?: true }
    /** Settings › Chat › Messages: how message notifications alert. */
    val messageLevel: Flow<NotificationLevel> = context.dataStore.data.map(::messageLevelOf)

    /** Settings › Chat › Calls: how ringing, missed and group-call notifications alert. */
    val callLevel: Flow<NotificationLevel> = context.dataStore.data.map {
        NotificationLevel.resolveCalls(it[callLevelKey], messageLevelOf(it))
    }
    val scrollDial: Flow<ScrollDial> = context.dataStore.data.map {
        ScrollDial.resolve(it[scrollDialKey], it[legacyChatScrollSpeedKey], it[legacyMenuScrollSpeedKey])
    }
    val composeStyle: Flow<ComposeStyle> = context.dataStore.data.map { ComposeStyle.resolve(it[composeStyleKey]) }
    val keyboardStyle: Flow<KeyboardStyle> = context.dataStore.data.map { KeyboardStyle.resolve(it[keyboardStyleKey]) }

    suspend fun setMaxMessages(value: Int) {
        context.dataStore.edit { it[maxMessagesKey] = value }
    }

    suspend fun setMaxDays(value: Int) {
        context.dataStore.edit { it[maxDaysKey] = value }
    }

    suspend fun setShowThumbnails(value: Boolean) {
        context.dataStore.edit { it[showThumbnailsKey] = value }
    }

    /**
     * Calls start at the Messages level until they're set, so changing
     * Messages first stores that starting level for Calls: it mustn't move
     * with Messages from then on.
     */
    suspend fun setMessageLevel(value: NotificationLevel) {
        context.dataStore.edit {
            if (it[callLevelKey] == null) it[callLevelKey] = messageLevelOf(it).name
            it[messageLevelKey] = value.name
            it.remove(legacyNotificationsKey)
        }
    }

    suspend fun setCallLevel(value: NotificationLevel) {
        context.dataStore.edit { it[callLevelKey] = value.name }
    }

    private fun messageLevelOf(prefs: Preferences): NotificationLevel =
        NotificationLevel.resolve(prefs[messageLevelKey], prefs[legacyNotificationsKey])

    suspend fun setScrollDial(value: ScrollDial) {
        context.dataStore.edit {
            it[scrollDialKey] = value.name
            it.remove(legacyChatScrollSpeedKey)
            it.remove(legacyMenuScrollSpeedKey)
        }
    }

    suspend fun setComposeStyle(value: ComposeStyle) {
        context.dataStore.edit { it[composeStyleKey] = value.name }
    }

    suspend fun setKeyboardStyle(value: KeyboardStyle) {
        context.dataStore.edit { it[keyboardStyleKey] = value.name }
    }
}
