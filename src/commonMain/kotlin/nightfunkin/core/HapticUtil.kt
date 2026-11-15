package nightfunkin.core

import nightfunkin.platform.KnfEnv
import nightfunkin.platform.KnfHaptics
import kotlin.math.ceil

/**
 * Port of FNF 0.8.6's `funkin.util.HapticUtil`.
 *
 * The Haxe class is the single gate between the game and the device's
 * vibration motor: every script, stage and UI call site goes through
 * `HapticUtil.vibrate` / `vibrateByPreset` / `increasingVibrate`.
 *
 * FNF hides all of it behind `Preferences.hapticsMode` (NONE / NOTES_ONLY /
 * ALL) and only compiles it with `FEATURE_HAPTICS`. KNF has no options menu
 * for it yet, so the `KNF_VIBRATION=1` environment flag stands in for
 * `HapticsMode.ALL` - off by default, like every other KNF debug hook.
 *
 * The pattern maths is kept verbatim, including the oddities the source has:
 * a period is split into `period / 2` pulses and the amplitude is multiplied
 * by `hapticsIntensityMultiplier` (1.0 here) and clamped to
 * [MAX_VIBRATION_AMPLITUDE].
 */
object HapticUtil {
    // `funkin.util.Constants`
    const val DEFAULT_VIBRATION_PERIOD = 0.1
    const val DEFAULT_VIBRATION_DURATION = 0.1
    const val MIN_VIBRATION_AMPLITUDE = 0.25
    const val DEFAULT_VIBRATION_AMPLITUDE = 0.5
    const val MAX_VIBRATION_AMPLITUDE = 1.0
    const val DEFAULT_VIBRATION_SHARPNESS = 1.0

    /** `Preferences.hapticsIntensityMultiplier`; KNF has no slider for it. */
    var intensityMultiplier: Double = 1.0

    /** `KNF_VIBRATION=1` replaces `Preferences.hapticsMode != NONE`. */
    private val enabled: Boolean by lazy { KnfEnv["KNF_VIBRATION"] == "1" }

    /** `HapticUtil.hapticsAvailable`. */
    val hapticsAvailable: Boolean
        get() = enabled && KnfHaptics.available

    /** A vibration preset, FNF's `VibrationPreset` typedef. */
    data class VibrationPreset(
        val period: Double = DEFAULT_VIBRATION_PERIOD,
        val duration: Double = DEFAULT_VIBRATION_DURATION,
        val amplitude: Double = DEFAULT_VIBRATION_AMPLITUDE,
        val sharpness: Double = DEFAULT_VIBRATION_SHARPNESS,
    )

    /** `HapticUtil.defaultVibrationPreset`. */
    val defaultVibrationPreset: VibrationPreset get() = VibrationPreset()

    /**
     * `HapticUtil.vibrate`.
     *
     * `sharpness` only reaches CoreHaptics; the Android backend ignores it,
     * exactly like FNF's own Lime extension does.
     */
    fun vibrate(
        period: Double = DEFAULT_VIBRATION_PERIOD,
        duration: Double = DEFAULT_VIBRATION_DURATION,
        amplitude: Double = DEFAULT_VIBRATION_AMPLITUDE,
        sharpness: Double = DEFAULT_VIBRATION_SHARPNESS,
    ) {
        if (!hapticsAvailable) return

        val amplitudeValue = (amplitude * intensityMultiplier)
            .coerceIn(0.0, MAX_VIBRATION_AMPLITUDE)
        if (amplitudeValue <= 0.0) return

        if (period > 0.0) {
            val durationPeriod = period / 2.0
            val count = ceil(duration / durationPeriod).toInt().coerceAtLeast(1)
            val durations = DoubleArray(count) { durationPeriod }
            val amplitudes = DoubleArray(count) { amplitudeValue }
            KnfHaptics.vibrateWaveform(durations, amplitudes)
        } else {
            KnfHaptics.vibrate(duration, amplitudeValue)
        }
    }

    /** `HapticUtil.vibrateByPreset`. */
    fun vibrateByPreset(preset: VibrationPreset? = null) {
        if (!hapticsAvailable) return
        val value = preset ?: defaultVibrationPreset
        vibrate(value.period, value.duration, value.amplitude, value.sharpness)
    }

    /**
     * `HapticUtil.increasingVibrate`: a ramp from [startAmplitude] to
     * [targetAmplitude] over [tweenDuration], then one strong pulse at twice
     * the target amplitude.
     *
     * The Haxe version drives the ramp from a `FlxTween.num` onUpdate, i.e. one
     * tiny vibration per rendered frame, followed by
     * `vibrate(DEFAULT_PERIOD, DEFAULT_DURATION, target * 2)` on completion.
     * Handing the whole ramp to the OS as a single waveform produces the same
     * feel without per-frame vibration calls.
     */
    fun increasingVibrate(
        startAmplitude: Double,
        targetAmplitude: Double,
        tweenDuration: Double = 1.0,
    ) {
        if (!hapticsAvailable) return
        KnfHaptics.cancel()

        val steps = RAMP_STEPS
        val stepDuration = tweenDuration.coerceAtLeast(0.0) / steps
        val rampDurations = DoubleArray(steps) { stepDuration }
        val rampAmplitudes = DoubleArray(steps) {
            val t = if (steps <= 1) 1.0 else it.toDouble() / (steps - 1)
            (startAmplitude + (targetAmplitude - startAmplitude) * t) * intensityMultiplier
        }

        // The completion pulse of the original: `period` 0.1 -> five 50 ms
        // pulses, at `targetAmplitude * 2`.
        val finalAmplitude = (targetAmplitude * 2.0).coerceIn(0.0, MAX_VIBRATION_AMPLITUDE)
        val finalPulses = ceil(DEFAULT_VIBRATION_DURATION / (DEFAULT_VIBRATION_PERIOD / 2.0)).toInt()
        val durations = DoubleArray(steps + finalPulses)
        val amplitudes = DoubleArray(steps + finalPulses)
        for (i in 0 until steps) {
            durations[i] = rampDurations[i]
            amplitudes[i] = rampAmplitudes[i]
        }
        for (i in 0 until finalPulses) {
            durations[steps + i] = DEFAULT_VIBRATION_PERIOD / 2.0
            amplitudes[steps + i] = finalAmplitude
        }
        KnfHaptics.vibrateWaveform(durations, amplitudes)
    }

    /** `amplitudeTween.cancel()` in the original. */
    fun cancel() {
        if (!hapticsAvailable) return
        KnfHaptics.cancel()
    }

    private const val RAMP_STEPS = 24
}
