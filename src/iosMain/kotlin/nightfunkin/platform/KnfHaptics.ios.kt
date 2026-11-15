package nightfunkin.platform

/**
 * iOS haptics are not wired yet: Lima's extension used `CoreHaptics` on
 * iPhone, and `UIImpactFeedbackGenerator`/`CoreHaptics` need a UIImpact
 * generator bound to the view controller. Until then the platform reports
 * `available = false`, so `HapticUtil` stays silent on iOS.
 */
actual object KnfHaptics {
    actual val available: Boolean get() = false

    actual fun vibrate(durationSeconds: Double, amplitude: Double) = Unit

    actual fun vibrateWaveform(durationsSeconds: DoubleArray, amplitudes: DoubleArray) = Unit

    actual fun cancel() = Unit
}
