package nightfunkin.core

/** Android: stdout ends up in logcat, which is enough for `adb logcat`. */
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
