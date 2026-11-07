package nightfunkin.play

/**
 * `senpai.hxc` / `senpai-pico.hxc`'s `transitionToDialogue`:
 *
 * 1. hold a hard black screen for 0.25 s;
 * 2. start on black and fade the game **in** from black over 2 s
 *    (`RetroCameraFade.fadeBlack` starts with a -255 offset and steps to 0);
 * 3. start the conversation once the game is fully visible.
 *
 * KNF cannot use Flixel's camera filters, so the fade is a screen-space black
 * rectangle. It stays below the dialogue's own root, which is mounted last.
 */
class SenpaiIntroCutscene(
    private val host: CutsceneHost,
    conversationId: String,
    musicStopSpeakers: Set<String> = emptySet(),
    musicRestartSpeakers: Set<String> = emptySet(),
) : SongCutscene {

    private val dialogue = DialogueCutscene(
        host,
        conversationId,
        musicStopSpeakers = musicStopSpeakers,
        musicRestartSpeakers = musicRestartSpeakers,
    )

    private var time = 0.0
    private var fadeStarted = false
    private var dialogueShown = false
    private var done = false

    override suspend fun start() {
        host.setHudVisible(false)
        dialogue.prepare()
        host.showBlackScreen()
        host.setBlackScreenAlpha(1.0)
        println("KNF: senpai transition started")
    }

    override fun update(dt: Double): Boolean {
        if (done) return true
        time += dt

        if (!fadeStarted && time >= BLACK_HOLD_SECONDS) {
            fadeStarted = true
            // `RetroCameraFade.fadeBlack` starts fully black and steps toward
            // the game, so keep the overlay opaque rather than revealing the
            // stage instantly and then darkening it.
            host.setBlackScreenAlpha(1.0)
        }
        if (fadeStarted && !dialogueShown) {
            val t = ((time - BLACK_HOLD_SECONDS) / FADE_SECONDS).coerceIn(0.0, 1.0)
            val step = kotlin.math.floor(t * FADE_STEPS) / FADE_STEPS
            host.setBlackScreenAlpha(1.0 - step)
            if (t >= 1.0) {
                dialogueShown = true
                host.setBlackScreenAlpha(0.0)
                dialogue.show()
                println("KNF: senpai transition handing off to dialogue")
            }
        }

        if (dialogueShown && dialogue.update(dt)) {
            done = true
            return true
        }
        return false
    }

    override val pauseMode: CutscenePauseMode
        get() = if (dialogueShown) dialogue.pauseMode else CutscenePauseMode.NONE

    override fun pause() {
        if (dialogueShown) dialogue.pause()
    }

    override fun resume() {
        if (dialogueShown) dialogue.resume()
    }

    override fun skip() {
        if (dialogueShown) dialogue.skip()
    }

    override fun restart() {
        if (dialogueShown) dialogue.restart()
    }

    override fun destroy() {
        done = true
        dialogue.destroy()
        host.hideBlackScreen()
    }

    private companion object {
        const val BLACK_HOLD_SECONDS = 0.25
        const val FADE_SECONDS = 2.0

        /** `RetroCameraFade.fadeBlack(camGame, 12, 2)`. */
        const val FADE_STEPS = 12.0
    }
}
