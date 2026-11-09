package nightfunkin.core

/**
 * Minimal, crash-proof boot diagnostics.
 *
 * On a phone there is no Xcode console, and a Kotlin/Native uncaught exception
 * aborts the process immediately — so the only way to find out where the app
 * died is to leave a trail on disk. The iOS implementation writes
 * `Documents/KNF/boot.log` (visible in the Files app thanks to
 * `UIFileSharingEnabled`), redirects stdout/stderr to
 * `Documents/KNF/stdout.log` so KorGE's own log lines are captured too, and
 * installs an unhandled-exception hook that appends the full stack trace.
 *
 * Every method must be safe to call at any point during startup. Implementations
 * never throw.
 */
expect object KnfBootLog {
    /** Redirects logs and installs the crash hook. Idempotent; call once, first thing. */
    fun install()

    /** Appends one stage marker line. */
    fun mark(message: String)

    /** Appends an exception with its full stack trace. */
    fun error(throwable: Throwable)
}
