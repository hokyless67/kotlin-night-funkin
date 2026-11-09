package nightfunkin.core

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlin.native.EagerInitialization
import kotlin.native.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import nightfunkin.platform.KnfPlatform
import platform.posix.FILE
import platform.posix.S_IRWXG
import platform.posix.S_IRWXO
import platform.posix.S_IRWXU
import platform.posix.dup2
import platform.posix.fclose
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.mkdir

/**
 * Runs during Kotlin/Native runtime initialization, i.e. before KorGE's
 * `applicationDidFinishLaunching` creates the window. That way a crash in the
 * iOS window/GL setup is visible too, not only crashes after `main()` starts.
 */
@OptIn(ExperimentalStdlibApi::class)
@Suppress("DEPRECATION")
@EagerInitialization
private val knfIosBootLogInstalled: String = run {
    KnfBootLog.install()
    "installed"
}

/**
 * iOS has no accessible console without a Mac, and an uncaught Kotlin/Native
 * exception kills the process instantly. So:
 *
 *  - `Documents/KNF/boot.log` records stage markers and exception traces.
 *  - `Documents/KNF/stdout.log` receives fds 1/2, i.e. everything KorGE prints.
 *    Kotlin/Native's `println` writes straight to `STDOUT_FILENO`, so a
 *    `dup2` redirect catches it immediately, without buffering problems.
 *
 * Both files are visible from the iOS Files app because the app declares
 * `UIFileSharingEnabled` / `LSSupportsOpeningDocumentsInPlace`.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual object KnfBootLog {
    private var installed = false

    /** Kept open for the whole process lifetime; closing it would close fd 1. */
    private var stdoutFile: CPointer<FILE>? = null

    private val dir: String get() = KnfPlatform.userFilesDir()
    private val bootLogPath: String get() = "$dir/boot.log"
    private val stdoutLogPath: String get() = "$dir/stdout.log"

    actual fun install() {
        if (installed) return
        installed = true
        ensureDir()

        stdoutFile = fopen(stdoutLogPath, "w")
        stdoutFile?.let { file ->
            val fd = fileno(file)
            if (fd >= 0) {
                dup2(fd, 1)
                dup2(fd, 2)
            }
        }

        setUnhandledExceptionHook { throwable ->
            mark("UNCAUGHT EXCEPTION\n" + throwable.stackTraceToString())
        }

        mark("=== KNF boot ===")
    }

    actual fun mark(message: String) {
        ensureDir()
        val file = fopen(bootLogPath, "a") ?: return
        try {
            val bytes = (message + "\n").encodeToByteArray()
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                    fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
                }
            }
        } finally {
            fclose(file)
        }
    }

    actual fun error(throwable: Throwable) {
        mark("EXCEPTION\n" + throwable.stackTraceToString())
    }

    private fun ensureDir() {
        val path = dir
        var index = path.indexOf('/')
        while (index > 0) {
            mkdir(path.substring(0, index), (S_IRWXU or S_IRWXG or S_IRWXO).convert())
            index = path.indexOf('/', index + 1)
        }
        mkdir(path, (S_IRWXU or S_IRWXG or S_IRWXO).convert())
    }
}
