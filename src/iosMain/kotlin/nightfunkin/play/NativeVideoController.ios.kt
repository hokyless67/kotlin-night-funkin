package nightfunkin.play

/**
 * iOS has no controller yet: `AVPlayer` + `AVPlayerLayer` can drop in here later
 * without touching the cutscene code. Until then cutscenes are skipped on iOS,
 * exactly like they were before Android support.
 */
actual fun createNativeVideoController(videoPath: String): NativeVideoController? = null
