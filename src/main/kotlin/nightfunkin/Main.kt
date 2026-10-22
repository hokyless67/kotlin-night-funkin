package nightfunkin

import korlibs.image.color.Colors
import korlibs.event.Key
import korlibs.korge.Korge
import korlibs.korge.scene.sceneContainer
import korlibs.korge.view.addUpdater
import korlibs.math.geom.Size
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
        LoadingSceneTarget.toSong = true
        println("KNF: requested boot song '$songId' (autoplay=${PlayRequest.autoplay})")
    }

    scenes.changeTo<LoadingScene>()
}
