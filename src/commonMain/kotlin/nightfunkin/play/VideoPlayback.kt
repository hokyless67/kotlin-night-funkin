package nightfunkin.play

import korlibs.image.bitmap.Bitmap32
import korlibs.korge.view.Image
import korlibs.math.geom.Anchor2D
import korlibs.time.PerformanceCounter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nightfunkin.audio.MusicPlayer
import nightfunkin.core.Constants
import nightfunkin.core.KnfFiles
import nightfunkin.core.KnfFmt
import nightfunkin.platform.KnfEnv
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The platform-independent side of video playback: the display [Image] over
 * one reusable [Bitmap32], the audio track on [MusicPlayer] whose clock drives
 * which frame is shown, and the pause/restart bookkeeping.
 *
 * Decoding itself is delegated to [videoBackend]; this class only drains its
 * [VideoFrames] queue and times the frames against the audio position. The
 * frame queue is drained eagerly (all frames due at the current playhead, only
 * the newest drawn) so a slow decode never rewinds the picture.
 */
class VideoPlayback(private val videoPath: String) {

    var error: String? = null
        private set

    var width = 0
        private set
    var height = 0
        private set
    var fps = 30.0
        private set
    var durationMs = 0.0
        private set
    var hasAudio = false
        private set

    var image: Image? = null
        private set

    private val audioPlayer = MusicPlayer()
    private var bitmap: Bitmap32? = null
    private var source: VideoFrames? = null

    private var pendingFrame: VideoFrame? = null
    private var recycleBuffer: IntArray? = null
    /**
     * Frames that were decoded but superseded before they could be drawn (the
     * game stalled, or several frames became due at once). Their pixel arrays go
     * back to the decoder one per poll.
     */
    private val spareBuffers = ArrayDeque<IntArray>()
    private var currentFrameIndex = -1

    private var videoAbsPath: String? = null
    private var outputWidth = 0
    private var outputHeight = 0

    private var audioStarted = false
    private var paused = false
    private var audioPausedByMenu = false
    private var pauseStartedNanos = 0.0
    private var wallStartNanos = 0.0
    /**
     * Correction applied on top of the wall clock so the picture follows the
     * audio without inheriting its block-quantised steps (see [elapsedMs]).
     */
    private var clockOffsetMs = 0.0
    private var maxElapsedMs = 0.0

    suspend fun prepare(): Boolean {
        try {
            val backend = videoBackend
            if (!backend.isSupported) {
                error = "video decoding is not supported on this platform"
                return false
            }

            val file = KnfFiles.fileOrNull(videoPath)
            if (file == null) {
                error = "missing asset '${KnfFiles.file(videoPath).absolutePath()}'"
                return false
            }
            val absPath = file.absolutePath()
            videoAbsPath = absPath

            val probe = withContext(Dispatchers.Default) { backend.probe(absPath) }
            if (probe == null) {
                error = "the platform video decoder could not read '${file.name}'"
                return false
            }

            width = probe.width
            height = probe.height
            fps = probe.fps
            durationMs = probe.durationMs
            hasAudio = probe.hasAudio

            val scale = min(
                Constants.GAME_WIDTH.toDouble() / width,
                Constants.GAME_HEIGHT.toDouble() / height,
            )
            val outWidth = even((width * scale).roundToInt().coerceAtLeast(2))
            val outHeight = even((height * scale).roundToInt().coerceAtLeast(2))
            outputWidth = outWidth
            outputHeight = outHeight

            val bmp = Bitmap32(outWidth, outHeight)
            bmp.premultiplied = true
            bitmap = bmp
            val view = Image(bmp, Anchor2D.CENTER, smoothing = true)
            view.x = Constants.GAME_WIDTH / 2.0
            view.y = Constants.GAME_HEIGHT / 2.0
            image = view

            // Start the frame decoder before the audio extraction: ffmpeg
            // decodes into the (small) frame queue in the background, so the
            // first picture is already waiting while the WAV track is being
            // produced.
            source = withContext(Dispatchers.Default) { backend.openFrames(absPath, outWidth, outHeight) }
            if (source == null) {
                if (error == null) error = "could not start the video decoder"
                return false
            }

            if (hasAudio) {
                val wav = withContext(Dispatchers.Default) { backend.extractAudioWavBytes(absPath) }
                if (wav != null) {
                    val loaded = audioPlayer.loadBytes("video:$videoPath", wav, 1.0)
                    if (!loaded) {
                        println("KNF: video cutscene could not decode audio for '$videoPath'; video will run silently")
                    }
                }
            }
            awaitFirstFrame()
            return error == null
        } catch (t: Throwable) {
            error = t.message ?: t::class.simpleName ?: "unknown error"
            close()
            return false
        }
    }

    fun showFirstFrame() {
        updateDisplay(0.0)
    }

    fun playAudio() {
        audioStarted = true
        paused = false
        audioPausedByMenu = false
        wallStartNanos = PerformanceCounter.nanoseconds
        clockOffsetMs = 0.0
        if (audioPlayer.exists) audioPlayer.play(0.0)
    }

    /** `VideoCutscene.pauseVideo()`: stop the movie clock until [resumeAudio]. */
    fun pauseAudio() {
        if (!audioStarted || paused) return
        paused = true
        pauseStartedNanos = PerformanceCounter.nanoseconds
        audioPausedByMenu = audioPlayer.exists && audioPlayer.pauseIfPlaying()
    }

    /** `VideoCutscene.resumeVideo()`: continue from the paused audio sample. */
    fun resumeAudio() {
        if (!paused) return
        paused = false
        val now = PerformanceCounter.nanoseconds
        // The wall clock must not count the time the menu was open.
        wallStartNanos += now - pauseStartedNanos
        if (audioPausedByMenu && audioPlayer.exists) {
            val at = audioPlayer.pausedAtMs
            audioPlayer.play(at)
            // Put the movie clock exactly on the sample playback resumes from.
            clockOffsetMs = at - (now - wallStartNanos) / 1_000_000.0
            if (at > maxElapsedMs) maxElapsedMs = at
        }
        audioPausedByMenu = false
    }

    /**
     * Rewind the decoder and start the extracted audio from zero. The pause
     * menu closes immediately afterwards.
     */
    fun restart() {
        val absPath = videoAbsPath ?: return
        audioPlayer.stop()
        source?.close()
        source = null
        pendingFrame = null
        recycleBuffer = null
        spareBuffers.clear()
        currentFrameIndex = -1
        audioStarted = false
        paused = false
        audioPausedByMenu = false
        error = null
        maxElapsedMs = 0.0
        clockOffsetMs = 0.0
        wallStartNanos = 0.0
        source = videoBackend.openFrames(absPath, outputWidth, outputHeight)
        if (source == null) {
            if (error == null) error = "could not restart the video decoder"
            return
        }
        playAudio()
    }

    /**
     * Movie position in milliseconds.
     *
     * The picture is paced by the wall clock, not by the audio position. KNF's
     * decoded outputs report `SoundChannel.current` once per 2048-frame device
     * block, so `audiblePositionMs` advances in ~46 ms steps; stepping the
     * frame index on that value turns 24/30 fps video into visible judder. The
     * audio position is still authoritative: whenever it drifts more than
     * [MAX_DRIFT_MS] away from the wall clock, the offset is corrected, so the
     * two stay in step without inheriting the quantisation.
     *
     * A silent movie (or one whose audio finished first) simply keeps running
     * on the wall clock.
     */
    fun elapsedMs(): Double {
        if (!audioStarted) return 0.0
        if (paused) return maxElapsedMs
        val now = PerformanceCounter.nanoseconds
        var wall = (now - wallStartNanos) / 1_000_000.0 + clockOffsetMs
        if (audioPlayer.exists && audioPlayer.playing) {
            val audio = audioPlayer.audiblePositionMs
            // Ignore the device-open / queue-fill window, where the audio has
            // not produced anything audible yet and `audio` is still 0.
            if (audio > 0.0) {
                val drift = audio - wall
                if (abs(drift) > MAX_DRIFT_MS) {
                    clockOffsetMs += drift
                    wall += drift
                }
            }
        }
        if (wall > maxElapsedMs) maxElapsedMs = wall
        return maxElapsedMs
    }

    fun debugState(): String =
        "audio=${audioPlayer.exists} playing=${audioPlayer.playing} paused=$paused " +
            "pos=${KnfFmt.decimals(audioPlayer.positionMs, 0)} frame=$currentFrameIndex " +
            "pending=${pendingFrame != null} error=$error"

    fun updateDisplay(elapsedMs: Double) {
        val bmp = bitmap ?: return
        val source = this.source ?: return
        val target = (elapsedMs * fps / 1000.0).toInt().coerceAtLeast(0)
        var latest: VideoFrame? = null

        while (true) {
            var frame = pendingFrame
            if (frame == null) {
                // `nextFrame` hands the array to the decoder, so it must be
                // cleared: offering the same buffer on every poll put duplicate
                // entries into the decoder's free list, two queued frames ended
                // up sharing one array, and decoding the later frame overwrote
                // the earlier one before it was shown (visible as skipped,
                // jerky frames).
                val spare = spareBuffers.removeFirstOrNull()
                val offered = spare ?: recycleBuffer
                if (spare == null) recycleBuffer = null
                frame = source.nextFrame(offered, 0)
                if (frame == null) break
            }
            pendingFrame = null
            if (frame.index > target) {
                pendingFrame = frame
                break
            }
            // Only the newest due frame is drawn in this tick; hand the others
            // back to the decoder on the next polls instead of leaking them.
            latest?.let { older -> if (spareBuffers.size < SPARE_BUFFERS) spareBuffers.addLast(older.pixels) }
            latest = frame
        }

        val frame = latest ?: return
        frame.pixels.copyInto(bmp.ints)
        // Bumps Bitmap.contentVersion, which is how KorGE knows to re-upload the
        // texture while the same Bitmap32 object is reused every frame.
        bmp.unlock()
        currentFrameIndex = frame.index
        recycleBuffer = frame.pixels
    }

    fun stopPlayback() {
        if (DEBUG_VIDEO) println("KNF: video stop audio")
        audioPlayer.stop()
        if (DEBUG_VIDEO) println("KNF: video audio stopped")
        source?.close()
        source = null
        pendingFrame = null
        recycleBuffer = null
        spareBuffers.clear()
        if (DEBUG_VIDEO) println("KNF: video decoder shutdown")
    }

    fun close() {
        stopPlayback()
        audioPlayer.dispose()
        image = null
        bitmap = null
    }

    /**
     * Waits for the decoder's first frame (up to [FIRST_FRAME_TIMEOUT_MS]) and
     * keeps it for the initial display.
     */
    private suspend fun awaitFirstFrame() {
        val source = this.source ?: return
        val deadline = PerformanceCounter.nanoseconds + FIRST_FRAME_TIMEOUT_MS * 1_000_000.0
        while (PerformanceCounter.nanoseconds < deadline) {
            val frame = source.nextFrame(recycleBuffer, FIRST_FRAME_POLL_MS)
            if (frame != null) {
                pendingFrame = frame
                break
            }
            if (error != null) break
        }
        if (pendingFrame == null && error == null) {
            error = "the video decoder produced no frames"
        }
        if (error == null) updateDisplay(0.0)
    }

    private fun even(value: Int): Int = if (value % 2 == 0) value else value - 1

    private companion object {
        const val FIRST_FRAME_TIMEOUT_MS = 5_000.0
        const val FIRST_FRAME_POLL_MS = 100L

        /** How far the audio may drift from the wall clock before it is corrected. */
        const val MAX_DRIFT_MS = 100.0

        /** Buffers kept aside for frames that are dropped on a catch-up tick. */
        const val SPARE_BUFFERS = 8

        val DEBUG_VIDEO = KnfEnv["KNF_DEBUG_VIDEO"] == "1"
    }
}
