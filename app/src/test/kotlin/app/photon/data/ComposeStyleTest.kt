package app.photon.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeStyleTest {

    @Test
    fun defaultsToInlineWhenNothingOrSomethingUnknownIsStored() {
        assertEquals(ComposeStyle.INLINE, ComposeStyle.resolve(null))
        assertEquals(ComposeStyle.INLINE, ComposeStyle.resolve("SIDEWAYS"))
        assertEquals(ComposeStyle.FULL_SCREEN, ComposeStyle.resolve("FULL_SCREEN"))
    }

    @Test
    fun cyclesBetweenTheTwoStyles() {
        assertEquals(ComposeStyle.FULL_SCREEN, ComposeStyle.INLINE.next())
        assertEquals(ComposeStyle.INLINE, ComposeStyle.FULL_SCREEN.next())
        assertEquals("FULL SCREEN", ComposeStyle.FULL_SCREEN.label)
    }

    @Test
    fun storesTheChosenStyle() = runBlocking {
        val prefs = PhotonPreferences(ApplicationProvider.getApplicationContext())
        assertEquals(ComposeStyle.INLINE, prefs.composeStyle.first())
        prefs.setComposeStyle(ComposeStyle.FULL_SCREEN)
        assertEquals(ComposeStyle.FULL_SCREEN, prefs.composeStyle.first())
        prefs.setComposeStyle(ComposeStyle.INLINE)
        assertEquals(ComposeStyle.INLINE, prefs.composeStyle.first())
    }
}
