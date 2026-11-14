package nightfunkin.platform

actual object KnfProfiler {
    /** Stack sampling needs java.lang.Thread; not available on Kotlin/Native. */
    actual fun start(targetThreadName: String) = Unit

    actual fun startWatchdog(targetThreadName: String) = Unit

    actual fun frame() = Unit
}
