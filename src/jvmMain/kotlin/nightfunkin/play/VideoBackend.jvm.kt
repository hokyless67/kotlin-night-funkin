package nightfunkin.play

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nightfunkin.platform.KnfPlatform
import java.io.File
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Desktop video backend: the system `ffmpeg`/`ffprobe` binaries.
 *
 * The decoder pipes raw RGBA frames from ffmpeg through a tiny blocking queue
 * (four frames): ffmpeg blocks on the stdout pipe once it is full, which gives
 * natural backpressure and prevents it from decoding the entire movie ahead of
 * the game. Consumed pixel buffers are recycled through [FfmpegFrames.nextFrame].
 */
actual val videoBackend: VideoBackend = FfmpegVideoBackend

private object FfmpegVideoBackend : VideoBackend {

    /**
     * Videos decode only in real desktop mode. `KNF_MOBILE=1` emulates mobile,
     * where there is no decoder, so video cutscenes take the mobile skip path
     * and that behaviour can be tested on a desktop.
     */
    override val isSupported: Boolean get() = !KnfPlatform.isMobile

    override fun probe(videoPath: String): VideoProbe? {
        val ffprobe = FfmpegTool.ffprobe ?: run {
            println("KNF: ffprobe was not found (set KNF_FFPROBE or -Dknf.ffprobe=...)")
            return null
        }
        val result = runProcess(
            listOf(
                ffprobe,
                "-v", "error",
                "-show_entries",
                "stream=codec_type,width,height,avg_frame_rate,r_frame_rate,duration:format=duration",
                "-of", "json",
                videoPath,
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

        return object : VideoProbe {
            override val width = w
            override val height = h
            override val fps = rate.takeIf { it in 1.0..240.0 } ?: 30.0
            override val durationMs = duration
            override val hasAudio = streams.any {
                it.jsonObject["codec_type"]?.jsonPrimitive?.content == "audio"
            }
        }
    }

    override fun openFrames(videoPath: String, outWidth: Int, outHeight: Int): VideoFrames? =
        FfmpegFrames.open(videoPath, outWidth, outHeight)

    override fun extractAudioWavBytes(videoPath: String): ByteArray? {
        val ffmpeg = FfmpegTool.ffmpeg ?: return null
        val p = try {
            ProcessBuilder(
                listOf(
                    ffmpeg,
                    "-y",
                    "-nostdin",
                    "-v", "error",
                    "-i", videoPath,
                    "-map", "0:a:0",
                    "-vn",
                    "-ac", "2",
                    "-ar", "44100",
                    "-f", "wav",
                    "pipe:1",
                ),
            ).redirectErrorStream(false).start()
        } catch (t: Throwable) {
            println("KNF: ffmpeg audio ($videoPath): could not start: ${t.message}")
            return null
        }
        // Drain stderr on its own thread so it cannot fill the pipe and stall
        // ffmpeg while the main thread reads the WAV out of stdout.
        val stderrDrainer = thread(name = "knf-video-audio-stderr", isDaemon = true) {
            runCatching {
                p.errorStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) println("KNF: ffmpeg audio ($videoPath): $line")
                }
            }
        }
        try {
            val bytes = runCatching { p.inputStream.readBytes() }.getOrDefault(ByteArray(0))
            val finished = runCatching { p.waitFor(120, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!finished) p.destroyForcibly()
            val ok = finished && p.exitValue() == 0 && bytes.isNotEmpty()
            if (!ok) return null
            return bytes
        } finally {
            stderrDrainer.interrupt()
        }
    }

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
}

/**
 * One running `ffmpeg` process producing raw RGBA frames, plus its reader and
 * stderr-drain daemon threads.
 */
private class FfmpegFrames private constructor(
    private val videoPath: String,
    private val process: Process,
    private val outWidth: Int,
    private val outHeight: Int,
) : VideoFrames {

    override val width: Int get() = outWidth
    override val height: Int get() = outHeight

    private val queue = ArrayBlockingQueue<VideoFrame>(QUEUE_CAPACITY)
    private val freeBuffers = ArrayBlockingQueue<IntArray>(QUEUE_CAPACITY + 3)

    @Volatile
    private var closed = false
    private var readerThread: Thread? = null
    private var stderrThread: Thread? = null

    override fun nextFrame(recycle: IntArray?, timeoutMs: Long): VideoFrame? {
        if (recycle != null) freeBuffers.offer(recycle)
        if (closed) return null
        return runCatching { queue.poll(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { process.destroyForcibly() }
        readerThread?.interrupt()
        stderrThread?.interrupt()
        queue.clear()
        freeBuffers.clear()
    }

    fun start() {
        val pixelCount = outWidth * outHeight
        repeat(QUEUE_CAPACITY + 2) { freeBuffers.offer(IntArray(pixelCount)) }

        stderrThread = thread(name = "knf-video-stderr", isDaemon = true) {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) println("KNF: ffmpeg ($videoPath): $line")
                }
            }
        }

        val frameBytes = outWidth * outHeight * 4
        readerThread = thread(name = "knf-video-reader", isDaemon = true) {
            val input = process.inputStream.buffered(1 shl 20)
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
                }
            } catch (_: InterruptedException) {
                // close() interrupts; treat as end of stream.
            } catch (t: Throwable) {
                if (!closed) println("KNF: video frame reader failed ($videoPath): ${t.message}")
            } finally {
                runCatching { process.destroy() }
            }
        }
    }

    /** Blocks up to 50 ms per attempt; gives up when the player is closed. */
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

    companion object {
        const val QUEUE_CAPACITY = 4

        fun open(videoPath: String, outWidth: Int, outHeight: Int): FfmpegFrames? {
            val ffmpeg = FfmpegTool.ffmpeg ?: run {
                println("KNF: ffmpeg was not found (set KNF_FFMPEG or -Dknf.ffmpeg=...)")
                return null
            }
            val command = listOf(
                ffmpeg,
                "-nostdin",
                "-v", "error",
                "-i", videoPath,
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
                println("KNF: could not start ffmpeg for '$videoPath': ${t.message}")
                return null
            }
            return FfmpegFrames(videoPath, p, outWidth, outHeight).also { it.start() }
        }
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
