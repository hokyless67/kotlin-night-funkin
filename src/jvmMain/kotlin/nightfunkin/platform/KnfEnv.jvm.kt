package nightfunkin.platform

actual object KnfEnv {
    actual operator fun get(name: String): String? = System.getenv(name)
}
