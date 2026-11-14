package nightfunkin.play

import korlibs.image.bitmap.Bitmap32

/**
 * A frame captured from a native movie player, ready to be drawn *inside* the
 * game: used while the pause menu is open, when the platform's own movie
 * surface has to step aside for the game's UI.
 *
 * FNF keeps the movie visible (frozen) behind `PauseSubState`'s 60% black
 * backdrop, so `VideoCutscene.pauseVideo()` must not make the picture
 * disappear. A native player can only offer its current frame as pixels.
 */
class NativeVideoStill(
    /** The captured picture, already sized in device pixels. */
    val bitmap: Bitmap32,
    /** Center of the picture in game units (`Constants.GAME_WIDTH` space). */
    val x: Double,
    val y: Double,
    /** Size of the picture in game units. */
    val width: Double,
    val height: Double,
)

/**
 * A platform player that plays a movie *itself* - video and audio both - instead
 * of handing decoded frames to the game.
 *
 * Desktop has no system media player that can be embedded in a KorGE scene, so
 * it decodes with `ffmpeg` and drives the picture from [VideoPlayback]. Android
 * is the opposite: the framework media stack (ExoPlayer) plays every container
 * the game ships (MP4/AAC and MKV/Vorbis) with hardware decoding, and exposing
 * its texture through a normal Android `TextureView` on top of the game's
 * surface is far cheaper than piping raw frames over the JVM bridge.
 *
 * Implementations must be safe to drive from the game thread: all of their
 * methods may be called from there, and the platform-specific work happens on
 * the platform's own thread internally.
 */
interface NativeVideoController {
    /** True once [prepare] succeeded and the movie can be shown. */
    val isReady: Boolean

    /** Last prepare/playback error, or `null`. */
    val error: String?

    /** True once playback reached the end of the movie. */
    val ended: Boolean

    /** Current playback position in milliseconds. Debug/logging only. */
    val positionMs: Double

    /** Movie length in milliseconds, `0.0` until known. */
    val durationMs: Double

    /**
     * Locates the asset, creates the player and decodes enough to start
     * playback (paused). Runs inside `sceneInit`.
     */
    suspend fun prepare(): Boolean

    /** Starts (or resumes) playback. */
    fun play()

    /**
     * `VideoCutscene.pauseVideo()`: freeze picture and audio for the pause
     * menu. The movie surface is hidden once its current frame was captured
     * for [takeStill], so the game's own menu is not covered by the native
     * surface.
     */
    fun pauseForMenu()

    /**
     * The frozen picture captured by [pauseForMenu], or `null` while it is
     * still being read back. The caller owns the returned bitmap and should
     * draw it behind the pause menu until [resume] or [restart].
     */
    fun takeStill(): NativeVideoStill?

    /** `VideoCutscene.resumeVideo()`. */
    fun resume()

    /** `VideoCutscene.restartVideo()`: back to the first frame and play. */
    fun restart()

    /**
     * Shows/hides the movie surface. Used by the `2hot` prelude, which keeps the
     * movie (and its audio) running while the game is visible.
     */
    fun setVisible(visible: Boolean)

    /** Stops playback and hides the surface; the controller stays reusable. */
    fun stop()

    /** Stops everything and releases the player. */
    fun close()

    /** One-line state for `KNF_DEBUG_VIDEO`. */
    fun debugState(): String
}

/**
 * The platform's native movie player, or `null` when the platform has none and
 * [VideoPlayback]'s frame pipeline is used instead.
 */
expect fun createNativeVideoController(videoPath: String): NativeVideoController?
