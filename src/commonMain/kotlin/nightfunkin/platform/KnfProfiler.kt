package nightfunkin.platform

/**
 * TEMP diagnostics: a poor man's profiler. Samples the stack of the game's
 * render thread from a daemon thread and prints a histogram of the deepest
 * korlibs frames — shows exactly where the render loop spends its CPU.
 */
expect object KnfProfiler {
    fun start(targetThreadName: String)
}
