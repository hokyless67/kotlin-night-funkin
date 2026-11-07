package nightfunkin.play

import nightfunkin.core.Constants

/** Timing verdict for a note. Order matters: `>=` when compared in a `when`. */
enum class Judgement(val label: String) {
    SICK("sick"),
    GOOD("good"),
    BAD("bad"),
    SHIT("shit"),
    MISS("miss"),
}

/**
 * FNF's `SaveScoreTallyData` / `Highscore.tallies`, the numbers the results
 * screen counts up. Kept mutable because gameplay updates them one note at a
 * time, exactly like `Highscore.tallies` in `PlayState.applyScore`.
 */
class ScoreTallies(
    var sick: Int = 0,
    var good: Int = 0,
    var bad: Int = 0,
    var shit: Int = 0,
    var missed: Int = 0,
    var combo: Int = 0,
    var maxCombo: Int = 0,
    var totalNotesHit: Int = 0,
    var totalNotes: Int = 0,
) {
    fun reset() {
        sick = 0
        good = 0
        bad = 0
        shit = 0
        missed = 0
        combo = 0
        maxCombo = 0
        totalNotesHit = 0
    }

    fun copy(): ScoreTallies = ScoreTallies(
        sick = sick,
        good = good,
        bad = bad,
        shit = shit,
        missed = missed,
        combo = combo,
        maxCombo = maxCombo,
        totalNotesHit = totalNotesHit,
        totalNotes = totalNotes,
    )

    /** `Scoring.tallyCompletion`: (sick + good - missed) / totalNotes. */
    val completion: Float
        get() {
            if (totalNotes <= 0) return 0f
            return ((sick + good - missed).toFloat() / totalNotes).coerceIn(0f, 1f)
        }
}

/**
 * FNF 0.8.6's `ScoringRank`. `key` is the JSON field name used by playable
 * character files (`perfectGold`, `loss`, ...), [assetName] is the name used by
 * the results screen assets.
 */
enum class ScoringRank(val key: String, val assetName: String) {
    PERFECT_GOLD("perfectGold", "PERFECT"),
    PERFECT("perfect", "PERFECT"),
    EXCELLENT("excellent", "EXCELLENT"),
    GREAT("great", "GREAT"),
    GOOD("good", "GOOD"),
    SHIT("loss", "LOSS"),
    ;

    /** `ScoringRank.getHorTextAsset`. */
    val horTextAsset: String get() = "resultScreen/rankText/rankScroll$assetName"

    /** `ScoringRank.getVerTextAsset`. */
    val verTextAsset: String get() = "resultScreen/rankText/rankText$assetName"

    /** Delays from `Scoring.hx`, in 24 fps frames, converted to seconds. */
    val musicDelay: Double
        get() = when (this) {
            PERFECT_GOLD, PERFECT -> 95.0 / 24.0
            EXCELLENT -> 0.0
            GREAT -> 5.0 / 24.0
            GOOD -> 3.0 / 24.0
            SHIT -> 2.0 / 24.0
        }

    val bfDelay: Double
        get() = when (this) {
            PERFECT_GOLD, PERFECT -> 95.0 / 24.0
            EXCELLENT -> 97.0 / 24.0
            else -> 95.0 / 24.0
        }

    val flashDelay: Double
        get() = when (this) {
            PERFECT_GOLD, PERFECT -> 129.0 / 24.0
            EXCELLENT -> 122.0 / 24.0
            GREAT -> 109.0 / 24.0
            GOOD -> 107.0 / 24.0
            SHIT -> 186.0 / 24.0
        }

    val highscoreDelay: Double
        get() = when (this) {
            PERFECT_GOLD, PERFECT -> 140.0 / 24.0
            EXCELLENT -> 140.0 / 24.0
            GREAT -> 129.0 / 24.0
            GOOD -> 127.0 / 24.0
            SHIT -> 207.0 / 24.0
        }
}

/**
 * Judgement and scoring, ported from FNF 0.8.6's `play/scoring/Scoring.hx`.
 *
 * The default (and only) scoring system in 0.8.6 is **PBOT1**: judgement and
 * score are computed independently from the same absolute timing error.
 * `judgeNote` picks the label, `scoreNote` runs a sigmoid for the points.
 */
object Scoring {

    val PBOT1_RANKS: List<String> = listOf("PERFECT", "EXCELLENT", "NORMAL", "SHIT")

    /** PBOT1 judgement thresholds, in ms of absolute timing error. */
    fun judgeNote(absTimingMs: Float): Judgement = when {
        absTimingMs <= Constants.PBOT1_SICK_THRESHOLD -> Judgement.SICK
        absTimingMs <= Constants.PBOT1_GOOD_THRESHOLD -> Judgement.GOOD
        absTimingMs <= Constants.PBOT1_BAD_THRESHOLD -> Judgement.BAD
        absTimingMs <= Constants.PBOT1_SHIT_THRESHOLD -> Judgement.SHIT
        else -> Judgement.MISS
    }

    /** PBOT1 sigmoid score: 500 at ≤5 ms, −100 on a miss, 9…500 in between. */
    fun scoreNote(absTimingMs: Float): Int {
        val t = absTimingMs
        if (t > Constants.PBOT1_MISS_THRESHOLD) return Constants.PBOT1_MISS_SCORE
        if (t < Constants.PBOT1_PERFECT_THRESHOLD) return Constants.PBOT1_MAX_SCORE
        val factor = 1.0 - (1.0 / (1.0 + Math.exp(-Constants.PBOT1_SCORING_SLOPE.toDouble() * (t.toDouble() - Constants.PBOT1_SCORING_OFFSET.toDouble()))))
        return (Constants.PBOT1_MAX_SCORE * factor + Constants.PBOT1_MIN_SCORE).toInt()
    }

    /** Health delta granted (or taken) for a judgement. */
    fun healthDelta(j: Judgement): Float = when (j) {
        Judgement.SICK -> Constants.HEALTH_SICK_BONUS
        Judgement.GOOD -> Constants.HEALTH_GOOD_BONUS
        Judgement.BAD -> Constants.HEALTH_BAD_BONUS
        Judgement.SHIT -> Constants.HEALTH_SHIT_BONUS
        Judgement.MISS -> Constants.HEALTH_MISS_PENALTY
    }

    /** Rough accuracy percentage for a completed song. */
    fun rankFor(accuracy: Float): String = when {
        accuracy >= 0.99f -> PBOT1_RANKS[0]
        accuracy >= 0.95f -> PBOT1_RANKS[1]
        accuracy >= 0.90f -> PBOT1_RANKS[2]
        else -> PBOT1_RANKS[3]
    }

    /**
     * FNF's `Scoring.calculateRank`.
     *
     * Perfect Gold is a full Sick clear; everything else is graded from
     * `tallyCompletion`. The original returns `null` for a chart with no notes,
     * which `ResultState` then treats as SHIT.
     */
    fun calculateRank(tallies: ScoreTallies?): ScoringRank {
        if (tallies == null || tallies.totalNotes <= 0) return ScoringRank.SHIT
        if (tallies.sick == tallies.totalNotes) return ScoringRank.PERFECT_GOLD

        val completion = tallies.completion
        return when {
            completion == Constants.RANK_PERFECT_THRESHOLD -> ScoringRank.PERFECT
            completion >= Constants.RANK_EXCELLENT_THRESHOLD -> ScoringRank.EXCELLENT
            completion >= Constants.RANK_GREAT_THRESHOLD -> ScoringRank.GREAT
            completion >= Constants.RANK_GOOD_THRESHOLD -> ScoringRank.GOOD
            else -> ScoringRank.SHIT
        }
    }
}
