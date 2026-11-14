package nightfunkin.play

/**
 * Android does not use the frame pipeline: there is no out-of-the-box way to
 * pipe an MP4 into raw RGBA frames at gameplay framerate (MediaCodec +
 * ImageReader is a whole pipeline of its own), so video cutscenes play through
 * [AndroidVideoController], which drives ExoPlayer and a `TextureView` above
 * the game surface (see [createNativeVideoController]).
 *
 * Keeping this backend unsupported means [VideoPlayback] is never built on
 * Android, so a missing native player degrades to "cutscene skipped", exactly
 * as it did before.
 */
actual val videoBackend: VideoBackend = object : VideoBackend {
    override val isSupported: Boolean get() = false
    override fun probe(videoPath: String): VideoProbe? = null
    override fun openFrames(videoPath: String, outWidth: Int, outHeight: Int): VideoFrames? = null
    override fun extractAudioWavBytes(videoPath: String): ByteArray? = null
}
