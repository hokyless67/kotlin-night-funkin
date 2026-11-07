package nightfunkin.play

import nightfunkin.core.KnfFmt
import nightfunkin.platform.KnfEnv

/**
 * FNF's `VideoCutscene` for a single movie file.
 *
 * Decoding happens on the platform [videoBackend] (the system `ffmpeg` on
 * desktop, nothing on mobile — those platforms skip the cutscene gracefully).
 * The movie's audio plays through [MusicPlayer]; its playback clock drives
 * which video frame is displayed, so the two do not drift.
 * `ffmpeg`/`ffprobe` can be overridden with `KNF_FFMPEG`/`KNF_FFPROBE` or
 * `-Dknf.ffmpeg=...`/`-Dknf.ffprobe=...` on desktop.
 */
class VideoSongCutscene(
    private val host: CutsceneHost,
    private val videoPath: String,
    override val kind: CutsceneKind = CutsceneKind.STARTING,
    /** `darnell.hxc` calls `tweenCameraZoom(1.3, 0, true)` before its video. */
    private val initialCameraZoom: Double? = null,
) : SongCutscene {

    private val player = VideoPlayback(videoPath)
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
                "(${player.width}x${player.height} @ ${KnfFmt.decimals(player.fps, 3)} fps, " +
                "${KnfFmt.decimals(player.durationMs / 1000.0, 2)}s, audio=${player.hasAudio})"
        )
    }

    override val pauseMode: CutscenePauseMode
        get() = if (prepared && !done && !fading) CutscenePauseMode.VIDEO else CutscenePauseMode.NONE

    /** `VideoCutscene.pauseVideo()`. */
    override fun pause() {
        player.pauseAudio()
    }

    /** `VideoCutscene.resumeVideo()`. */
    override fun resume() {
        player.resumeAudio()
    }

    /** `VideoCutscene.finishVideo()`: remove the video and start the 0.5 s fade. */
    override fun skip() {
        beginFade()
    }

    /** `VideoCutscene.restartVideo()`: seek back to the first frame. */
    override fun restart() {
        if (!prepared || done) return
        fading = false
        fadeElapsed = 0.0
        maxElapsedMs = 0.0
        lastDebugMs = -1.0
        host.setHudVisible(false)
        player.restart()
        started = true
        println("KNF: video cutscene restarted ($videoPath)")
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
            println("KNF: video t=${KnfFmt.decimals(maxElapsedMs, 0)}ms ${player.debugState()}")
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
        if (DEBUG) println("KNF: video beginFade t=${KnfFmt.decimals(maxElapsedMs, 0)}ms")
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

        val DEBUG = KnfEnv["KNF_DEBUG_VIDEO"] == "1"

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

    override val pauseMode: CutscenePauseMode get() = video.pauseMode
    override fun pause() = video.pause()
    override fun resume() = video.resume()
    override fun skip() = video.skip()

    /** `2hot.hxc` reuses `VideoCutscene.restartVideo`, which only rewinds the movie. */
    override fun restart() = video.restart()

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
                        "(${KnfFmt.decimals(startFocusX, 1)}, ${KnfFmt.decimals(startFocusY, 1)}) " +
                        "zoom ${KnfFmt.decimals(startZoom, 2)}"
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
            if (DEBUG) println("KNF: 2hot prelude showing video at t=${KnfFmt.decimals(time, 2)}s")
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

        val DEBUG = KnfEnv["KNF_DEBUG_VIDEO"] == "1"

        /** `FlxEase.quadInOut`. */
        fun quadInOut(t: Double): Double =
            if (t < 0.5) 2.0 * t * t else 1.0 - (-2.0 * t + 2.0) * (-2.0 * t + 2.0) / 2.0
    }
}
