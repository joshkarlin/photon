package app.photon.ui.shared

import com.thelightphone.sdk.ui.LightTopBarCenter
import org.junit.Test
import kotlin.test.assertEquals

/** The thread's title, and the timer line under it while messages disappear. */
class ChatTitleTest {

    @Test
    fun aTimerPutsALineUnderTheTitle() {
        val onClick = {}
        assertEquals(
            LightTopBarCenter.TwoLineDetail("Sam", "disappearing · 1 week", onClick = onClick),
            chatTitle("Sam", 7 * 24 * 3600L, onClick),
        )
        assertEquals(LightTopBarCenter.Text("Sam", onClick = null), chatTitle("Sam", 0, null))
    }
}
