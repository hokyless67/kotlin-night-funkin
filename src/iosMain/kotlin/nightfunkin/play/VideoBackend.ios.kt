package nightfunkin.play

/**
 * iOS has no out-of-the-box way to pipe an MP4 into raw RGBA frames at
 * gameplay framerate (AVAssetReader is a whole pipeline of its own), so video
 * cutscenes are skipped gracefully on mobile. A native decoder can be dropped
 * in here later without touching the cutscene code.
 */
actual val videoBackend: VideoBackend = object : VideoBackend {
    override val isSupported: Boolean get() = false
    override fun probe(videoPath: String): VideoProbe? = null
    override fun openFrames(videoPath: String, outWidth: Int, outHeight: Int): VideoFrames? = null
    override fun extractAudioWavBytes(videoPath: String): ByteArray? = null
}
