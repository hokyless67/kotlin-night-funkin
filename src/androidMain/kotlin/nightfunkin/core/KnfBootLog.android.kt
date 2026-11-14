package nightfunkin.core

import android.util.Log
import nightfunkin.platform.KnfEnv
import nightfunkin.platform.KnfPlatform
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * Android build of the boot log.
 *
 * `adb logcat` is not always available (a phone plugged into a machine that
 * cannot run adb, MIUI's "USB debugging (Security settings)" prompt, a user who
 * simply cannot read the console), yet phone-only failures - a scene that ends
 * up as a black screen because its `sceneInit` threw, a texture that the driver
 * refused - are exactly the ones that need a trace.
 *
 * So stdout/stderr are mirrored into `Documents/KNF/knf.log`, next to the asset
 * folder the user already knows. The file is recreated on every launch, so it
 * always contains the current run, and every line still goes to logcat. The
 * uncaught-exception handler appends the full stack trace.
 */
actual object KnfBootLog {
    private var installed = false

    /** Kept open for the process lifetime; `System.out` writes here. */
    private var fileStream: PrintStream? = null

    private fun logFile(): File? {
        // The asset root is `<public Documents>/KNF/files/assets`, and the
        // public Documents folder is the one the user can open from a file
        // manager or over USB. `userFilesDir()` (app-scoped) is the fallback
        // when shared storage is not writable.
        val candidates = buildList {
            val assets = KnfPlatform.assetRootCandidates().firstOrNull { it.contains("/KNF/") }
            if (assets != null) {
                val root = File(assets).parentFile?.parentFile // .../KNF
                if (root != null) add(File(root, "knf.log"))
            }
            add(File(KnfPlatform.userFilesDir(), "knf.log"))
        }
        for (file in candidates) {
            val ok = runCatching {
                file.parentFile?.mkdirs()
                if (file.exists()) file.delete()
                file.createNewFile()
            }.getOrDefault(false)
            if (ok && file.canWrite()) return file
        }
        return null
    }

    actual fun install() {
        if (installed) return
        installed = true
        val file = logFile()
        val fileOut: OutputStream? = runCatching {
            file?.let { FileOutputStream(it, true) }
        }.getOrNull()
        val tee = PrintStream(TeeOutputStream(System.out, fileOut), true, "UTF-8")
        fileStream = tee
        System.setOut(tee)
        System.setErr(tee)

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            mark("UNCAUGHT EXCEPTION in ${thread?.name}")
            error(throwable)
            fileOut?.let { runCatching { it.flush() } }
        }

        mark("=== KNF boot ===")
        if (file != null) println("KNF: log file ${file.absolutePath}")
        runCatching {
            if (KnfEnv.hasConfigFile) {
                println("KNF: knf.cfg keys = ${KnfEnv.configKeys.sorted().joinToString(", ")}")
            }
        }
    }

    actual fun mark(message: String) {
        println("KNF-BOOT: $message")
    }

    actual fun error(throwable: Throwable) {
        Log.e("KNF", "uncaught exception", throwable)
        throwable.printStackTrace()
        fileStream?.flush()
    }

    /**
     * Copies everything written to stdout into the log file as well.
     *
     * Must never throw: it runs inside `println` from any thread, including the
     * GL and audio threads.
     */
    private class TeeOutputStream(
        private val console: OutputStream?,
        private val file: OutputStream?,
    ) : OutputStream() {
        override fun write(b: Int) {
            runCatching { console?.write(b) }
            runCatching { file?.write(b) }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            runCatching { console?.write(b, off, len) }
            runCatching { file?.write(b, off, len) }
        }

        override fun flush() {
            runCatching { console?.flush() }
            runCatching { file?.flush() }
        }

        override fun close() {
            flush()
        }
    }
}
