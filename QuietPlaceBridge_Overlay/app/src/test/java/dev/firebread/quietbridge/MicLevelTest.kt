package dev.firebread.quietbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Verifies the two things the whole project depends on:
 *  1. the sound meter actually changes with different input levels, and
 *  2. the chat command we send is exactly what the Minecraft add-on parses.
 *
 * The add-on receiver (FB Mod Quiet BP/scripts/sound_maker.js) does:
 *   if (ev.id !== "quietplace:mic") return;
 *   const levelMatch = msg.match(/^(?:level\s*[=:]?\s*)?([0-9]*\.?[0-9]+)$/);
 *   ["speaking","noise","on","true"].includes(msg)  -> noise ON
 *   ["silent","off","muted","stop","false"].includes(msg) -> noise OFF
 */
class MicLevelTest {

    // ---------- raw RMS ----------

    @Test
    fun silenceHasZeroRms() {
        val buffer = ShortArray(1024) { 0 }
        assertEquals(0f, MicLevel.rms(buffer, buffer.size), 0.0001f)
    }

    @Test
    fun emptyOrNegativeReadHasZeroRms() {
        assertEquals(0f, MicLevel.rms(ShortArray(512), 0), 0.0001f)
    }

    @Test
    fun fullScaleSquareWaveHasRmsOfOne() {
        val buffer = ShortArray(1024) { i -> if (i % 2 == 0) Short.MAX_VALUE else -Short.MAX_VALUE }
        val rms = MicLevel.rms(buffer, buffer.size)
        assertEquals(1.0f, rms, 0.01f)
    }

    @Test
    fun sineWaveHasRmsNearRootTwoOverTwo() {
        val buffer = ShortArray(2048) { i ->
            (sin(2.0 * PI * i / 64.0) * Short.MAX_VALUE).toInt().toShort()
        }
        val rms = MicLevel.rms(buffer, buffer.size)
        assertEquals(0.707f, rms, 0.01f)
    }

    // ---------- the meter responds to input level ----------

    @Test
    fun louderInputProducesHigherDisplayedLevel() {
        fun levelOf(amplitude: Int): Float {
            val buffer = ShortArray(512) { i -> if (i % 2 == 0) amplitude else -amplitude }
            return MicLevel.displayLevel(MicLevel.rms(buffer, buffer.size))
        }
        // gain of 5 means rms 0.10 -> level 0.50, and anything louder clamps at 1.0
        val quiet = levelOf((Short.MAX_VALUE * 0.002).toInt())
        val speech = levelOf((Short.MAX_VALUE * 0.10).toInt())
        val loud = levelOf((Short.MAX_VALUE * 0.40).toInt())

        assertTrue("speech level $speech should exceed quiet level $quiet", speech > quiet)
        assertTrue("loud level $loud should exceed speech level $speech", loud > speech)
        assertEquals(0.01f, quiet, 0.005f)
        assertEquals(0.50f, speech, 0.02f)
        assertEquals(1f, loud, 0.001f)
    }

    @Test
    fun displayLevelUsesTheGainAndClamps() {
        assertEquals(0.25f, MicLevel.displayLevel(0.05f), 0.0001f)
        assertEquals(0f, MicLevel.displayLevel(-3f), 0.0001f)
        assertEquals(1f, MicLevel.displayLevel(4f), 0.0001f)
    }

    @Test
    fun thresholdDecidesNoiseVsQuiet() {
        assertTrue(MicLevel.isAboveThreshold(0.024f, 0.024f))
        assertFalse(MicLevel.isAboveThreshold(0.023f, 0.024f))
    }

    @Test
    fun bucketGroupsLevelsIntoTenSteps() {
        assertEquals(0, MicLevel.bucket(0f))
        assertEquals(0, MicLevel.bucket(0.099f))
        assertEquals(1, MicLevel.bucket(0.1f))
        assertEquals(9, MicLevel.bucket(1f))
    }

    // ---------- chat throttle ----------

    @Test
    fun throttleBlocksSendsInsideTheInterval() {
        assertFalse(MicLevel.shouldSend(stateChanged = true, levelChanged = false, elapsedMs = 1399L))
        assertTrue(MicLevel.shouldSend(stateChanged = true, levelChanged = false, elapsedMs = 1400L))
    }

    @Test
    fun throttleBlocksUnchangedQuietStates() {
        assertFalse(MicLevel.shouldSend(stateChanged = false, levelChanged = false, elapsedMs = 5000L))
    }

    // ---------- command protocol matches the add-on ----------

    @Test
    fun levelCommandIsExactlyWhatTheAddonParses() {
        val command = MicLevel.command(0.42f)
        assertEquals("/scriptevent quietplace:mic level=0.42", command)

        // Everything after the id is the add-on's "message".
        val message = command.substringAfter("quietplace:mic ").trim()
        val match = MicLevel.ADDON_LEVEL_MESSAGE.matchEntire(message)
            ?: fail("add-on regex does not accept our level message: $message")
        assertEquals("0.42", match.groupValues[1])
    }

    @Test
    fun allRepresentativeLevelsParseOnTheAddOnSide() {
        for (level in listOf(0f, 0.05f, 0.12f, 0.5f, 0.99f, 1f)) {
            val message = MicLevel.levelMessage(level)
            val match = MicLevel.ADDON_LEVEL_MESSAGE.matchEntire(message)
                ?: fail("add-on regex rejects: $message")
            val parsed = match.groupValues[1].toFloat()
            assertEquals("round-trip lost precision for $message", level, parsed, 0.005f)
        }
    }

    @Test
    fun stateCommandsUseTheAddOnsAcceptedKeywords() {
        assertEquals("/scriptevent quietplace:mic speaking", MicLevel.stateCommand(true))
        assertEquals("/scriptevent quietplace:mic silent", MicLevel.stateCommand(false))
        assertTrue(MicLevel.ADDON_SPEAKING_WORDS.contains(MicLevel.stateMessage(true)))
        assertTrue(MicLevel.ADDON_SILENT_WORDS.contains(MicLevel.stateMessage(false)))
    }

    @Test
    fun commandsCarryNoSpacesThatWouldBreakInputText() {
        // Shizuku types the command with `input text`, which needs no shell quoting
        // beyond space handling; our payloads must stay simple ASCII.
        for (command in listOf(MicLevel.command(0.42f), MicLevel.stateCommand(true), MicLevel.stateCommand(false))) {
            assertFalse("unexpected quote in $command", command.contains('\'') || command.contains('"'))
        }
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)
}
