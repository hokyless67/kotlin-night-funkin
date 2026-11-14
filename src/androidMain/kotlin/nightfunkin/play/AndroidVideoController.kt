package nightfunkin.play

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import korlibs.image.bitmap.Bitmap32
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import nightfunkin.core.Constants
import nightfunkin.core.KnfFiles
import nightfunkin.core.KnfFmt
import nightfunkin.platform.AndroidContextHolder
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Android's native movie player: ExoPlayer renders into a [TextureView] placed
 * above the game's surface, and decodes the audio itself.
 *
 * Android ships no way to pipe an MP4/MKV into raw RGBA frames at gameplay
 * framerate without writing a MediaCodec pipeline, but it does ship a complete
 * media stack. ExoPlayer handles every container the game uses - H.264 in MP4
 * with AAC, and H.264 in Matroska with Vorbis (`stress`, `guns`, `ugh`, ...) -
 * with hardware decoding, so the cutscene simply plays itself and the game only
 * has to know when it started and when it ended.
 *
 * The view is an ordinary Android `TextureView` in the activity's content view:
 * a `SurfaceView` (KorGE's game surface) is composed *behind* the window, so any
 * regular view in the hierarchy draws on top of it. The video keeps FNF's
 * geometry (`scale = min(gameWidth / videoWidth, gameHeight / videoHeight)`,
 * centered), including the letterbox bars on ultra-wide screens.
 *
 * Because the surface covers the game, the pause menu cannot be drawn over it:
 * `pauseForMenu()` captures the current frame and the game draws that still
 * instead, exactly like FNF's frozen movie behind `PauseSubState`.
 *
 * All ExoPlayer and view calls happen on the main thread; the game thread only
 * reads the `@Volatile` state flags and hands work over with `Handler.post`.
 */
internal class AndroidVideoController(private val videoPath: String) : NativeVideoController {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    override var isReady: Boolean = false
        private set

    @Volatile
    override var error: String? = null
        private set

    @Volatile
    override var ended: Boolean = false
        private set

    @Volatile
    override var positionMs: Double = 0.0
        private set

    @Volatile
    override var durationMs: Double = 0.0
        private set

    @Volatile
    private var pendingStill: NativeVideoStill? = null

    // ---- main-thread-only state ------------------------------------------

    private var player: ExoPlayer? = null
    private var root: FrameLayout? = null
    private var texture: TextureView? = null

    private var videoWidth = 0.0
    private var videoHeight = 0.0
    private var firstFrameRendered = false
    private var frameLogged = false
    private var surfaceLogged = false
    private var wantVisible = false
    /** True once playback was requested, i.e. the game scene is actually live. */
    private var playRequested = false
    /** `VideoCutscene.hideVideo()`: the movie runs but must not be on screen. */
    private var hiddenByGame = false
    private var tickerRunning = false

    private var readySignal: CompletableDeferred<Boolean>? = null

    private val positionTicker = object : Runnable {
        override fun run() {
            val p = player ?: return
            positionMs = p.currentPosition.coerceAtLeast(0L).toDouble()
            // Also self-heals the "first frame callback was missed" case: once
            // playback has really advanced, the picture is worth showing.
            applyVisibility()
            main.postDelayed(this, POSITION_POLL_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    val d = player?.duration ?: 0L
                    if (d > 0L) durationMs = d.toDouble()
                    isReady = true
                    // `onVideoSizeChanged` normally arrives before playback, but
                    // the track's own format is authoritative and available right
                    // here; without it the TextureView would stay at 1x1.
                    applyFormatSize()
                    layoutVideo()
                    readySignal?.complete(error == null)
                }

                Player.STATE_ENDED -> {
                    ended = true
                    positionMs = durationMs
                }
            }
        }

        override fun onPlayerError(e: PlaybackException) {
            error = e.message ?: ("playback error ${e.errorCodeName}")
            readySignal?.complete(false)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val ratio = videoSize.pixelWidthHeightRatio.toDouble().takeIf { it > 0.0 } ?: 1.0
            videoWidth = videoSize.width * ratio
            videoHeight = videoSize.height.toDouble()
            layoutVideo()
        }

        override fun onRenderedFirstFrame() {
            firstFrameRendered = true
            if (!frameLogged) {
                frameLogged = true
                println(
                    "KNF: video first frame surface=${texture?.isAvailable} " +
                        "size=${texture?.width}x${texture?.height}"
                )
            }
            applyVisibility()
        }
    }

    override suspend fun prepare(): Boolean {
        val path = KnfFiles.fileOrNull(videoPath)
        if (path == null) {
            error = "missing asset '${KnfFiles.file(videoPath).absolutePath()}'"
            return false
        }

        val activity = AndroidContextHolder.activity()
        if (activity == null) {
            error = "no activity to host the video surface"
            return false
        }

        val started = try {
            runOnMain { attach(activity, path.absolutePath()) }
        } catch (t: Throwable) {
            error = t.message ?: t::class.simpleName ?: "could not create the video player"
            false
        }
        if (!started) {
            if (error == null) error = "could not create the video player"
            return false
        }

        // Waiting for READY covers the slow part (opening the hardware codec,
        // reading the container index) inside the loading screen instead of
        // during the first second of the cutscene.
        val ready = withTimeoutOrNull(PREPARE_TIMEOUT_MS) { awaitReady() } ?: false
        if (!ready) {
            if (error == null) error = "the video player did not become ready"
            return false
        }
        return true
    }

    override fun play() {
        main.post {
            if (player == null) return@post
            // The scene is live now: ExoPlayer may have already rendered its
            // first frame while the loading screen was still up, so the surface
            // is only allowed on screen from here (unless a scripted prelude
            // asked for it to stay hidden).
            playRequested = true
            if (!hiddenByGame) wantVisible = true
            ended = false
            applyFormatSize()
            layoutVideo()
            player?.play()
            startTicker()
            applyVisibility()
            if (!surfaceLogged) {
                surfaceLogged = true
                println(
                    "KNF: video surface available=${texture?.isAvailable} " +
                        "size=${texture?.width}x${texture?.height} " +
                        "video=${KnfFmt.decimals(videoWidth, 0)}x${KnfFmt.decimals(videoHeight, 0)} " +
                        "container=${root?.width}x${root?.height}"
                )
            }
        }
    }

    override fun pauseForMenu() {
        // The capture has to run on the main thread, but the game expects the
        // still on the very next frame; `takeStill` polls until it is ready.
        main.post {
            val p = player ?: return@post
            p.pause()
            pendingStill = captureStill()
            wantVisible = false
            applyVisibility()
        }
    }

    override fun takeStill(): NativeVideoStill? {
        val still = pendingStill ?: return null
        pendingStill = null
        return still
    }

    override fun resume() {
        setVisible(true)
        main.post {
            if (player == null) return@post
            playRequested = true
            ended = false
            // A capture that the game never picked up must not resurface on the
            // next pause.
            pendingStill = null
            player?.play()
            startTicker()
            applyVisibility()
        }
    }

    override fun restart() {
        setVisible(true)
        main.post {
            if (player == null) return@post
            playRequested = true
            ended = false
            pendingStill = null
            player?.seekTo(0L)
            player?.play()
            startTicker()
            applyVisibility()
        }
    }

    override fun setVisible(visible: Boolean) {
        // Main-thread state, so it is set inside the posted block: the handler
        // preserves the order with the `play()` that follows `start()`.
        main.post {
            hiddenByGame = !visible
            wantVisible = visible
            applyVisibility()
        }
    }

    override fun stop() {
        main.post {
            player?.pause()
            pendingStill = null
            hiddenByGame = true
            wantVisible = false
            applyVisibility()
        }
    }

    override fun close() {
        main.post {
            main.removeCallbacks(positionTicker)
            tickerRunning = false
            root?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
            root = null
            texture = null
            player?.release()
            player = null
            pendingStill = null
            wantVisible = false
            playRequested = false
            hiddenByGame = false
            firstFrameRendered = false
            isReady = false
        }
    }

    override fun debugState(): String =
        "native ready=$isReady ended=$ended " +
            "pos=${KnfFmt.decimals(positionMs, 0)} dur=${KnfFmt.decimals(durationMs, 0)} " +
            "still=${pendingStill != null} error=$error"

    // ---- internals --------------------------------------------------------

    private suspend fun awaitReady(): Boolean {
        if (isReady || error != null) return isReady && error == null
        val deferred = CompletableDeferred<Boolean>()
        readySignal = deferred
        // The player may have become ready between the check and publishing the
        // signal; the listener only fires once.
        if (isReady || error != null) deferred.complete(isReady && error == null)
        return deferred.await()
    }

    private fun attach(activity: Activity, absolutePath: String): Boolean {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: run {
            error = "the activity has no content view"
            return false
        }

        val container = FrameLayout(activity).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // Hidden with `alpha`, never with `visibility`: the TextureView must
            // keep drawing so its texture layer (and with it ExoPlayer's output
            // surface) exists from the first frame on.
            alpha = 0f
            // Touches must reach the game underneath (pause / skip controls).
            setOnTouchListener { _, _ -> false }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutVideo() }
        }
        val surface = TextureView(activity).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnTouchListener { _, _ -> false }
        }
        // Full-window until the track size is known: a 1x1 surface gives the
        // decoder a 1x1 buffer, and `layoutVideo` replaces this the moment the
        // movie's aspect is available.
        container.addView(surface, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))

        val exo = try {
            ExoPlayer.Builder(activity).build()
        } catch (t: Throwable) {
            error = t.message ?: "could not create ExoPlayer"
            return false
        }
        // The listener has to be installed *before* the view is attached: a
        // `TextureView` only reports an already-available surface texture to a
        // listener that was set at attach time.
        exo.setVideoTextureView(surface)
        exo.addListener(listener)
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(absolutePath))))
        exo.playWhenReady = false
        player = exo

        val added = runCatching {
            content.addView(container, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }.isSuccess
        if (!added) {
            error = "could not add the video surface to the window"
            player = null
            runCatching { exo.release() }
            return false
        }
        root = container
        texture = surface
        exo.prepare()
        return true
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        main.post(positionTicker)
    }

    private fun applyVisibility() {
        val view = root ?: return
        // `alpha`, not `visibility`: a hidden view stops drawing, which means the
        // TextureView never creates its texture layer - ExoPlayer then has nowhere
        // to render the movie (audio still plays) and never reports a video size.
        val shown = wantVisible && playRequested && error == null &&
            (firstFrameRendered || positionMs >= FIRST_FRAME_FALLBACK_MS)
        view.alpha = if (shown) 1f else 0f
    }

    /**
     * Reads the video track's size from the player's own format list.
     *
     * `onVideoSizeChanged` is the normal source, but it is only delivered by the
     * video renderer, so a movie whose output surface is not ready yet would
     * keep the view at 1x1. The track format is known as soon as the player is
     * ready, so the letterbox is correct from the first frame either way.
     */
    private fun applyFormatSize() {
        if (videoWidth > 0.0 && videoHeight > 0.0) return
        val tracks = player?.currentTracks ?: return
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO || !group.isSupported) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                if (format.width <= 0 || format.height <= 0) continue
                val ratio = format.pixelWidthHeightRatio.toDouble().takeIf { it > 0.0 } ?: 1.0
                videoWidth = format.width * ratio
                videoHeight = format.height.toDouble()
                return
            }
        }
    }

    /**
     * FNF's `VideoCutscene` sizing: fit the movie inside the game area with
     * `min(...)`, then center it. The game area itself is `SHOW_ALL`-centered in
     * the window, so centering inside the container is equivalent.
     */
    private fun layoutVideo() {
        val view = texture ?: return
        val container = root ?: return
        if (videoWidth <= 0.0 || videoHeight <= 0.0) return
        val width = container.width.toDouble()
        val height = container.height.toDouble()
        if (width <= 0.0 || height <= 0.0) return

        val gameWidth = Constants.GAME_WIDTH.toDouble()
        val gameHeight = Constants.GAME_HEIGHT.toDouble()
        val scale = min(width / gameWidth, height / gameHeight)
        val gameScreenWidth = gameWidth * scale
        val gameScreenHeight = gameHeight * scale
        val fit = min(gameScreenWidth / videoWidth, gameScreenHeight / videoHeight)
        val outWidth = (videoWidth * fit).roundToInt().coerceAtLeast(1)
        val outHeight = (videoHeight * fit).roundToInt().coerceAtLeast(1)

        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.width != outWidth || params.height != outHeight) {
            params.width = outWidth
            params.height = outHeight
            view.layoutParams = params
        }
    }

    /**
     * Reads the currently displayed frame back into a [Bitmap32] the game can
     * draw itself. `TextureView.getBitmap` is a GPU to CPU copy of the view's
     * size, so this runs once per pause, not per frame.
     */
    private fun captureStill(): NativeVideoStill? {
        val texture = this.texture ?: return null
        val container = this.root ?: return null
        if (!texture.isAvailable) return null
        val width = container.width.toDouble()
        val height = container.height.toDouble()
        if (width <= 0.0 || height <= 0.0) return null

        val source = try {
            texture.bitmap
        } catch (_: Throwable) {
            null
        } ?: return null
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) {
            source.recycle()
            return null
        }
        val pixels = IntArray(w * h)
        try {
            source.getPixels(pixels, 0, w, 0, 0, w, h)
        } catch (_: Throwable) {
            source.recycle()
            return null
        }
        source.recycle()

        // Android hands pixels back as `0xAARRGGBB`; a korlibs `Bitmap32` stores
        // `0xAABBGGRR` (little-endian R,G,B,A), so red and blue have to swap.
        for (n in pixels.indices) {
            val color = pixels[n]
            pixels[n] = (color and 0xFF00FF00.toInt()) or
                ((color and 0xFF) shl 16) or
                ((color ushr 16) and 0xFF)
        }

        val bitmap = Bitmap32(w, h)
        bitmap.premultiplied = true
        pixels.copyInto(bitmap.ints)

        val scale = min(width / Constants.GAME_WIDTH, height / Constants.GAME_HEIGHT)
        return NativeVideoStill(
            bitmap = bitmap,
            x = Constants.GAME_WIDTH / 2.0,
            y = Constants.GAME_HEIGHT / 2.0,
            width = w / scale,
            height = h / scale,
        )
    }

    private suspend fun <T> runOnMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        return suspendCancellableCoroutine { cont ->
            main.post {
                try {
                    cont.resume(block())
                } catch (t: Throwable) {
                    cont.resumeWith(Result.failure(t))
                }
            }
        }
    }

    private companion object {
        /** Hardware codec start-up is fast, but a cold device can take a while. */
        const val PREPARE_TIMEOUT_MS = 15_000L

        /** Position sampling for the debug log; playback itself is clocked by ExoPlayer. */
        const val POSITION_POLL_MS = 250L

        /**
         * Shows the picture after this much *playing* time even if the renderer's
         * first-frame callback was missed; by then the surface holds real frames.
         */
        const val FIRST_FRAME_FALLBACK_MS = 400.0

        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    }
}

actual fun createNativeVideoController(videoPath: String): NativeVideoController? =
    AndroidVideoController(videoPath)
