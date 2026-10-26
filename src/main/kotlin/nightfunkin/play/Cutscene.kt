package nightfunkin.play

import korlibs.korge.view.View

/** What the PlayScene should do once the cutscene's update returns `true`. */
enum class CutsceneKind {
    /** FNF's `CutsceneType.STARTING`: call `startCountdown()`. */
    STARTING,

    /** FNF's `CutsceneType.ENDING`: call `endSong(true)`. */
    ENDING,
}

/**
 * A scripted, non-interactive sequence that takes over a song's intro or ending.
 *
 * FNF implements these as HScript classes under
 * `assets/preload/scripts/songs/` and `assets/preload/scripts/stages/`. They hook
 * `onCountdownStart`/`onSongEnd`, call `event.cancel()` and later call
 * `startCountdown()`/`endSong(true)` themselves. KNF has no HScript runtime, so
 * the same sequences are ported to Kotlin and registered by song id in
 * [SongCutscenes].
 *
 * A cutscene owns no gameplay state: it drives the scene through [CutsceneHost]
 * and returns `true` from [update] once it is over.
 */
interface SongCutscene {
    val kind: CutsceneKind get() = CutsceneKind.STARTING

    /** Loads assets and puts the initial frame on screen. Runs inside `sceneInit`. */
    suspend fun start()

    /** Advances the cutscene. @return true when it is over. */
    fun update(dt: Double): Boolean

    /** Releases anything the cutscene added to the scene. */
    fun destroy() {}
}

/**
 * The PlayScene operations available to a cutscene.
 *
 * Keeping this small stops a cutscene from reaching into gameplay internals and
 * mirrors the handful of `PlayState` helpers the original scripts use
 * (`camHUD.visible`, `cameraFollowPoint`, `FunkinSound.playOnce`, ...).
 */
interface CutsceneHost {
    fun setHudVisible(visible: Boolean)

    /** Shows the HUD and fades it from transparent over [durationSeconds]. */
    fun fadeHudIn(durationSeconds: Double)

    /** Screen-space black rectangle above everything, like the scripts' `blackScreen`. */
    fun showBlackScreen()
    fun hideBlackScreen()
    fun setBlackScreenAlpha(alpha: Double)

    /**
     * Adds a screen-space view above the black screen (used by video playback).
     * The caller owns removal through [removeCutsceneView].
     */
    fun addCutsceneView(view: View)
    fun removeCutsceneView(view: View)

    /** FNF's `cameraFollowPoint.setPosition(...)` + `resetCamera()` + direct zoom. */
    fun focusCamera(x: Double, y: Double, zoom: Double)
    fun setCameraZoom(zoom: Double)

    /**
     * Adds a view to the stage's z-sorted sprite group. Used by stage-script
     * cutscenes whose actors sit in stage space and must follow the camera.
     */
    fun addStageView(view: View, zIndex: Int)
    fun removeStageView(view: View)

    /** Hides/shows a stage character, as the stage scripts do during cutscenes. */
    fun setCharacterVisible(slot: String, visible: Boolean)

    /** Top-left stage position of a character (`getBoyfriend().x/.y`). */
    fun characterPosition(slot: String): Pair<Double, Double>?

    /** Stage `zIndex` of a character, used to rebuild relative actor order. */
    fun characterZIndex(slot: String): Int

    /** A character's `cameraFocusPoint`, including stage camera offsets. */
    fun characterCameraFocus(slot: String): Pair<Double, Double>?

    /** `PlayState.vocals.opponentVolume`; the philly cutscene mutes a corpse. */
    fun setOpponentVocalsVolume(volume: Double)

    /** Current camera state, read before scripted camera moves. */
    val cameraZoom: Double
    val cameraFocusX: Double
    val cameraFocusY: Double

    /** The stage's `cameraZoom`, used by `VideoCutscene.finishVideo`. */
    val stageZoom: Double

    /** Character animations used by the `2hot` ending choreography. */
    fun playCharacterAnimation(slot: String, animation: String, forceRestart: Boolean = true)
    fun danceCharacter(slot: String, forceRestart: Boolean = true)
    fun isCharacterAnimationFinished(slot: String): Boolean

    /** FNF's per-character `danceEvery = 0` override. */
    fun setCharacterDancesOnBeat(slot: String, enabled: Boolean)

    /**
     * Resolves an extension-less sound stem the way `Paths.sound` does (stage
     * library first, then `shared`, then `preload`) and preloads it.
     * @return the resolved path, or null if it is not available.
     */
    suspend fun preloadSound(stem: String): String?

    /**
     * Resolves a looping music track for a cutscene. The caller owns playback
     * (dialogue uses its own [nightfunkin.audio.MusicPlayer]).
     */
    suspend fun preloadMusic(stem: String): String?

    /** True when FNF's CONFIRM / CUTSCENE_ADVANCE was pressed this frame. */
    val advancePressed: Boolean

    fun playSound(path: String, volume: Double = 1.0)

    /** Fire-and-forget sound, used by dialogue text blips. */
    fun playSfx(path: String, volume: Double = 1.0)
    fun isSoundPlaying(): Boolean
}

/**
 * Registry of ported intro cutscenes, the Kotlin equivalent of the per-song
 * `Song` scripts in FNF. Add a new entry here and implement [SongCutscene].
 */
object SongCutscenes {
    /**
     * Ported `onCountdownStart` cutscenes.
     *
     * `storyMode` mirrors FNF's `PlayStatePlaylist.isStoryMode`. Several songs
     * deliberately skip their video in Freeplay; KNF exposes `KNF_STORY=1` for
     * testing those story-mode sequences without a full campaign port.
     */
    fun createIntro(
        songId: String,
        stageId: String,
        variation: String,
        storyMode: Boolean,
        host: CutsceneHost,
    ): SongCutscene? {
        // Song scripts win over stage scripts, matching FNF's dispatch order.
        when (songId) {
            "winter-horrorland" -> return WinterHorrorlandCutscene(host)
            "guns" -> if (storyMode) {
                return VideoSongCutscene(host, "videos/videos/gunsCutscene.mkv")
            }
            "ugh" -> if (storyMode) {
                return VideoSongCutscene(host, "videos/videos/ughCutscene.mkv")
            }
            "stress" -> {
                // `stress-pico.hxc` always plays its video; `stress.hxc` only
                // plays in Story Mode.
                if (variation == "pico") {
                    return VideoSongCutscene(host, "videos/videos/stressPicoCutscene.mkv")
                }
                if (storyMode) {
                    return VideoSongCutscene(host, "videos/videos/stressCutscene.mkv")
                }
            }
            "darnell" -> if (storyMode) {
                // `darnell.hxc` plays the movie before the countdown. (Its
                // optional in-game can-kicking prelude is a separate script.)
                return VideoSongCutscene(
                    host,
                    "videos/videos/darnellCutscene.mp4",
                    initialCameraZoom = 1.3,
                )
            }
            // Week 6 uses data-driven conversations instead of video files.
            // `roses-pico.hxc` and `senpai-pico.hxc` always play their mix's
            // dialogue; the default variations are story-mode only.
            "roses" -> {
                if (variation == "pico") {
                    return DialogueCutscene(
                        host,
                        "roses-pico",
                        beforeSoundStem = "sounds/ANGRY_TEXT_BOX",
                    )
                }
                if (storyMode) {
                    return DialogueCutscene(
                        host,
                        "roses",
                        beforeSoundStem = "sounds/ANGRY_TEXT_BOX",
                    )
                }
            }
            "senpai" -> {
                if (variation == "pico") {
                    return SenpaiIntroCutscene(
                        host,
                        "senpai-pico",
                        musicStopSpeakers = setOf("senpai-bwuh"),
                        musicRestartSpeakers = setOf("senpai"),
                    )
                }
                if (storyMode) return SenpaiIntroCutscene(host, "senpai")
            }
            "thorns" -> if (storyMode) return ThornsIntroCutscene(host)
        }
        // Stage-script cutscenes. `phillyTrainErect.hxc` runs whenever the
        // player character is `pico-playable`; every chart on this stage is a
        // Pico Mix, so the variation check is equivalent.
        if (stageId == "phillyTrainErect" && variation == "pico") {
            return PhillyTrainErectCutscene(host)
        }
        // The remaining stage scripts (sserafim) need their custom shader
        // pipelines and are not ported yet.
        return null
    }

    /** Ported `onSongEnd` cutscenes (`CutsceneType.ENDING`). */
    fun createEnding(
        songId: String,
        stageId: String,
        variation: String,
        storyMode: Boolean,
        host: CutsceneHost,
    ): SongCutscene? {
        if (!storyMode) return null
        when (songId) {
            "blazin" -> return VideoSongCutscene(
                host,
                "videos/videos/blazinCutscene.mp4",
                CutsceneKind.ENDING,
            )
            "2hot" -> return TwoHotEndingCutscene(host)
        }
        return null
    }
}

/**
 * `winter-horrorland.hxc` -> `playHorrorStartCutscene`.
 *
 * The original sequence:
 * 1. cancel the countdown and hide the HUD;
 * 2. show a black screen for 0.1 s;
 * 3. jump the camera to the severed head on top of `mallEvil`'s tree
 *    (400, -2050), zoom 2.5, and play `Lights_Turn_On`;
 * 4. when the sound ends, fade the HUD back in over two seconds and start the
 *    countdown.
 *
 * The camera is deliberately left on the tree; the chart's first `FocusCamera`
 * event (3 s in) pans it back to the characters.
 */
class WinterHorrorlandCutscene(private val host: CutsceneHost) : SongCutscene {

    private var time = 0.0
    private var started = false
    private var soundPath: String? = null
    private var soundStartedAt = 0.0

    override suspend fun start() {
        host.setHudVisible(false)
        host.showBlackScreen()
        soundPath = host.preloadSound("sounds/Lights_Turn_On")
        println("KNF: Winter Horrorland cutscene sound: ${soundPath ?: "<missing>"}")
    }

    override fun update(dt: Double): Boolean {
        time += dt

        if (!started) {
            if (time < BLACK_SCREEN_SECONDS) return false
            started = true
            host.hideBlackScreen()
            // `cameraFollowPoint.setPosition(400, -2050); resetCamera(); zoom = 2.5`
            host.focusCamera(TREE_FOCUS_X, TREE_FOCUS_Y, CUTSCENE_ZOOM)

            val path = soundPath
            if (path == null) {
                // The original would wait forever for a missing sound file;
                // going straight to the countdown keeps the song playable.
                host.fadeHudIn(HUD_FADE_SECONDS)
                return true
            }
            host.playSound(path, 1.0)
            soundStartedAt = time
            return false
        }

        // `FunkinSound.playOnce(..., onComplete)` -> wait for the channel to end.
        // The short grace period covers the frame between creating the channel
        // and the audio backend reporting it as playing.
        if (time - soundStartedAt >= SOUND_START_GRACE_SECONDS && !host.isSoundPlaying()) {
            host.fadeHudIn(HUD_FADE_SECONDS)
            println("KNF: Winter Horrorland cutscene finished after ${"%.2f".format(time)}s")
            return true
        }
        return false
    }

    private companion object {
        /** `new FlxTimer().start(0.1, ...)` */
        const val BLACK_SCREEN_SECONDS = 0.1
        const val TREE_FOCUS_X = 400.0
        const val TREE_FOCUS_Y = -2050.0
        const val CUTSCENE_ZOOM = 2.5

        /** `TWEEN_DURATION` in the original script. */
        const val HUD_FADE_SECONDS = 2.0

        const val SOUND_START_GRACE_SECONDS = 0.1
    }
}
