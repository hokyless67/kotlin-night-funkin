package nightfunkin.audio

import kotlin.math.min

/**
 * Freeplay's partial instrumental preview, from
 * `FreeplayState.playCurSongPreview`.
 *
 * FNF loads only the [previewStart]..[previewEnd] slice of the instrumental,
 * fades in from 0 to 0.7 over two seconds, fades out over the last two seconds,
 * then reloads and plays it again. We keep the same timings but reuse the
 * already-decoded sound and restart it at the preview start instead of paying
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
        if (!player.load(path)) return false
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

        val position = player.positionMs
        if (position >= endMs) {
            restart()
            return
        }

        val elapsed = (position - startMs).coerceAtLeast(0.0)
        val remaining = (endMs - position).coerceAtLeast(0.0)
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
    }
}
