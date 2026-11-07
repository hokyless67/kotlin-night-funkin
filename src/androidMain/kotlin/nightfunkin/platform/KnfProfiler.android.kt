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
}
