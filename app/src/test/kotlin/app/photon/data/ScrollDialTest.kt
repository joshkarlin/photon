package app.photon.data

import app.photon.ui.shared.ScrollDialEvent
import app.photon.ui.shared.listDialTarget
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrollDialTest {

    @Test
    fun defaultsToMediumWhenNothingIsStored() {
        assertEquals(ScrollDial.MEDIUM, ScrollDial.resolve(null, null, null))
    }

    @Test
    fun storedSettingWinsOverLegacySpeeds() {
        assertEquals(ScrollDial.OFF, ScrollDial.resolve("OFF", "FAST", "FAST"))
        assertEquals(ScrollDial.SLOW, ScrollDial.resolve("SLOW", "FAST", null))
    }

    @Test
    fun migratesLegacyChatSpeedBeforeMenuSpeed() {
        assertEquals(ScrollDial.FAST, ScrollDial.resolve(null, "FAST", "SLOW"))
        assertEquals(ScrollDial.SLOW, ScrollDial.resolve(null, "SLOW", null))
        // Only a menu speed was ever changed.
        assertEquals(ScrollDial.FAST, ScrollDial.resolve(null, null, "FAST"))
    }

    @Test
    fun ignoresUnknownValues() {
        assertEquals(ScrollDial.MEDIUM, ScrollDial.resolve("TURBO", "BOGUS", null))
        assertEquals(ScrollDial.SLOW, ScrollDial.resolve("", null, "SLOW"))
    }

    @Test
    fun onlyOffHandsTheDialBackToTheSystem() {
        assertFalse(ScrollDial.OFF.consumesDial)
        assertTrue(ScrollDial.entries.filter { it != ScrollDial.OFF }.all { it.consumesDial })
    }

    @Test
    fun settingCyclesThroughEveryOption() {
        assertEquals(
            listOf(ScrollDial.SLOW, ScrollDial.MEDIUM, ScrollDial.FAST, ScrollDial.OFF),
            generateSequence(ScrollDial.OFF.next()) { it.next() }.take(4).toList(),
        )
    }

    @Test
    fun listsStepOneTwoOrThreeRowsPerDetent() {
        assertEquals(6, listDialTarget(5, ScrollDialEvent(1, ScrollDial.SLOW)))
        assertEquals(7, listDialTarget(5, ScrollDialEvent(1, ScrollDial.MEDIUM)))
        assertEquals(2, listDialTarget(5, ScrollDialEvent(-1, ScrollDial.FAST)))
        assertEquals(0, listDialTarget(1, ScrollDialEvent(-1, ScrollDial.FAST)))
    }

    @Test
    fun chatScrollGrowsWithSpeed() {
        val lines = ScrollDial.entries.map { it.chatLines }
        assertEquals(lines.sorted(), lines)
        assertEquals(1f, ScrollDial.MEDIUM.chatLines)
    }
}
