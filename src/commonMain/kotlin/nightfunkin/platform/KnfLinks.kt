package nightfunkin.platform

/**
 * Opens a URL in the platform's browser (the merch link button on the main
 * menu). KorGE 6's `GameWindow` has no `openURL`, so this is an expect/actual.
 */
expect object KnfLinks {
    fun open(url: String)
}
