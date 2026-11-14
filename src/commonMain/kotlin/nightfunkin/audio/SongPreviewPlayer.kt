package nightfunkin.audio

import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.time.TimeSource

/**
 * Freeplay's partial instrumental preview, from
 * `FreeplayState.playCurSongPreview`.
 *
 * FNF loads only the [previewStart]..[previewEnd] slice of the instrumental,
 * fades in from 0 to 0.7 over two seconds, fades out over the last two seconds,
 * then reloads and plays it again. We keep the same timings but reuse the
 * already-loaded sound and restart it at the preview start instead of paying
 * the reload cost on every loop.
 */
class SongPreviewPlayer {

    private val player = MusicPlayer()

    private var active = false
    private var targetVolume = 0.0
    private var startMs = 0.0
    private var endMs = 0.0

    suspend fun play(
        path: String,
        previewStart: Double,
        previewEnd: Double,
        volume: Double = PREVIEW_VOLUME,
        isCurrent: () -> Boolean = { true },
    ): Boolean {
        stop()
        // `FreeplayState.FADE_IN_DELAY` waits a quarter of a second after the
        // capsule is selected before the preview starts, so scrolling through
        // the list does not fire one load per step. Reading and opening the
        // track takes most of that time already, so the clock starts before the
        // load instead of after it: the preview still waits the full delay, but
        // the delay covers the file work instead of being added to it.
        val requestedAt = TimeSource.Monotonic.markNow()
        if (!player.load(path)) return false
        val waitMs = START_DELAY_MS - requestedAt.elapsedNow().inWholeMilliseconds
        if (waitMs > 0L) delay(waitMs)
        // Loading is asynchronous; the selection may have moved on while the
        // file was decoding. Never start a preview that is no longer current.
        if (!isCurrent()) {
            stop()
            return false
        }

        val lengthMs = player.totalMs
        if (lengthMs <= 0.0) {
            println("KNF: preview has no duration: $path")
            return false
        }

        val (startFraction, endFraction) = normalizePreview(previewStart, previewEnd)
        startMs = lengthMs * startFraction
        endMs = lengthMs * endFraction
        if (endMs <= startMs) return false

        targetVolume = volume
        active = true
        restart()
        return true
    }

    /** Must run every frame while the preview is visible. */
    fun update() {
        if (!active) return

        // The loop point follows the producer: it is where the track's data
        // actually ends, and it is the position the fade-out below refers to,
        // so the fade reaches zero exactly when the preview is cut.
        val generated = player.positionMs
        if (generated >= endMs) {
            restart()
            return
        }

        // The fade-in follows the listener instead. A stream's decoder jumps
        // forward by a whole prefetch buffer right after `play()`, so reading
        // the producer's position here made the gain snap from silence to a
        // large value instead of ramping (Freeplay read `FlxG.sound.music.time`,
        // which is the position the device has played).
        val heard = player.playedPositionMs
        val elapsed = (heard - startMs).coerceAtLeast(0.0)
        val remaining = (endMs - generated).coerceAtLeast(0.0)
        var gain = 1.0
        if (elapsed < FADE_DURATION_MS) gain = min(gain, elapsed / FADE_DURATION_MS)
        if (remaining < FADE_DURATION_MS) gain = min(gain, remaining / FADE_DURATION_MS)
        player.volume = targetVolume * gain
    }

    fun stop() {
        active = false
        player.stop()
        player.volume = 0.0
    }

    fun dispose() {
        stop()
        player.dispose()
    }

    private fun restart() {
        // FlxSound.fadeIn(2, 0, previewVolume): start silent, then ramp up.
        player.volume = 0.0
        player.play(startMs)
    }

    private fun normalizePreview(previewStart: Double, previewEnd: Double): Pair<Double, Double> {
        var start = previewStart
        var end = previewEnd

        // `FreeplaySongData.get_previewStartTime` / `get_previewEndTime`.
        if (start >= end || start > 1.0) start = DEFAULT_PREVIEW_START
        if (start >= end || end > 1.0) end = DEFAULT_PREVIEW_END
        if (start < 0.0) start = DEFAULT_PREVIEW_START
        if (end <= start) {
            start = DEFAULT_PREVIEW_START
            end = DEFAULT_PREVIEW_END
        }
        return start to end
    }

    private companion object {
        const val PREVIEW_VOLUME = 0.7
        const val FADE_DURATION_MS = 2_000.0
        const val DEFAULT_PREVIEW_START = 0.0
        const val DEFAULT_PREVIEW_END = 0.2

        /** `FreeplayState.FADE_IN_DELAY` (0.25 s), in milliseconds. */
        const val START_DELAY_MS = 250L
    }
}
