package nightfunkin.play

/**
 * Desktop has no embeddable system player: `VideoPlayback` decodes the movie
 * with the external `ffmpeg` and drives the picture from its own clock.
 */
actual fun createNativeVideoController(videoPath: String): NativeVideoController? = null
