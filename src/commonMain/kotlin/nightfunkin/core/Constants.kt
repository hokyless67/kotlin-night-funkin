package nightfunkin.core

/**
 * Kotlin Night Funkin — global game constants.
 *
 * Values are ported 1:1 from Friday Night Funkin' 0.8.6
 * (`source/funkin/util/Constants.hx`, `source/funkin/Conductor.hx`,
 *  `source/funkin/play/notes/Strumline.hx`, `source/funkin/util/GRhythmUtil.hx`)
 * so that behaviour matches the original game.
 */
object Knf {
    const val NAME = "Kotlin Night Funkin"
    const val SHORT = "KNF"
    const val VERSION = "0.1.0"
}

object Constants {
    // ---- viewport -------------------------------------------------------
    /**
     * The resolution FNF is authored at (Flixel's `FlxG.initialWidth/Height`).
     *
     * Every layout constant in the game is expressed in this space. [GAME_WIDTH]
     * and [GAME_HEIGHT] start equal to it and only grow on mobile, where the
     * game area follows the screen's aspect ratio (see `KnfViewport`).
     */
    const val BASE_WIDTH = 1280
    const val BASE_HEIGHT = 720

    /**
     * Size of the game area in game units.
     *
     * FNF's `FullScreenScaleMode` makes the logical game resolution match the
     * device instead of letterboxing 16:9: on a 20:9 phone the area becomes
     * 1600x720, so full-screen art covers the screen and the camera shows a
     * little more of the stage on both sides. Everything that centres itself on
     * these values therefore stays centred, exactly like FNF's use of
     * `FlxG.width/FlxG.height`.
     */
    var GAME_WIDTH: Int = BASE_WIDTH
        private set
    var GAME_HEIGHT: Int = BASE_HEIGHT
        private set

    /**
     * FNF's `FullScreenScaleMode.gameCutoutSize.x`: how much wider the game
     * area is than the 16:9 reference, in game units. Zero at 16:9, so every
     * `+ cutout` offset below is a no-op on desktop.
     */
    val GAME_CUTOUT_WIDTH: Int get() = GAME_WIDTH - BASE_WIDTH

    /**
     * FNF's `FullScreenScaleMode.gameNotchSize.x`: the width of the camera
     * cutout, converted to game units.
     *
     * The game area deliberately covers the whole screen (a landscape notch
     * sits on the display's short edge, which Android lets the window extend
     * into), so UI pinned to the left/right edges has to step inward by this
     * amount or it disappears under the cutout. FNF adds it to left-anchored
     * elements and subtracts it from right-anchored ones; see
     * `FreeplayState`/`ResultState`. Zero on desktop.
     */
    var GAME_NOTCH_WIDTH: Int = 0
        private set

    /** [GAME_NOTCH_WIDTH] as a `Double`, for layout math. */
    val GAME_NOTCH: Double get() = GAME_NOTCH_WIDTH.toDouble()

    /** Called once by `KnfViewport` before any scene is built. */
    fun applyViewport(width: Int, height: Int, notchWidth: Int) {
        GAME_WIDTH = width
        GAME_HEIGHT = height
        GAME_NOTCH_WIDTH = notchWidth
    }

    /** FNF's `Constants.PIXEL_ART_SCALE`. */
    const val PIXEL_ART_SCALE = 6.0

    // ---- time -----------------------------------------------------------
    const val MS_PER_SEC = 1000f
    const val SECS_PER_MIN = 60f
    const val STEPS_PER_BEAT = 4

    /** MP3 decoder latency baked into the format (0.8.6 Constants.MP3_DELAY_MS). */
    const val MP3_DELAY_MS = 528f / 44100f * MS_PER_SEC

    // ---- strumline geometry ---------------------------------------------
    const val STRUMLINE_SIZE = 104
    const val NOTE_SPACING = STRUMLINE_SIZE + 8
    const val KEY_COUNT = 4
    const val INITIAL_OFFSET = -0.275f * STRUMLINE_SIZE
    const val NUDGE = 2f
    const val STRUMLINE_X_OFFSET = 48f
    const val STRUMLINE_Y_OFFSET = 24f
    const val NOTE_SPLASH_CAP = 6

    /** Note scroll is LINEAR in milliseconds — this is the core FNF constant. */
    const val PIXELS_PER_MS = 0.45f

    /** Hit window half-width in ms (both directions). */
    const val HIT_WINDOW_MS = 160f

    // ---- judgement (PBOT1, the 0.8.6 default scoring system) -------------
    const val PBOT1_PERFECT_THRESHOLD = 5.0f
    const val PBOT1_MISS_THRESHOLD = 160.0f
    const val PBOT1_SICK_THRESHOLD = 45.0f
    const val PBOT1_GOOD_THRESHOLD = 90.0f
    const val PBOT1_BAD_THRESHOLD = 135.0f
    const val PBOT1_SHIT_THRESHOLD = 160.0f

    const val PBOT1_MAX_SCORE = 500
    const val PBOT1_SCORING_OFFSET = 54.99f
    const val PBOT1_SCORING_SLOPE = 0.080f
    const val PBOT1_MIN_SCORE = 9f
    const val PBOT1_MISS_SCORE = -100
    /** `Constants.SCORE_GHOST_MISS_PENALTY`: ghost taps cost points, not tallies. */
    const val SCORE_GHOST_MISS_PENALTY = -10f

    // ---- health ---------------------------------------------------------
    const val HEALTH_MAX = 2.0f
    const val HEALTH_STARTING = HEALTH_MAX / 2.0f
    const val HEALTH_MIN = 0.0f

    const val HEALTH_KILLER_BONUS = 2.0f / 100f * HEALTH_MAX
    const val HEALTH_SICK_BONUS = 1.5f / 100f * HEALTH_MAX
    const val HEALTH_GOOD_BONUS = 0.75f / 100f * HEALTH_MAX
    const val HEALTH_BAD_BONUS = 0.0f
    const val HEALTH_SHIT_BONUS = -1.0f / 100f * HEALTH_MAX
    const val HEALTH_HOLD_BONUS_PER_SECOND = 6.0f / 100f * HEALTH_MAX
    const val HEALTH_MISS_PENALTY = -4.0f / 100f * HEALTH_MAX
    const val HEALTH_GHOST_MISS_PENALTY = -4.0f / 100f * HEALTH_MAX
    const val HEALTH_HOLD_DROP_PENALTY_PER_SECOND = 0f
    const val HEALTH_MINE_PENALTY = -15.0f / 100f * HEALTH_MAX

    // ---- results screen rank thresholds (Constants.hx) -------------------
    const val RANK_PERFECT_THRESHOLD = 1.00f
    const val RANK_EXCELLENT_THRESHOLD = 0.90f
    const val RANK_GREAT_THRESHOLD = 0.80f
    const val RANK_GOOD_THRESHOLD = 0.60f

    /** Health bar smoothing (0.8.6 PlayState.updateHealthBar). */
    const val HEALTH_LERP_RATE = 0.15f

    /**
     * FNF's `Constants.GHOST_TAP_DELAY` (3/8 s).
     *
     * A tap into empty air is forgiven for this long after a real hit, so
     * hitting a note and immediately mashing does not cost you.
     */
    const val GHOST_TAP_DELAY = 3.0 / 8.0

    // ---- camera ---------------------------------------------------------
    const val DEFAULT_BOP_INTENSITY = 1.015f
    const val HUD_ZOOM_INTENSITY = 0.015f * 2.0f
    const val DEFAULT_ZOOM_RATE = 4
    const val DEFAULT_ZOOM_OFFSET = 0
    const val DEFAULT_CAMERA_FOLLOW_RATE = 0.04f
    const val MAX_RELATIVE_CAM_ZOOM = 1.35f
    const val BOP_DECAY_PER_FRAME = 0.95

    // ---- conductor drift correction (0.8.6 PlayState.update) ------------
    /** Exponential ease ratio used to pull the Conductor onto the audio clock. */
    const val MUSIC_EASE_RATIO = 42.0
    const val CONDUCTOR_DRIFT_THRESHOLD = 65.0

    // ---- song ids --------------------------------------------------------
    val DEFAULT_DIFFICULTY_LIST = listOf("easy", "normal", "hard")

    /** `Constants.DEFAULT_DIFFICULTY_LIST_FULL`: drives `listAllDifficulties`'s sort order. */
    val DEFAULT_DIFFICULTY_LIST_FULL = listOf("easy", "normal", "hard", "erect", "nightmare")
    val DEFAULT_VARIATION_LIST = listOf("default", "erect", "pico", "bf")
    const val DEFAULT_DIFFICULTY = "normal"
    const val DEFAULT_VARIATION = "default"

    /** Number of strumlines; `data / 4` selects the strumline, `data % 4` the arrow. */
    const val STRUMLINE_SIZE_DEFAULT = 4
}

enum class NoteDirection(val id: Int, val colorName: String) {
    LEFT(0, "purple"),
    DOWN(1, "blue"),
    UP(2, "green"),
    RIGHT(3, "red");

    val color: Int
        get() = when (this) {
            LEFT -> 0xFFFF22AA.toInt()
            DOWN -> 0xFF00EEFF.toInt()
            UP -> 0xFF00CC00.toInt()
            RIGHT -> 0xFFCC1111.toInt()
        }

    companion object {
        fun of(id: Int): NoteDirection = entries[id.coerceIn(0, 3)]
        val ORDERED: List<NoteDirection> get() = entries.toList()
    }
}
