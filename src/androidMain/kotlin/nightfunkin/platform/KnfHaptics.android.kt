package nightfunkin.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.roundToInt

/**
 * Android vibration, the backend FNF's `extension.haptics.Haptic` uses on
 * phones and tablets.
 *
 * `Haptic.vibrateOneShot` / `vibratePattern` map onto
 * `VibrationEffect.createOneShot` and `createWaveform` on API 26+; the API 21-25
 * fallback can only do a plain `vibrate(millis)` because amplitude control
 * arrived with O.
 *
 * The `sharpness` argument of the Haxe API is ignored: it is a CoreHaptics
 * (`CHHapticEvent`) concept, and FNF's own Android extension drops it too.
 */
actual object KnfHaptics {
    private var cached: Vibrator? = null
    private var resolved = false

    private fun vibrator(): Vibrator? {
        if (resolved) return cached
        resolved = true
        cached = runCatching {
            val context = AndroidContextHolder.appContext() ?: return@runCatching null
            if (Build.VERSION.SDK_INT >= 31) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }.getOrNull()
        return cached
    }

    actual val available: Boolean
        get() = runCatching { vibrator()?.hasVibrator() == true }.getOrDefault(false)

    private fun amplitude255(amplitude: Double): Int =
        (amplitude.coerceIn(0.0, 1.0) * 255.0).roundToInt().coerceIn(0, 255)

    actual fun vibrate(durationSeconds: Double, amplitude: Double) {
        val device = vibrator() ?: return
        val ms = (durationSeconds * 1000.0).roundToInt().coerceAtLeast(1)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                device.vibrate(VibrationEffect.createOneShot(ms.toLong(), amplitude255(amplitude)))
            } else {
                @Suppress("DEPRECATION")
                device.vibrate(ms.toLong())
            }
        }
    }

    actual fun vibrateWaveform(durationsSeconds: DoubleArray, amplitudes: DoubleArray) {
        val device = vibrator() ?: return
        val count = minOf(durationsSeconds.size, amplitudes.size)
        if (count <= 0) return

        // A waveform entry of 0 ms is rejected by `VibrationEffect`; the Haxe
        // side can pass very small non-zero durations (0.01 s), so clamp the
        // timings but keep every pulse.
        val timings = LongArray(count) {
            (durationsSeconds[it] * 1000.0).roundToInt().toLong().coerceAtLeast(1L)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                val levels = IntArray(count) { amplitude255(amplitudes[it]) }
                device.vibrate(VibrationEffect.createWaveform(timings, levels, -1))
            } else {
                @Suppress("DEPRECATION")
                device.vibrate(timings.sum())
            }
        }
    }

    actual fun cancel() {
        runCatching { vibrator()?.cancel() }
    }
}
