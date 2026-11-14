package nightfunkin.platform

/**
 * Poor man's profiler/watchdog for the render thread.
 *
 * [start] runs a sampling profiler that prints a histogram of the deepest
 * korlibs frames - shows where the render loop spends its CPU on average.
 *
 * [startWatchdog] watches the game's render thread and, when no frame has been
 * completed for longer than a frame budget, prints the stack it is sampling.
 * That catches stalls a frame timer cannot attribute: the render pass, a texture
 * upload, a driver call, or a stop-the-world pause (the sampler itself is
 * starved there, so it reports what it saw just before the pause).
 *
 * [frame] marks a completed frame; call it from the main loop every frame.
 */
expect object KnfProfiler {
    fun start(targetThreadName: String)

    /** Watches the thread whose name starts with [targetThreadName] (mobile debug builds). */
    fun startWatchdog(targetThreadName: String)

    /** Marks that a game frame completed. Cheap; called from the render loop. */
    fun frame()
}
