package dev.firebread.quietbridge

import java.util.Locale
import kotlin.math.sqrt

/**
 * Pure microphone math and Minecraft command-protocol helpers.
 *
 * This object deliberately has NO Android imports so it can be unit tested on the
 * JVM (see MicLevelTest). The command strings produced here must stay in sync with
 * the game-side receiver in the add-on:
 *
 *   FB Mod Quiet BP/scripts/sound_maker.js
 *     system.afterEvents.scriptEventReceive.subscribe((ev) => {
 *       if (ev.id !== "quietplace:mic") return;
 *       const levelMatch = msg.match(/^(?:level\s*[=:]?\s*)?([0-9]*\.?[0-9]+)$/);
 *       // also accepts: "speaking" / "silent"
 *     });
 */
object MicLevel {

    /** Raw RMS is mic/hardware dependent; this gain maps typical speech onto 0..1. */
    const val DISPLAY_GAIN = 5.0f

    /**
     * Minimum gap between chat injections. Every command opens Minecraft chat, so
     * this caps how often the screen can be taken over (1.4s, same as before).
     */
    const val MIN_SEND_INTERVAL_MS = 1400L

    /** Distinct meter buckets (10% steps) used to avoid spamming identical levels. */
    const val BUCKETS = 10

    /** Same expression the add-on uses to parse the level payload. */
    val ADDON_LEVEL_MESSAGE = Regex("^(?:level\\s*[=:]\\s*)?([0-9]*\\.?[0-9]+)$")

    /** Keywords the add-on treats as explicit on/off state. */
    val ADDON_SPEAKING_WORDS = listOf("speaking", "noise", "on", "true")
    val ADDON_SILENT_WORDS = listOf("silent", "off", "muted", "stop", "false")

    /** RMS (root mean square) of the captured PCM samples, normalised to 0..1. */
    fun rms(buffer: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val v = buffer[i].toDouble() / 32768.0
            sum += v * v
        }
        return sqrt(sum / count).toFloat()
    }

    /** Map raw RMS onto the 0..1 level that the add-on expects. */
    fun displayLevel(rms: Float): Float = (rms * DISPLAY_GAIN).coerceIn(0f, 1f)

    /** True when the raw RMS is at or above the user's sensitivity threshold. */
    fun isAboveThreshold(rms: Float, threshold: Float): Boolean = rms >= threshold

    /** Bucket index used to skip sends while the level has not visibly changed. */
    fun bucket(level: Float): Int =
        (level.coerceIn(0f, 1f) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)

    /** True when a new chat injection is justified (change happened + throttle passed). */
    fun shouldSend(stateChanged: Boolean, levelChanged: Boolean, elapsedMs: Long): Boolean =
        (stateChanged || levelChanged) && elapsedMs >= MIN_SEND_INTERVAL_MS

    fun twoDecimals(value: Float): String =
        String.format(Locale.US, "%.2f", value.coerceIn(0f, 1f))

    /** Message body the add-on receives after the `quietplace:mic` id (level form). */
    fun levelMessage(level: Float): String = "level=" + twoDecimals(level)

    /** Message body for explicit state (form the add-on lists as speaking/silent). */
    fun stateMessage(speaking: Boolean): String = if (speaking) "speaking" else "silent"

    /** Full chat command that reports a sound level to the add-on. */
    fun command(level: Float): String = "/scriptevent quietplace:mic " + levelMessage(level)

    /** Full chat command that reports explicit on/off state to the add-on. */
    fun stateCommand(speaking: Boolean): String =
        "/scriptevent quietplace:mic " + stateMessage(speaking)
}
