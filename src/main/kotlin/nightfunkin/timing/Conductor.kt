package nightfunkin.timing

import nightfunkin.core.Constants
import kotlin.math.floor

/**
 * A BPM / time-signature change point.
 *
 * Ported from `SongTimeChange` in FNF 0.8.6 (`source/funkin/data/song/SongData.hx`).
 * [beatTime] is *accumulated quarter-note time*, filled in by
 * [Conductor.mapTimeChanges].
 */
class SongTimeChange(
    val timeStamp: Float,
    val bpm: Float,
    val timeSignatureNumerator: Int = 4,
    val timeSignatureDenominator: Int = 4,
    val beatTuplets: List<Int> = listOf(4, 4, 4, 4),
) {
    /** Accumulated beats at [timeStamp]; computed by [Conductor.mapTimeChanges]. */
    var beatTime: Float = 0f

    override fun toString() =
        "SongTimeChange(t=$timeStamp, bpm=$bpm, $timeSignatureNumerator/$timeSignatureDenominator)"
}

/**
 * The rhythm clock. Everything in KNF's gameplay is derived from here.
 *
 * Direct port of `funkin/Conductor.hx` from Friday Night Funkin' 0.8.6,
 * including the fractional `songPositionDelta` interpolation that keeps note
 * scrolling smooth between audio clock updates.
 */
class Conductor {

    /** Current song position in milliseconds (the single source of truth). */
    var songPosition: Float = 0f
        private set

    /** Sub-frame interpolation added on top of [songPosition]. */
    var songPositionDelta: Float = 0f
        private set

    val timeChanges: MutableList<SongTimeChange> = arrayListOf()

    var currentTimeChange: SongTimeChange? = null
        private set

    // ---- fractional and integer musical time ----------------------------
    var currentStepTime: Float = 0f
        private set
    var currentBeatTime: Float = 0f
        private set
    var currentMeasureTime: Float = 0f
        private set

    var currentStep: Int = 0
        private set
    var currentBeat: Int = 0
        private set
    var currentMeasure: Int = 0
        private set

    // ---- offsets --------------------------------------------------------
    var instrumentalOffset: Float = 0f
    var formatOffset: Float = 0f
    var globalOffset: Float = 0f

    val combinedOffset: Float get() = instrumentalOffset + formatOffset + globalOffset

    /** Chart-editor style BPM override; `null` uses the song's time changes. */
    var bpmOverride: Float? = null

    // ---- callbacks ------------------------------------------------------
    var onStepHit: (() -> Unit)? = null
    var onBeatHit: (() -> Unit)? = null
    var onMeasureHit: (() -> Unit)? = null

    private var oldStep = 0
    private var oldBeat = 0
    private var oldMeasure = 0
    private var prevTime = -1f

    // ---- derived timing -------------------------------------------------
    val bpm: Float get() = bpmOverride ?: currentTimeChange?.bpm ?: 100f
    val timeSignatureNumerator: Int get() = currentTimeChange?.timeSignatureNumerator ?: 4
    val timeSignatureDenominator: Int get() = currentTimeChange?.timeSignatureDenominator ?: 4

    val beatLengthMs: Float
        get() = (Constants.SECS_PER_MIN / bpm) * Constants.MS_PER_SEC * (4f / timeSignatureDenominator)

    val stepLengthMs: Float get() = beatLengthMs / Constants.STEPS_PER_BEAT

    val measureLengthMs: Float get() = beatLengthMs * timeSignatureNumerator

    val stepsPerMeasure: Int get() = timeSignatureNumerator * 4

    /** Smooth song time used for note positioning (FNF: `getTimeWithDelta`). */
    val timeWithDelta: Float get() = songPosition + songPositionDelta

    // ======================================================================
    // setup
    // ======================================================================

    fun reset() {
        songPosition = 0f
        songPositionDelta = 0f
        currentStepTime = 0f
        currentBeatTime = 0f
        currentMeasureTime = 0f
        currentStep = 0
        currentBeat = 0
        currentMeasure = 0
        currentTimeChange = null
        oldStep = 0; oldBeat = 0; oldMeasure = 0
        prevTime = -1f
        timeChanges.clear()
        timeChanges += SongTimeChange(0f, 100f)
        currentTimeChange = timeChanges[0]
    }

    fun forceBPM(bpm: Float?) {
        bpmOverride = bpm
    }

    /**
     * Sorts [changes] and precomputes each entry's accumulated [SongTimeChange.beatTime].
     * Ported from `Conductor.mapTimeChanges`.
     */
    fun mapTimeChanges(changes: List<SongTimeChange>) {
        timeChanges.clear()
        timeChanges += changes
            .sortedBy { it.timeStamp }
            .map { if (it.timeStamp < 0f) SongTimeChange(0f, it.bpm, it.timeSignatureNumerator, it.timeSignatureDenominator) else it }

        if (timeChanges.isEmpty()) {
            timeChanges += SongTimeChange(0f, 100f)
        }
        if (timeChanges[0].timeStamp != 0f) {
            timeChanges.add(0, SongTimeChange(0f, timeChanges[0].bpm))
        }

        timeChanges[0].beatTime = 0f
        for (i in 1 until timeChanges.size) {
            val prev = timeChanges[i - 1]
            val cur = timeChanges[i]
            cur.beatTime = round(
                prev.beatTime +
                    ((cur.timeStamp - prev.timeStamp) * prev.bpm / 60f / 1000f * (prev.timeSignatureDenominator / 4f)),
                4
            )
        }
        currentTimeChange = timeChanges[0]
    }

    // ======================================================================
    // per-frame update
    // ======================================================================

    /**
     * Advances the clock to [songPosMs] (already in milliseconds).
     *
     * @param applyOffsets whether [combinedOffset] should be added. FNF passes
     *   `false` when it has already folded the offset into the value it gives us.
     */
    fun update(songPosMs: Float, applyOffsets: Boolean = true) {
        var pos = songPosMs + if (applyOffsets) combinedOffset else 0f

        currentTimeChange = findTimeChange(pos)

        val tc = currentTimeChange!!
        currentStepTime = round(tc.beatTime * 4f + (pos - tc.timeStamp) / stepLengthMs, 6)
        currentBeatTime = currentStepTime / 4f
        currentMeasureTime = timeInMeasures(pos)

        currentStep = floor(currentStepTime).toInt()
        currentBeat = floor(currentBeatTime).toInt()
        currentMeasure = floor(currentMeasureTime).toInt()

        if (currentStep != oldStep) {
            oldStep = currentStep
            onStepHit?.invoke()
        }
        if (currentBeat != oldBeat) {
            oldBeat = currentBeat
            onBeatHit?.invoke()
        }
        if (currentMeasure != oldMeasure) {
            oldMeasure = currentMeasure
            onMeasureHit?.invoke()
        }

        if (prevTime != pos) {
            songPositionDelta = 0f
            prevTime = pos
            songPosition = pos
        }
    }

    /**
     * Sub-frame interpolation hook. Call once per frame *before* [update] with the
     * real frame delta so notes glide smoothly even if the audio clock is coarse.
     */
    fun accumulateDelta(dtSeconds: Float, pitch: Float = 1f) {
        songPositionDelta += dtSeconds * Constants.MS_PER_SEC * pitch
    }

    private fun findTimeChange(posMs: Float): SongTimeChange {
        var result = timeChanges[0]
        if (posMs > 0f) {
            for (i in timeChanges.indices) {
                if (posMs >= timeChanges[i].timeStamp) result = timeChanges[i] else break
            }
        }
        return result
    }

    fun timeChangeAt(ms: Float): SongTimeChange = findTimeChange(ms)

    // ======================================================================
    // conversions (all time-change aware, like FNF)
    // ======================================================================

    fun timeInMeasures(ms: Float): Float {
        var result = 0f
        var lastTimeChange: SongTimeChange? = null
        for (i in timeChanges.indices) {
            val tc = timeChanges[i]
            if (tc.timeStamp > ms) break
            lastTimeChange = tc
            result += (measureLengthMsAt(tc))
            if (i < timeChanges.size - 1 && timeChanges[i + 1].timeStamp <= ms) {
                val next = timeChanges[i + 1]
                val measureLen = measureLengthMsAt(tc)
                result += (next.timeStamp - tc.timeStamp) / measureLen
            }
        }
        if (lastTimeChange != null) {
            val tail = (ms - lastTimeChange.timeStamp) / measureLengthMsAt(lastTimeChange)
            return tail
        }
        return 0f
    }

    fun measureTimeInMs(measureTime: Float): Float {
        // Walk the time changes accumulating measures until we cover measureTime.
        var remaining = measureTime
        for (i in timeChanges.indices) {
            val tc = timeChanges[i]
            val measureLen = measureLengthMsAt(tc)
            val beatsHere = (tc.beatTime + (tc.timeStamp - tc.timeStamp)) // no-op, keeps intent clear
            if (remaining < 1f || i == timeChanges.size - 1) {
                return tc.timeStamp + remaining * measureLen
            }
            remaining -= 1f
        }
        return 0f
    }

    fun timeInSteps(ms: Float): Float {
        if (timeChanges.size <= 1) {
            val tc = timeChanges[0]
            return (ms - tc.timeStamp) / stepLengthMsAt(tc)
        }
        var result = 0f
        var lastTimeChange: SongTimeChange? = null
        for (i in timeChanges.indices) {
            val tc = timeChanges[i]
            if (tc.timeStamp > ms) break
            lastTimeChange = tc
            result += (tc.beatTime - timeChanges[0].beatTime) * 4f
            if (i < timeChanges.size - 1 && timeChanges[i + 1].timeStamp <= ms) {
                result += (timeChanges[i + 1].timeStamp - tc.timeStamp) / stepLengthMsAt(tc)
            }
        }
        val last = lastTimeChange ?: timeChanges[0]
        result += (ms - last.timeStamp) / stepLengthMsAt(last)
        return result
    }

    fun stepTimeInMs(stepTime: Float): Float {
        var remaining = stepTime
        for (i in timeChanges.indices) {
            val tc = timeChanges[i]
            val stepsFromStart = (tc.beatTime - timeChanges[0].beatTime) * 4f
            if (remaining < stepsFromStart || i == timeChanges.size - 1) {
                // interpolate backwards from this change point
                var acc = 0f
                for (j in 0..i) {
                    if (j == i) break
                    acc += (timeChanges[j + 1].timeStamp - timeChanges[j].timeStamp) / stepLengthMsAt(timeChanges[j])
                }
                return tc.timeStamp + (remaining - acc) * stepLengthMsAt(tc)
            }
            if (i < timeChanges.size - 1) {
                remaining -= (timeChanges[i + 1].timeStamp - tc.timeStamp) / stepLengthMsAt(tc)
            }
        }
        return 0f
    }

    fun beatTimeInMs(beatTime: Float): Float = stepTimeInMs(beatTime * 4f)

    // ---- per-change helpers ---------------------------------------------

    private fun beatLengthMsAt(tc: SongTimeChange): Float {
        val b = bpmOverride ?: tc.bpm
        return (Constants.SECS_PER_MIN / b) * Constants.MS_PER_SEC * (4f / tc.timeSignatureDenominator)
    }

    private fun stepLengthMsAt(tc: SongTimeChange): Float =
        beatLengthMsAt(tc) / Constants.STEPS_PER_BEAT

    private fun measureLengthMsAt(tc: SongTimeChange): Float =
        beatLengthMsAt(tc) * tc.timeSignatureNumerator

    private fun round(value: Float, decimals: Int): Float {
        var m = 1f
        repeat(decimals) { m *= 10f }
        return kotlin.math.round(value * m) / m
    }

    init {
        reset()
    }
}
