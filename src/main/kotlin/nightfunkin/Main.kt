package nightfunkin

import korlibs.image.color.Colors
import korlibs.event.Key
import korlibs.korge.Korge
import korlibs.korge.scene.sceneContainer
import korlibs.korge.view.Views
import korlibs.korge.view.addUpdater
import korlibs.math.geom.Size
import korlibs.time.seconds
import nightfunkin.core.Constants
import nightfunkin.core.Knf
import nightfunkin.play.PlayRequest
import nightfunkin.play.PlayScene
import nightfunkin.play.ResultScene
import nightfunkin.ui.LoadingScene
import nightfunkin.ui.LoadingSceneTarget
import nightfunkin.ui.MainMenuScene
import nightfunkin.ui.SongSelectScene
import nightfunkin.ui.TitleScene

suspend fun main() = Korge(
    windowSize = Size(Constants.GAME_WIDTH.toDouble(), Constants.GAME_HEIGHT.toDouble()),
    virtualSize = Size(Constants.GAME_WIDTH.toDouble(), Constants.GAME_HEIGHT.toDouble()),
    title = Knf.NAME,
    backgroundColor = Colors["#000000"],
) {
    println("=== ${Knf.NAME} v${Knf.VERSION} (${Knf.SHORT}) ===")
    nightfunkin.core.Save.load()

    if (System.getenv("KNF_DEBUG_INPUT") == "1") {
        val watched = listOf(Key.A, Key.S, Key.D, Key.W, Key.Z, Key.ENTER, Key.LEFT)
        stage.addUpdater {
            watched.forEach { key ->
                if (stage.keys.justPressed(key)) println("KNF: pressed $key")
            }
        }
    }

    val scenes = sceneContainer()

    // KorGE 6 constructs scenes through its Injector, so they must be registered.
    injector.root.mapPrototype { LoadingScene() }
    injector.root.mapPrototype { TitleScene() }
    injector.root.mapPrototype { MainMenuScene() }
    injector.root.mapPrototype { SongSelectScene() }
    injector.root.mapPrototype { PlayScene() }
    injector.root.mapPrototype { ResultScene() }

    // Test hooks — boot straight into a song instead of the menus:
    //   KNF_SONG=bopeebo KNF_DIFFICULTY=hard KNF_AUTOPLAY=1 ./gradlew run
    val songId = System.getenv("KNF_SONG")
    if (!songId.isNullOrBlank()) {
        PlayRequest.songId = songId
        System.getenv("KNF_DIFFICULTY")?.takeIf { it.isNotBlank() }?.let { PlayRequest.difficulty = it }
        System.getenv("KNF_VARIATION")?.takeIf { it.isNotBlank() }?.let { PlayRequest.variation = it }
        PlayRequest.autoplay = System.getenv("KNF_AUTOPLAY") == "1"
        // Direct song boots behave like Story Mode by default, which makes the
        // story-gated cutscenes reachable on their default variation without a
        // campaign port. `KNF_STORY=0` opts back into Freeplay behaviour.
        PlayRequest.storyMode = System.getenv("KNF_STORY")?.let { it == "1" } ?: true
        LoadingSceneTarget.toSong = true
        println(
            "KNF: requested boot song '$songId' " +
                    "(autoplay=${PlayRequest.autoplay}, story=${PlayRequest.storyMode})"
        )
    }

    // Test hook for the menu itself (the normal route is Title -> Main -> Freeplay).
    if (System.getenv("KNF_FREEPLAY") == "1") {
        println("KNF: booting directly into Freeplay")
        scenes.changeTo<SongSelectScene>()
    } else {
        scenes.changeTo<LoadingScene>()
    }

    if (System.getenv("KNF_WINDOW_BOP") == "1") {
        stage.addUpdater { dt -> WindowBop.update(views, dt.seconds) }
    }
}

object WindowBop {
    private const val BOP_MULTIPLY = 50.0
    private const val INTENSITY_MULTIPLY = 1000.0
    private const val DECAY_POWER = 5.0
    private const val SPEED = 0.1

    var intensity = 0.0
    private var elapsed = 0.0
    private var duration = 0.0
    private var active = false

    private fun bopAmount(intensity: Double): Double =
        BOP_MULTIPLY * Math.tanh(intensity * INTENSITY_MULTIPLY)

    fun start(stepLengthMs: Double) {
        duration = stepLengthMs / 1000.0
        elapsed = 0.0
        active = duration > 0.0
        System.out.println("KNF: beat, intensity = $intensity, \n bop = ${bopAmount(intensity)}")
    }

    fun update(views: Views, dtSeconds: Double) {
        if (!active) return
        elapsed += dtSeconds * SPEED
        val t = (elapsed / duration).coerceIn(0.0, 1.0)

        val k = Math.pow(1.0 - t, DECAY_POWER)
        val extra = bopAmount(intensity) * k

        val w = Constants.GAME_WIDTH + extra.toInt()
        val h = Constants.GAME_HEIGHT + (extra * Constants.GAME_HEIGHT / Constants.GAME_WIDTH).toInt()
        views.gameWindow.setSize(w, h)

        if (t >= 1.0) {
            active = false
            intensity = 0.0
            views.gameWindow.setSize(Constants.GAME_WIDTH, Constants.GAME_HEIGHT)
        }
    }
}