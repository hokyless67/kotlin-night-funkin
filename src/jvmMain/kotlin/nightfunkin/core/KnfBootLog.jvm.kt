package nightfunkin.core

/** Desktop: the console is visible, so markers just go to stdout. */
actual object KnfBootLog {
    actual fun install() = Unit

    actual fun mark(message: String) {
        println("KNF-BOOT: $message")
    }

    actual fun error(throwable: Throwable) {
        println("KNF-BOOT: uncaught exception")
        throwable.printStackTrace()
    }
}
