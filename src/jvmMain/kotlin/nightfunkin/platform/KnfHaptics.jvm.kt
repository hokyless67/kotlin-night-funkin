package nightfunkin.platform

/**
 * Desktop has no haptics. FNF only compiles `HapticUtil` when
 * `FEATURE_HAPTICS` is defined (mobile targets), so the desktop build behaves
 * exactly like this: every call is a no-op.
 */
actual object KnfHaptics {
    actual val available: Boolean get() = false

    actual fun vibrate(durationSeconds: Double, amplitude: Double) = Unit

    actual fun vibrateWaveform(durationsSeconds: DoubleArray, amplitudes: DoubleArray) = Unit

    actual fun cancel() = Unit
}
