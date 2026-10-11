package app.photon.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The COMPOSE and KEYBOARD settings in Settings › Chat. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InputPreferencesTest {

    @Test
    fun composeDefaultsToInlineWhenNothingOrSomethingUnknownIsStored() {
        assertEquals(ComposeStyle.INLINE, ComposeStyle.resolve(null))
        assertEquals(ComposeStyle.INLINE, ComposeStyle.resolve("SIDEWAYS"))
        assertEquals(ComposeStyle.FULL_SCREEN, ComposeStyle.resolve("FULL_SCREEN"))
    }

    @Test
    fun composeCyclesBetweenTheTwoStyles() {
        assertEquals(ComposeStyle.FULL_SCREEN, ComposeStyle.INLINE.next())
        assertEquals(ComposeStyle.INLINE, ComposeStyle.FULL_SCREEN.next())
        assertEquals("FULL SCREEN", ComposeStyle.FULL_SCREEN.label)
    }

    @Test
    fun keyboardDefaultsToSystemWhenNothingOrSomethingUnknownIsStored() {
        assertEquals(KeyboardStyle.SYSTEM, KeyboardStyle.resolve(null))
        assertEquals(KeyboardStyle.SYSTEM, KeyboardStyle.resolve("DVORAK"))
        assertEquals(KeyboardStyle.LIGHT, KeyboardStyle.resolve("LIGHT"))
    }

    @Test
    fun keyboardCyclesBetweenSystemAndLight() {
        assertEquals(KeyboardStyle.LIGHT, KeyboardStyle.SYSTEM.next())
        assertEquals(KeyboardStyle.SYSTEM, KeyboardStyle.LIGHT.next())
    }

    @Test
    fun storesBothSettingsIndependently() = runBlocking {
        val prefs = PhotonPreferences(ApplicationProvider.getApplicationContext())
        assertEquals(ComposeStyle.INLINE, prefs.composeStyle.first())
        assertEquals(KeyboardStyle.SYSTEM, prefs.keyboardStyle.first())

        prefs.setKeyboardStyle(KeyboardStyle.LIGHT)
        assertEquals(KeyboardStyle.LIGHT, prefs.keyboardStyle.first())
        assertEquals(ComposeStyle.INLINE, prefs.composeStyle.first())

        prefs.setComposeStyle(ComposeStyle.FULL_SCREEN)
        assertEquals(ComposeStyle.FULL_SCREEN, prefs.composeStyle.first())
        assertEquals(KeyboardStyle.LIGHT, prefs.keyboardStyle.first())

        prefs.setKeyboardStyle(KeyboardStyle.SYSTEM)
        prefs.setComposeStyle(ComposeStyle.INLINE)
        assertEquals(KeyboardStyle.SYSTEM, prefs.keyboardStyle.first())
        assertEquals(ComposeStyle.INLINE, prefs.composeStyle.first())
    }
}
