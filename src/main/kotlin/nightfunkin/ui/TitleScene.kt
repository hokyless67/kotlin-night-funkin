package nightfunkin.ui

import korlibs.image.color.Colors
import korlibs.korge.scene.Scene
import korlibs.korge.view.SContainer
import korlibs.korge.view.addUpdater
import korlibs.korge.view.solidRect
import korlibs.korge.view.text
import kotlinx.coroutines.launch
import nightfunkin.audio.MusicPlayer
import nightfunkin.core.Constants
import nightfunkin.core.Knf
import nightfunkin.core.KnfFiles
import nightfunkin.core.KnfInput
import kotlin.time.Duration

/** Title screen: logo, subtitle, "press to continue". */
class TitleScene : Scene() {

    private var t = 0.0
    private var canAdvance = false
    private val music = MusicPlayer()

    override suspend fun SContainer.sceneInit() {
        solidRect(Constants.GAME_WIDTH * 2.0, Constants.GAME_HEIGHT * 2.0, Colors["#12121c"])
        solidRect(Constants.GAME_WIDTH * 2.0, 8.0, Colors["#ff22aa"])

        val logo = text(Knf.NAME.uppercase(), textSize = 72.0, color = Colors.WHITE)
        logo.x = Constants.GAME_WIDTH / 2.0 - logo.width / 2.0
        logo.y = 180.0
        addChild(logo)

        val sub = text(
            "a rhythm game written in Kotlin on KorGE",
            textSize = 24.0,
            color = Colors["#8b8b99"],
        )
        sub.x = Constants.GAME_WIDTH / 2.0 - sub.width / 2.0
        sub.y = 282.0
        addChild(sub)

        val prompt = text("press  Z  or  ENTER", textSize = 34.0, color = Colors["#66ff66"])
        prompt.x = Constants.GAME_WIDTH / 2.0 - prompt.width / 2.0
        prompt.y = 470.0
        addChild(prompt)

        val version = text("v${Knf.VERSION}  •  ${Knf.SHORT}", textSize = 18.0, color = Colors["#4a4a58"])
        version.x = Constants.GAME_WIDTH / 2.0 - version.width / 2.0
        version.y = 620.0
        addChild(version)

        KnfFiles.resolveSound("preload/music/freakyMenu/freakyMenu")?.let { music.load(it, 0.55) }
        music.play()
    }

    override suspend fun SContainer.sceneMain() {
        val knfInput = KnfInput(keys)
        addUpdater { dt ->
            val s = dt.secondsDouble()
            t += s
            knfInput.update()
            if (t > 0.6 && (knfInput.accept || knfInput.justPressed(0))) {
                music.stop()
                launch { sceneContainer.changeTo<SongSelectScene>() }
            }
        }
    }
}

fun Duration.secondsDouble(): Double = inWholeNanoseconds / 1_000_000_000.0
