package app.photon.ui.settings

import org.junit.Test
import kotlin.test.assertEquals

/** Settings values read as LightOS writes them, whatever case their enums use. */
class SentenceCaseTest {
    @Test
    fun capitalsBecomeSentenceCase() {
        assertEquals("Full screen", sentenceCase("FULL SCREEN"))
        assertEquals("Sound", sentenceCase("SOUND"))
        assertEquals("Off", sentenceCase("OFF"))
    }
}
