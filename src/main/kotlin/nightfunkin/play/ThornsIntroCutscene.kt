package nightfunkin.play

import korlibs.image.color.Colors
import korlibs.korge.view.Container
import korlibs.korge.view.SolidRect
import korlibs.korge.view.solidRect
import nightfunkin.core.Constants
import nightfunkin.core.KnfFiles
import nightfunkin.core.SparrowSprite
import nightfunkin.core.TextureAtlas

/**
 * `thorns.hxc`'s Senpai explosion prelude, followed by the data-driven
 * conversation.
 *
 * The original script shows a red screen, fades Senpai in in discrete steps,
 * plays `Senpai_Dies`, panics the camera white, then starts the dialogue while
 * the game camera is faded to black. KNF has no camera filters, so the white and
 * black passes are screen-space overlays; the ordering and timings are the same.
 */
class ThornsIntroCutscene(private val host: CutsceneHost) : SongCutscene {

    private enum class State { FADE_IN, ANIMATION, DIALOGUE, DONE }

    private val dialogue = DialogueCutscene(host, "thorns")

    private var root: Container? = null
    private var red: SolidRect? = null
    private var white: SolidRect? = null
    private var senpai: SparrowSprite? = null
    private var senpaiFrames: List<TextureAtlas.AtlasFrame> = emptyList()

    private var state = State.FADE_IN
    private var time = 0.0
    private var fadeElapsed = 0.0
    private var animationStart = 0.0

    private var deathSound: String? = null
    private var soundStarted = false
    private var blackStarted = false
    private var blackElapsed = 0.0
    private var blackDuration = BLACK_FADE_TIME
    private var dialogueScheduled = false
    private var dialogueAt = -1.0

    private var whiteStarted = false
    private var whiteDone = false
    private var whiteElapsed = 0.0

    override suspend fun start() {
        host.setHudVisible(false)
        dialogue.prepare()

        val root = Container()
        this.root = root
        host.addCutsceneView(root)

        // `new FunkinSprite(-20,-20).makeSolidColor(width*1.5,height*1.5,0xFFFFFFFF)`
        val red = root.solidRect(
            Constants.GAME_WIDTH * 1.5,
            Constants.GAME_HEIGHT * 1.5,
            Colors["#FF1B31"],
        )
        red.x = -20.0
        red.y = -20.0
        this.red = red

        val atlasPath = KnfFiles.resolve("weeb/senpaiCrazy", ".xml", "week6")
        val atlas = atlasPath?.let { TextureAtlas.tryLoad(it) }
        senpaiFrames = atlas?.framesByPrefix("Senpai Pre Explosion instance 1").orEmpty()
        if (atlas != null && senpaiFrames.isNotEmpty()) {
            val sprite = SparrowSprite(atlas, senpaiFrames, fps = 24.0, loop = false)
            sprite.playing = false
            sprite.scaleX = Constants.PIXEL_ART_SCALE
            sprite.scaleY = Constants.PIXEL_ART_SCALE
            val frame = senpaiFrames.first()
            val w = frame.logicalWidth * Constants.PIXEL_ART_SCALE
            val h = frame.logicalHeight * Constants.PIXEL_ART_SCALE
            // `setGraphicSize(Std.int(width * PIXEL_ART_SCALE)); updateHitbox();
            //  screenCenter(); x += width / 5;`
            sprite.x = (Constants.GAME_WIDTH - w) / 2.0 + w / 5.0
            sprite.y = (Constants.GAME_HEIGHT - h) / 2.0
            sprite.alpha = 0.0
            root.addChild(sprite)
            senpai = sprite
        } else {
            println("KNF: thorns cutscene asset missing: weeb/senpaiCrazy")
        }

        // Camera filters are unavailable; this overlay is faded in where the
        // script applies `RetroCameraFade.fadeWhite`/`fadeBlack`.
        white = root.solidRect(
            Constants.GAME_WIDTH.toDouble(),
            Constants.GAME_HEIGHT.toDouble(),
            Colors.WHITE,
        ).also { it.alpha = 0.0 }

        host.showBlackScreen()
        host.setBlackScreenAlpha(0.0)
        deathSound = runCatching { host.preloadSound("sounds/Senpai_Dies") }.getOrNull()
        println("KNF: thorns prelude started (sound=${deathSound ?: "<missing>"})")
    }

    override fun update(dt: Double): Boolean {
        if (state == State.DONE) return true
        time += dt

        when (state) {
            State.FADE_IN -> {
                fadeElapsed += dt
                val sprite = senpai
                if (sprite != null) {
                    while (fadeElapsed >= FADE_STEP) {
                        fadeElapsed -= FADE_STEP
                        sprite.alpha = (sprite.alpha + 0.15).coerceAtMost(1.0)
                        if (sprite.alpha >= 1.0) {
                            startAnimation()
                            break
                        }
                    }
                } else {
                    // Missing art: skip straight to the dialogue timing.
                    startAnimation()
                }
            }

            State.ANIMATION -> {
                updateAnimation(dt)
            }

            State.DIALOGUE -> {
                if (dialogue.update(dt)) {
                    state = State.DONE
                    return true
                }
            }

            State.DONE -> return true
        }
        return false
    }

    override fun destroy() {
        state = State.DONE
        dialogue.destroy()
        root?.removeFromParent()
        root = null
        red = null
        white = null
        senpai = null
        host.hideBlackScreen()
    }

    private fun startAnimation() {
        if (state != State.FADE_IN) return
        state = State.ANIMATION
        animationStart = time
        senpai?.alpha = 1.0
        senpai?.play(senpaiFrames, restart = true)
        soundStarted = true
        deathSound?.let { host.playSound(it, 1.0) }
        println("KNF: thorns prelude Senpai animation (t=${"%.2f".format(time)})")
    }

    private fun updateAnimation(dt: Double) {
        // Fade to white 3.2 s after the animation/sound start.
        if (!whiteStarted && !whiteDone && time - animationStart >= WHITE_DELAY) {
            whiteStarted = true
            whiteElapsed = 0.0
        }
        if (whiteStarted) {
            whiteElapsed += dt
            val t = (whiteElapsed / WHITE_FADE_TIME).coerceIn(0.0, 1.0)
            white?.alpha = t
            if (t >= 1.0) {
                whiteStarted = false
                whiteDone = true
                senpai?.removeFromParent()
                senpai = null
                red?.removeFromParent()
                red = null
                // The original immediately fades the white camera to black.
                beginBlackFade(WHITE_BLACK_FADE_TIME)
            }
        }

        // `FunkinSound.playOnce(..., onComplete)` starts the dialogue 1.6 s
        // after `Senpai_Dies` ends. The white flash's black fade is visual only
        // and must not schedule the conversation early.
        val soundEnded = !soundStarted || deathSound == null ||
            (time - animationStart >= SOUND_START_GRACE && !host.isSoundPlaying())
        if (soundEnded && !dialogueScheduled) {
            scheduleDialogueAfterSound()
        }
        if (blackStarted) {
            blackElapsed += dt
            val t = (blackElapsed / blackDuration.coerceAtLeast(0.0001)).coerceIn(0.0, 1.0)
            host.setBlackScreenAlpha(t)
        }

        if (dialogueAt >= 0.0 && time >= dialogueAt) {
            state = State.DIALOGUE
            red?.removeFromParent()
            red = null
            senpai?.removeFromParent()
            senpai = null
            white?.alpha = 0.0
            host.setBlackScreenAlpha(1.0)
            dialogue.show()
            println("KNF: thorns prelude handing off to dialogue (t=${"%.2f".format(time)})")
        }
    }

    private fun beginBlackFade(duration: Double) {
        if (blackStarted) return
        blackStarted = true
        blackElapsed = 0.0
        blackDuration = duration
        // Remove the red flash exactly when the original removes it.
        red?.removeFromParent()
        red = null
        host.setBlackScreenAlpha(0.0)
        println("KNF: thorns prelude black fade (t=${"%.2f".format(time)})")
    }

    private fun scheduleDialogueAfterSound() {
        dialogueScheduled = true
        // The white flash may already have faded to black; in that case keep
        // its 0.8 s fade and just wait out the remaining sound.
        if (!blackStarted) beginBlackFade(BLACK_FADE_TIME)
        dialogueAt = time + DIALOGUE_DELAY
        println("KNF: thorns prelude dialogue scheduled (t=${"%.2f".format(time)}, at=${"%.2f".format(dialogueAt)})")
    }

    private companion object {
        const val FADE_STEP = 0.3
        const val WHITE_DELAY = 3.2
        const val WHITE_FADE_TIME = 1.4
        const val WHITE_BLACK_FADE_TIME = 0.8
        const val BLACK_FADE_TIME = 1.4
        const val DIALOGUE_DELAY = 1.6
        const val SOUND_START_GRACE = 0.1
    }
}
