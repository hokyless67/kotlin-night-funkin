package nightfunkin.platform

/**
 * Read-only access to process environment variables, replacing the JVM-only
 * `System.getenv` calls across the game (debug/test hooks documented in the
 * README: `KNF_SONG`, `KNF_AUTOPLAY`, `KNF_TOUCH`, ...).
 */
expect object KnfEnv {
    operator fun get(name: String): String?
}
