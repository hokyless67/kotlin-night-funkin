package nightfunkin.platform

/**
 * Raw device vibration, the platform half of `funkin.util.HapticUtil`.
 *
 * FNF talks to Lime's `extension.haptics.Haptic`, which only exists on mobile
 * builds (`#if FEATURE_HAPTICS`). KNF keeps the same split: [HapticUtil]
 * decides *when* and *how strong* to buzz, and this object performs the call
 * with whatever the OS offers.
 *
 * Desktop and iOS report `available = false` until they get an implementation;
 * everything above this layer already guards on [HapticUtil.hapticsAvailable].
 */
expect object KnfHaptics {
    /** True when the device can vibrate right now. */
    val available: Boolean

    /**
     * A single pulse.
     *
     * @param durationSeconds length of the pulse.
     * @param amplitude 0..1 intensity (`VibrationEffect` amplitude 0..255).
     */
    fun vibrate(durationSeconds: Double, amplitude: Double)

    /**
     * A pulse train that starts immediately and stops after the last entry.
     *
     * @param durationsSeconds one entry per pulse.
     * @param amplitudes 0..1 per pulse.
     */
    fun vibrateWaveform(durationsSeconds: DoubleArray, amplitudes: DoubleArray)

    /** Stops any vibration this object started. */
    fun cancel()
}
