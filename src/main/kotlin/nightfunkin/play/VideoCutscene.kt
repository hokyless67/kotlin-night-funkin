package nightfunkin.play

import korlibs.image.bitmap.Bitmap32
import korlibs.korge.view.Image
import korlibs.math.geom.Anchor2D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nightfunkin.audio.MusicPlayer
import nightfunkin.core.Constants
import nightfunkin.core.KnfFiles
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * FNF's `VideoCutscene` for a single movie file.
 *
 * KorGE 6 has no video decoder, so the movie is decoded by the system's
 * `ffmpeg` into raw RGBA frames and uploaded into one reusable [Bitmap32].
 * Audio is extracted to a temporary WAV and played through [MusicPlayer]; its
 * playback clock drives which video frame is displayed, so the two do not
 * drift. `ffmpeg`/`ffprobe` can be overridden with `KNF_FFMPEG`/`KNF_FFPROBE`
 * or `-Dknf.ffmpeg=...`/`-Dknf.ffprobe=...`.
 */
class VideoSongCutscene(
    private val host: CutsceneHost,
    private val videoPath: String,
    override val kind: CutsceneKind = CutsceneKind.STARTING,
    /** `darnell.hxc` calls `tweenCameraZoom(1.3, 0, true)` before its video. */
    private val initialCameraZoom: Double? = null,
) : SongCutscene {

    private val player = FfmpegVideoPlayer(videoPath)
    private var prepared = false
    private var started = false
    private var fading = false
    private var done = false

    private var fadeElapsed = 0.0
    private var fadeStartZoom = 1.0
    private var maxElapsedMs = 0.0
    private var lastDebugMs = -1.0

    /** True when the movie is prepared and can be shown after a scripted prelude. */
    val ready: Boolean get() = prepared && !done && player.error == null

    /**
     * `VideoCutscene.hideVideo()`: the movie keeps decoding and its audio keeps
     * playing, but the game is visible. Used by the `2hot` ending choreography,
     * which shows the first six seconds of the video only at the end of its
     * camera/character sequence.
     */
    fun hideForPrelude() {
        player.image?.visible = false
        host.setBlackScreenAlpha(0.0)
    }

    /** `VideoCutscene.showVideo()` after the scripted prelude. */
    fun showFromPrelude() {
        host.setBlackScreenAlpha(1.0)
        player.image?.visible = true
    }

    override suspend fun start() {
        initialCameraZoom?.let { host.setCameraZoom(it) }
        host.setHudVisible(false)
        host.showBlackScreen()
        host.setBlackScreenAlpha(1.0)

        prepared = player.prepare()
        if (!prepared) {
            println("KNF: video cutscene skipped ($videoPath): ${player.error ?: "unknown error"}")
            host.hideBlackScreen()
            host.setHudVisible(true)
            done = true
            return
        }

        player.image?.let { host.addCutsceneView(it) }
        player.showFirstFrame()
        println(
            "KNF: video cutscene '$videoPath' " +
                "(${player.width}x${player.height} @ ${"%.3f".format(player.fps)} fps, " +
                "${"%.2f".format(player.durationMs / 1000.0)}s, audio=${player.hasAudio})"
        )
    }

    override fun update(dt: Double): Boolean {
        if (done) return true
        if (!prepared) return false

        if (fading) {
            fadeElapsed += dt
            val t = (fadeElapsed / FADE_SECONDS).coerceIn(0.0, 1.0)
            val eased = quadInOut(t)
            host.setBlackScreenAlpha(1.0 - eased)
            host.setCameraZoom(fadeStartZoom + (host.stageZoom - fadeStartZoom) * eased)
            if (t >= 1.0) {
                host.hideBlackScreen()
                done = true
                println("KNF: video cutscene finished ($videoPath)")
                return true
            }
            return false
        }

        if (!started) {
            started = true
            player.playAudio()
        }

        val elapsed = player.elapsedMs()
        if (elapsed > maxElapsedMs) maxElapsedMs = elapsed
        player.updateDisplay(maxElapsedMs)

        if (DEBUG && maxElapsedMs - lastDebugMs >= 1000.0) {
            lastDebugMs = maxElapsedMs
            println("KNF: video t=${"%.0f".format(maxElapsedMs)}ms ${player.debugState()}")
        }

        val decodeError = player.error
        if (decodeError != null) {
            println("KNF: video cutscene decode error ($videoPath): $decodeError")
            beginFade()
        } else if (maxElapsedMs >= player.durationMs - 1000.0 / player.fps * 0.5) {
            // `FunkinVideoSprite.bitmap.onEndReached` -> `finishVideo(0.5)`.
            beginFade()
        }
        return false
    }

    /** `VideoCutscene.finishVideo(0.5)`. */
    private fun beginFade() {
        if (fading || done) return
        if (DEBUG) println("KNF: video beginFade t=${"%.0f".format(maxElapsedMs)}ms")
        fading = true
        fadeElapsed = 0.0
        fadeStartZoom = host.cameraZoom

        // `vid.stop(); remove(vid); camHUD.visible = true;`
        player.stopPlayback()
        if (DEBUG) println("KNF: video playback stopped")
        player.image?.let { host.removeCutsceneView(it) }
        host.setHudVisible(true)
    }

    override fun destroy() {
        // Remove the view before close() clears the player's reference.
        player.image?.let { host.removeCutsceneView(it) }
        player.close()
        host.hideBlackScreen()
    }

    private companion object {
        /** `finishVideo(0.5)`. */
        const val FADE_SECONDS = 0.5

        val DEBUG = System.getenv("KNF_DEBUG_VIDEO") == "1"

        /** `FlxEase.quadInOut`. */
        fun quadInOut(t: Double): Double =
            if (t < 0.5) 2.0 * t * t else 1.0 - (-2.0 * t + 2.0) * (-2.0 * t + 2.0) / 2.0
    }
}

/**
 * `2hot.hxc`'s ending sequence.
 *
 * The movie starts at the normal song-end point and its audio plays from the
 * beginning, but it is hidden for six seconds while the camera pans from the
 * opponent to the stage, BF plays `intro1` and Dad plays `pissed`. At t=6 the
 * black overlay is put back and the movie is shown at its 6-second mark.
 */
class TwoHotEndingCutscene(private val host: CutsceneHost) : SongCutscene {

    override val kind = CutsceneKind.ENDING

    private val video = VideoSongCutscene(
        host,
        "videos/videos/2hotCutscene.mp4",
        CutsceneKind.ENDING,
    )

    private var time = 0.0
    private var failed = false
    private var done = false

    private var cameraMoveStarted = false
    private var startFocusX = 0.0
    private var startFocusY = 0.0
    private var startZoom = 1.0

    private var bfStarted = false
    private var dadStarted = false
    private var videoShown = false
    private var nextGfBeat = GF_BEAT_SECONDS

    override suspend fun start() {
        video.start()
        if (!video.ready) {
            failed = true
            return
        }
        // `VideoCutscene.hideVideo()` in `endCutscene()`.
        video.hideForPrelude()
        host.setHudVisible(false)
        // `danceEvery = 0` for BF and Dad during the choreography.
        host.setCharacterDancesOnBeat("bf", false)
        host.setCharacterDancesOnBeat("dad", false)
        host.danceCharacter("gf", forceRestart = true)
    }

    override fun update(dt: Double): Boolean {
        if (failed) return true
        if (done) return true

        // The movie keeps playing (and its audio stays in sync) while hidden.
        if (video.update(dt)) {
            done = true
            return true
        }

        time += dt

        if (!videoShown) {
            while (time >= nextGfBeat) {
                if (host.isCharacterAnimationFinished("gf")) {
                    host.danceCharacter("gf", forceRestart = true)
                }
                nextGfBeat += GF_BEAT_SECONDS
            }
        }

        if (!cameraMoveStarted && time >= CAMERA_MOVE_DELAY) {
            cameraMoveStarted = true
            startFocusX = host.cameraFocusX
            startFocusY = host.cameraFocusY
            startZoom = host.cameraZoom
            if (DEBUG) {
                println(
                    "KNF: 2hot prelude camera from " +
                        "(${"%.1f".format(startFocusX)}, ${"%.1f".format(startFocusY)}) " +
                        "zoom ${"%.2f".format(startZoom)}"
                )
            }
        }
        if (cameraMoveStarted && !videoShown) {
            val t = quadInOut(((time - CAMERA_MOVE_DELAY) / 2.0).coerceIn(0.0, 1.0))
            host.focusCamera(
                startFocusX + (CAMERA_TARGET_X - startFocusX) * t,
                startFocusY + (CAMERA_TARGET_Y - startFocusY) * t,
                startZoom + (CAMERA_TARGET_ZOOM - startZoom) * t,
            )
        }

        if (!bfStarted && time >= 2.0) {
            bfStarted = true
            host.playCharacterAnimation("bf", "intro1", forceRestart = true)
            if (DEBUG) println("KNF: 2hot prelude BF intro1")
        }
        if (!dadStarted && time >= 2.5) {
            dadStarted = true
            host.playCharacterAnimation("dad", "pissed", forceRestart = true)
            if (DEBUG) println("KNF: 2hot prelude Dad pissed")
        }
        if (!videoShown && time >= VIDEO_SHOW_DELAY) {
            videoShown = true
            video.showFromPrelude()
            if (DEBUG) println("KNF: 2hot prelude showing video at t=${"%.2f".format(time)}s")
        }

        return false
    }

    override fun destroy() {
        video.destroy()
    }

    private companion object {
        /** `cutsceneConductor.forceBPM(86.5)`. */
        const val GF_BEAT_SECONDS = 60.0 / 86.5

        const val CAMERA_MOVE_DELAY = 1.0
        const val CAMERA_TARGET_X = 1539.0
        const val CAMERA_TARGET_Y = 833.5
        const val CAMERA_TARGET_ZOOM = 0.69

        const val VIDEO_SHOW_DELAY = 6.0

        val DEBUG = System.getenv("KNF_DEBUG_VIDEO") == "1"

        /** `FlxEase.quadInOut`. */
        fun quadInOut(t: Double): Double =
            if (t < 0.5) 2.0 * t * t else 1.0 - (-2.0 * t + 2.0) * (-2.0 * t + 2.0) / 2.0
    }
}

/**
 * One decoded frame. Pixel buffers are independent of the reusable display
 * bitmap so the reader thread never races the renderer.
 */
private class VideoFrame(val index: Int, val pixels: IntArray)

/**
 * The ffmpeg process, its frame-reader thread, and the audio track.
 *
 * The frame queue is deliberately tiny (four frames): ffmpeg blocks on the
 * stdout pipe once it is full, which gives natural backpressure and prevents it
 * from decoding the entire movie ahead of the game.
 */
private class FfmpegVideoPlayer(private val videoPath: String) {

    @Volatile
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

    private var bitmap: Bitmap32? = null
    private val audioPlayer = MusicPlayer()

    private var process: Process? = null
    private var readerThread: Thread? = null
    private var stderrThread: Thread? = null

    private val queue = ArrayBlockingQueue<VideoFrame>(QUEUE_CAPACITY)
    private val freeBuffers = ArrayBlockingQueue<IntArray>(QUEUE_CAPACITY + 3)
    private val firstFrame = CountDownLatch(1)

    @Volatile
    private var closed = false
    @Volatile
    private var eof = false

    private var pendingFrame: VideoFrame? = null
    private var currentFrameIndex = -1

    private var audioStarted = false
    private var wallStartNanos = 0L
    private var lastAudioMs = 0.0
    private var lastAudioWallNanos = 0L
    private var maxElapsedMs = 0.0

    suspend fun prepare(): Boolean {
        try {
            val file = KnfFiles.fileOrNull(videoPath)
            if (file == null) {
                error = "missing asset '${KnfFiles.file(videoPath).absolutePath}'"
                return false
            }

            val probe = withContext(Dispatchers.IO) { probeVideo(file) }
            if (probe == null) {
                error = "ffprobe could not read '${file.name}' (is ffmpeg installed and on PATH?)"
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

            val bmp = Bitmap32(outWidth, outHeight)
            bmp.premultiplied = true
            bitmap = bmp
            val view = Image(bmp, Anchor2D.CENTER, smoothing = true)
            view.x = Constants.GAME_WIDTH / 2.0
            view.y = Constants.GAME_HEIGHT / 2.0
            image = view

            if (hasAudio) {
                val wav = withContext(Dispatchers.IO) { extractAudio(file) }
                if (wav != null) {
                    val loaded = try {
                        audioPlayer.loadFile(wav, 1.0)
                    } finally {
                        wav.delete()
                    }
                    if (!loaded) {
                        println("KNF: video cutscene could not decode audio for '$videoPath'; video will run silently")
                    }
                }
            }

            startDecoder(file, outWidth, outHeight)
            awaitFirstFrame()
            return error == null
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
            close()
            return false
        }
    }

    fun showFirstFrame() {
        updateDisplay(0.0)
    }

    fun playAudio() {
        audioStarted = true
        wallStartNanos = System.nanoTime()
        lastAudioWallNanos = wallStartNanos
        if (audioPlayer.exists) audioPlayer.play(0.0)
    }

    fun elapsedMs(): Double {
        if (!audioStarted) return 0.0
        val now = System.nanoTime()

        val raw = if (audioPlayer.exists) {
            val position = audioPlayer.positionMs
            if (position > lastAudioMs) {
                lastAudioMs = position
                lastAudioWallNanos = now
            }
            if (audioPlayer.playing) {
                position
            } else {
                // The extracted audio can be a little shorter than the movie
                // (or stop at the last decoded sample). Keep the video clock
                // moving from the last audio position so the final frames and
                // finishVideo() are not stranded.
                val wallSinceAudio = lastAudioMs +
                    (now - lastAudioWallNanos) / 1_000_000.0
                maxOf(position, wallSinceAudio)
            }
        } else {
            (now - wallStartNanos) / 1_000_000.0
        }

        if (raw > maxElapsedMs) maxElapsedMs = raw
        return maxElapsedMs
    }

    fun debugState(): String =
        "audio=${audioPlayer.exists} playing=${audioPlayer.playing} " +
            "pos=${"%.0f".format(audioPlayer.positionMs)} frame=$currentFrameIndex " +
            "queue=${queue.size} pending=${pendingFrame != null} eof=$eof error=$error"

    fun updateDisplay(elapsedMs: Double) {
        val bmp = bitmap ?: return
        val target = (elapsedMs * fps / 1000.0).toInt().coerceAtLeast(0)
        var latest: VideoFrame? = null

        while (true) {
            val frame = pendingFrame ?: queue.poll() ?: break
            pendingFrame = null
            if (frame.index > target) {
                pendingFrame = frame
                break
            }
            // Only the newest due frame is drawn in this tick; recycle the rest.
            latest?.let { freeBuffers.offer(it.pixels) }
            latest = frame
        }

        val frame = latest ?: return
        System.arraycopy(frame.pixels, 0, bmp.ints, 0, frame.pixels.size)
        // Bumps Bitmap.contentVersion, which is how KorGE knows to re-upload the
        // texture while the same Bitmap32 object is reused every frame.
        bmp.unlock()
        currentFrameIndex = frame.index
        freeBuffers.offer(frame.pixels)
    }

    fun stopPlayback() {
        if (DEBUG_VIDEO) println("KNF: video stop audio")
        audioPlayer.stop()
        if (DEBUG_VIDEO) println("KNF: video audio stopped")
        shutdownDecoder()
        if (DEBUG_VIDEO) println("KNF: video decoder shutdown")
    }

    fun close() {
        stopPlayback()
        audioPlayer.dispose()
        image = null
        bitmap = null
    }

    private suspend fun awaitFirstFrame() {
        firstFrame.await(5, TimeUnit.SECONDS)
        if (eof && queue.isEmpty() && pendingFrame == null && error == null) {
            error = "ffmpeg produced no video frames"
        }
        if (error == null) updateDisplay(0.0)
    }

    private fun startDecoder(file: File, outWidth: Int, outHeight: Int) {
        val ffmpeg = FfmpegTool.ffmpeg
        if (ffmpeg == null) {
            error = "ffmpeg was not found (set KNF_FFMPEG or -Dknf.ffmpeg=...)"
            firstFrame.countDown()
            return
        }

        val command = listOf(
            ffmpeg,
            "-nostdin",
            "-v", "error",
            "-i", file.absolutePath,
            "-map", "0:v:0",
            "-an", "-sn", "-dn",
            "-vf", "scale=${outWidth}:${outHeight}:flags=bicubic",
            "-pix_fmt", "rgba",
            "-f", "rawvideo",
            "pipe:1",
        )

        val p = try {
            ProcessBuilder(command).redirectErrorStream(false).start()
        } catch (t: Throwable) {
            error = "could not start ffmpeg: ${t.message}"
            firstFrame.countDown()
            return
        }
        process = p

        stderrThread = thread(name = "knf-video-stderr", isDaemon = true) {
            runCatching {
                p.errorStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) println("KNF: ffmpeg ($videoPath): $line")
                }
            }
        }

        val frameBytes = outWidth * outHeight * 4
        val pixelCount = outWidth * outHeight
        repeat(QUEUE_CAPACITY + 2) { freeBuffers.offer(IntArray(pixelCount)) }
        readerThread = thread(name = "knf-video-reader", isDaemon = true) {
            val input = p.inputStream.buffered(1 shl 20)
            val bytes = ByteArray(frameBytes)
            var index = 0
            try {
                while (!closed) {
                    if (!readFrame(input, bytes)) break
                    val pixels = freeBuffers.poll() ?: IntArray(pixelCount)
                    var b = 0
                    for (i in pixels.indices) {
                        val r = bytes[b].toInt() and 0xFF
                        val g = bytes[b + 1].toInt() and 0xFF
                        val bl = bytes[b + 2].toInt() and 0xFF
                        val a = bytes[b + 3].toInt() and 0xFF
                        // Bitmap32 stores 0xAABBGGRR.
                        pixels[i] = (a shl 24) or (bl shl 16) or (g shl 8) or r
                        b += 4
                    }
                    val frame = VideoFrame(index++, pixels)
                    if (!offer(frame)) {
                        freeBuffers.offer(pixels)
                        break
                    }
                    if (index == 1) firstFrame.countDown()
                }
            } catch (t: Throwable) {
                if (!closed) error = "frame reader failed: ${t.message}"
            } finally {
                eof = true
                firstFrame.countDown()
                runCatching { p.destroy() }
            }
        }
    }

    private fun offer(frame: VideoFrame): Boolean {
        while (!closed) {
            if (queue.offer(frame, 50, TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    private fun readFrame(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) return false
            if (read == 0) continue
            offset += read
        }
        return true
    }

    private fun shutdownDecoder() {
        closed = true
        process?.destroyForcibly()
        process = null
        readerThread?.interrupt()
        readerThread = null
        stderrThread?.interrupt()
        stderrThread = null
        queue.clear()
        freeBuffers.clear()
        pendingFrame = null
        firstFrame.countDown()
    }

    private fun probeVideo(file: File): VideoProbe? {
        val ffprobe = FfmpegTool.ffprobe ?: return null
        val result = runProcess(
            listOf(
                ffprobe,
                "-v", "error",
                "-show_entries",
                "stream=codec_type,width,height,avg_frame_rate,r_frame_rate,duration:format=duration",
                "-of", "json",
                file.absolutePath,
            ),
            timeoutMs = 15_000,
        )
        if (result.exitCode != 0) {
            if (result.output.isNotBlank()) println("KNF: ffprobe ($videoPath): ${result.output.trim()}")
            return null
        }

        val root = runCatching { Json.parseToJsonElement(result.output).jsonObject }.getOrNull() ?: return null
        val streams = root["streams"]?.jsonArray ?: return null
        val video = streams
            .mapNotNull { it.jsonObject }
            .firstOrNull { it["codec_type"]?.jsonPrimitive?.content == "video" }
            ?: return null

        val w = video["width"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        val h = video["height"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        val rate = parseRate(video["avg_frame_rate"]?.jsonPrimitive?.content)
            .takeIf { it > 0.0 }
            ?: parseRate(video["r_frame_rate"]?.jsonPrimitive?.content)
        val formatDuration = root["format"]?.jsonObject?.get("duration")?.jsonPrimitive?.content?.toDoubleOrNull()
        val streamDuration = video["duration"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val duration = (formatDuration ?: streamDuration ?: 0.0) * 1000.0

        return VideoProbe(
            width = w,
            height = h,
            fps = rate.takeIf { it in 1.0..240.0 } ?: 30.0,
            durationMs = duration,
            hasAudio = streams.any {
                it.jsonObject["codec_type"]?.jsonPrimitive?.content == "audio"
            },
        )
    }

    private fun extractAudio(file: File): File? {
        val ffmpeg = FfmpegTool.ffmpeg ?: return null
        val wav = try {
            Files.createTempFile("knf-video-", ".wav").toFile()
        } catch (t: Throwable) {
            return null
        }
        val result = runProcess(
            listOf(
                ffmpeg,
                "-y",
                "-nostdin",
                "-v", "error",
                "-i", file.absolutePath,
                "-map", "0:a:0",
                "-vn",
                "-ac", "2",
                "-ar", "44100",
                "-f", "wav",
                wav.absolutePath,
            ),
            timeoutMs = 120_000,
        )
        if (result.exitCode != 0 || !wav.isFile || wav.length() == 0L) {
            if (result.output.isNotBlank()) {
                println("KNF: ffmpeg audio ($videoPath): ${result.output.trim()}")
            }
            wav.delete()
            return null
        }
        return wav
    }

    private data class VideoProbe(
        val width: Int,
        val height: Int,
        val fps: Double,
        val durationMs: Double,
        val hasAudio: Boolean,
    )

    private data class ProcessResult(val exitCode: Int, val output: String)

    private fun runProcess(command: List<String>, timeoutMs: Long): ProcessResult {
        val p = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = runCatching { p.inputStream.readBytes().decodeToString() }.getOrDefault("")
        val finished = runCatching { p.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!finished) {
            p.destroyForcibly()
            return ProcessResult(-1, output)
        }
        return ProcessResult(p.exitValue(), output)
    }

    private fun parseRate(value: String?): Double {
        if (value.isNullOrBlank()) return 0.0
        val parts = value.split('/')
        return if (parts.size == 2) {
            val num = parts[0].toDoubleOrNull() ?: return 0.0
            val den = parts[1].toDoubleOrNull() ?: return 0.0
            if (den == 0.0) 0.0 else num / den
        } else {
            value.toDoubleOrNull() ?: 0.0
        }
    }

    private fun even(value: Int): Int = if (value % 2 == 0) value else value - 1

    private companion object {
        const val QUEUE_CAPACITY = 4
        val DEBUG_VIDEO = System.getenv("KNF_DEBUG_VIDEO") == "1"
    }
}

/** Locates `ffmpeg`/`ffprobe`; explicit paths win, then the PATH. */
private object FfmpegTool {
    private val ffmpegOverride: String? =
        System.getProperty("knf.ffmpeg")?.takeIf { it.isNotBlank() }
            ?: System.getenv("KNF_FFMPEG")?.takeIf { it.isNotBlank() }

    private val ffprobeOverride: String? =
        System.getProperty("knf.ffprobe")?.takeIf { it.isNotBlank() }
            ?: System.getenv("KNF_FFPROBE")?.takeIf { it.isNotBlank() }

    val ffmpeg: String? by lazy {
        resolve("ffmpeg", ffmpegOverride)?.also { println("KNF: ffmpeg: $it") }
    }

    val ffprobe: String? by lazy {
        val explicit = ffprobeOverride ?: ffmpegOverride?.let { siblingTool(it, "ffprobe") }
        resolve("ffprobe", explicit)?.also { println("KNF: ffprobe: $it") }
    }

    private fun resolve(name: String, override: String?): String? {
        if (!override.isNullOrBlank()) {
            val file = File(override)
            return if (file.isFile) file.absolutePath else override
        }

        val exe = if (isWindows) "$name.exe" else name
        val candidates = buildList {
            System.getenv("LOCALAPPDATA")?.let {
                add(File(File(it, "Microsoft/WinGet/Links"), exe).absolutePath)
            }
            add(File("C:/ffmpeg/bin", exe).absolutePath)
            add(File("C:/Program Files/ffmpeg/bin", exe).absolutePath)
        }
        for (candidate in candidates) {
            if (File(candidate).isFile) return candidate
        }

        return runCatching {
            val p = ProcessBuilder(name, "-version").redirectErrorStream(true).start()
            val ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
            if (!ok) p.destroyForcibly()
            if (ok) name else null
        }.getOrNull()
    }

    private fun siblingTool(ffmpegPath: String, name: String): String? {
        val file = File(ffmpegPath)
        val parent = file.parentFile ?: return null
        val exe = if (isWindows) "$name.exe" else name
        return File(parent, exe).takeIf { it.isFile }?.absolutePath
    }

    private val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
