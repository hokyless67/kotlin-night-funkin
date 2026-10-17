package nightfunkin.play

import nightfunkin.core.Constants

/** Timing verdict for a note. Order matters: `>=` when compared in a `when`. */
enum class Judgement(val label: String, val color: Int) {
    SICK("sick", 0xFF3DD6D0.toInt()),
    GOOD("good", 0xFF66FF66.toInt()),
    BAD("bad", 0xFFFFAA00.toInt()),
    SHIT("shit", 0xFFFF6600.toInt()),
    MISS("miss", 0xFFFF0000.toInt()),
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
}
