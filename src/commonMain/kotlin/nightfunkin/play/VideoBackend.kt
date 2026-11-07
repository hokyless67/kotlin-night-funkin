package nightfunkin.play

import korlibs.image.bitmap.Bitmap32

/**
 * One decoded video frame. Pixel buffers are independent of the reusable
 * display [Bitmap32] so the decoder never races the renderer.
 */
class VideoFrame(val index: Int, val pixels: IntArray)

/** Stream metadata as reported by the platform decoder. */
interface VideoProbe {
    val width: Int
    val height: Int
    val fps: Double
    val durationMs: Double
    val hasAudio: Boolean
}

/**
 * A running video decoder that hands out raw frames.
 *
 * [nextFrame] is intentionally synchronous and blocking-with-timeout: the
 * cutscene polls once per frame from the scene updater, exactly like the old
 * `ArrayBlockingQueue.poll` pipeline. Handing a consumed pixel array back via
 * [recycle] lets the decoder reuse buffers instead of allocating ~4 MB per
 * frame at 30 fps.
 */
interface VideoFrames {
    val width: Int
    val height: Int

    /**
     * Next decoded frame, or `null` on end-of-stream/timeout/error.
     * `timeoutMs == 0` performs a non-blocking poll.
     */
    fun nextFrame(recycle: IntArray?, timeoutMs: Long): VideoFrame?

    /** Stops the decoder and releases its resources. */
    fun close()
}

/**
 * Platform video support behind video cutscenes.
 *
 *  - Desktop (JVM): the system `ffmpeg`/`ffprobe` binaries, piped as raw RGBA
 *    frames (see `VideoBackend.jvm.kt`).
 *  - Mobile: no decoder — [isSupported] is false and video cutscenes are
 *    skipped gracefully. A native pipeline (MediaCodec / AVAssetReader) can
 *    slot in here later without touching the cutscene code.
 */
interface VideoBackend {
    val isSupported: Boolean

    /** Reads stream metadata, or null when the video cannot be probed. */
    fun probe(videoPath: String): VideoProbe?

    /** Starts a decoder producing [outWidth]x[outHeight] RGBA frames. */
    fun openFrames(videoPath: String, outWidth: Int, outHeight: Int): VideoFrames?

    /**
     * The video's first audio track as a 44.1 kHz stereo WAV, or null when the
     * stream has no (extractable) audio.
     */
    fun extractAudioWavBytes(videoPath: String): ByteArray?
}

expect val videoBackend: VideoBackend
