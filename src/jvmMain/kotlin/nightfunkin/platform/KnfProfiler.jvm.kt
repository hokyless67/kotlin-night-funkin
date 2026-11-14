package nightfunkin.platform

import kotlin.concurrent.thread

actual object KnfProfiler {
    private val SKIP = listOf("nightfunkin.platform", "java.", "kotlin.")

    actual fun start(targetThreadName: String) {
        thread(name = "knf-profiler", isDaemon = true) {
            // Wait for the render thread to appear and match by prefix (the
            // thread is named "GLThread 68" — number included).
            val target = generateSequence {
                Thread.getAllStackTraces().keys.firstOrNull { it.name.startsWith(targetThreadName) }
            }.firstOrNull { it != null } ?: return@thread
            val histogram = LinkedHashMap<String, Int>()
            var samples = 0
            while (true) {
                val stack = target.stackTrace
                val frame = stack.firstOrNull {
                    it.className.startsWith("korlibs.") && SKIP.none { s -> it.className.startsWith(s) }
                }
                if (frame != null) {
                    val key = "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
                    histogram[key] = (histogram[key] ?: 0) + 1
                } else if (stack.isNotEmpty()) {
                    val top = stack[0]
                    histogram["<${top.className.substringAfterLast('.')}.${top.methodName}>"] =
                        (histogram["<${top.className.substringAfterLast('.')}.${top.methodName}>"] ?: 0) + 1
                }
                samples++
                if (samples >= 500) {
                    println("KNF: profiler samples=$samples top frames:")
                    histogram.entries.sortedByDescending { it.value }.take(12).forEach {
                        println("KNF:   ${it.value} ${it.key}")
                    }
                    histogram.clear()
                    samples = 0
                }
                Thread.sleep(4)
            }
        }
    }

    /** Desktop build of [startWatchdog]; see the Android actual. */
    actual fun startWatchdog(targetThreadName: String) {
        if (watchdogStarted) return
        watchdogStarted = true
        thread(name = "knf-watchdog", isDaemon = true) {
            var target: Thread? = null
            val waitStart = System.nanoTime()
            while (target == null && System.nanoTime() - waitStart < 30_000_000_000L) {
                target = runCatching {
                    Thread.getAllStackTraces().keys.firstOrNull { it.name.startsWith(targetThreadName) }
                }.getOrNull()
                if (target == null) Thread.sleep(50)
            }
            val thread = target ?: run {
                println("KNF: watchdog: no thread named '$targetThreadName', disabled")
                return@thread
            }
            println("KNF: watchdog on '${thread.name}'")
            var lastDumpAt = 0L
            var prevNow = 0L
            var prevStack = emptyArray<StackTraceElement>()
            var lastFrame = -1
            var frameSince = 0L
            var dumps = 0
            while (dumps < WATCHDOG_DUMPS) {
                val now = System.nanoTime()
                val stack = runCatching { thread.stackTrace }.getOrDefault(emptyArray())

                if (prevNow != 0L &&
                    now - prevNow >= WATCHDOG_STALL_NS &&
                    now - lastDumpAt >= WATCHDOG_GAP_NS
                ) {
                    lastDumpAt = now
                    dumps++
                    println("KNF_STALL gap=${(now - prevNow) / 1_000_000}ms (all threads stopped), stack before it:")
                    prevStack.take(WATCHDOG_FRAMES).forEach { println("KNF_STALL   at $it") }
                }

                val frames = frameCounter
                if (frames != lastFrame) {
                    lastFrame = frames
                    frameSince = now
                } else if (now - frameSince >= WATCHDOG_STALL_NS && now - lastDumpAt >= WATCHDOG_GAP_NS) {
                    lastDumpAt = now
                    dumps++
                    println("KNF_STUCK frame stalled ${(now - frameSince) / 1_000_000}ms:")
                    stack.take(WATCHDOG_FRAMES).forEach { println("KNF_STUCK   at $it") }
                }
                prevNow = now
                prevStack = stack
                Thread.sleep(8)
            }
            println("KNF: watchdog finished ($dumps dumps)")
        }
    }

    private var watchdogStarted = false

    @Volatile
    private var frameCounter = 0

    actual fun frame() {
        frameCounter++
    }

    private const val WATCHDOG_STALL_NS = 100_000_000L
    private const val WATCHDOG_GAP_NS = 400_000_000L
    private const val WATCHDOG_DUMPS = 15
    private const val WATCHDOG_FRAMES = 16
}
