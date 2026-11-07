package nightfunkin.platform

/**
 * `System.getenv` exists on Android but is basically empty for apps - the
 * hooks stay available for rooted/debug builds and simply read as null
 * otherwise, which is exactly the non-hook behaviour the game expects.
 */
actual object KnfEnv {
    actual operator fun get(name: String): String? = System.getenv(name)
}
