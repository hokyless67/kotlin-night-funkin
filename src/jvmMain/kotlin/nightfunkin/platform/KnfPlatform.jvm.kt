package nightfunkin.platform

actual object KnfPlatform {
    private val ROOT_CANDIDATES = listOf(
        "Funkin-0.8.6-Source/assets",
        "assets",
        "../assets",
    )

    actual fun assetRootCandidates(): List<String> = listOfNotNull(
        KnfEnv["KNF_ASSETS"],
        System.getProperty("knf.assets"),
    ).filter { it.isNotBlank() } + ROOT_CANDIDATES

    actual fun userFilesDir(): String = System.getProperty("user.home") ?: "."

    private var hinted = false

    actual fun handleAssetsNotFound() {
        if (hinted) return
        hinted = true
        println(
            "KNF: could not locate the FNF asset root. Tried:\n" +
                assetRootCandidates().joinToString("\n") { "  - $it" } +
                "\nPut the 'assets' folder next to the game, or set KNF_ASSETS=<path>"
        )
    }

    /**
     * `KNF_MOBILE=1` turns the desktop build into a mobile emulator: the
     * on-screen touch overlay appears (the mouse emulates a finger) and the
     * video backend behaves like mobile, skipping cutscenes. Asset and save
     * locations stay desktop, so you can test with your local asset tree.
     */
    actual val isMobile: Boolean get() = KnfEnv["KNF_MOBILE"] == "1"

    actual val isAndroid: Boolean get() = false
}
