package nightfunkin.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
actual object KnfEnv {
    actual operator fun get(name: String): String? =
        getenv(name)?.toKString()
}
