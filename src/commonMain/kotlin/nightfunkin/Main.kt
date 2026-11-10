package nightfunkin

import korlibs.image.color.Colors
import korlibs.event.Key
import korlibs.korge.Korge
import korlibs.korge.scene.sceneContainer
import korlibs.korge.view.Views
import korlibs.korge.view.addUpdater
import korlibs.korge.view.solidRect
import korlibs.korge.view.text
import korlibs.math.geom.Size
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt
import nightfunkin.core.Constants
import nightfunkin.core.Knf
import nightfunkin.core.KnfBootLog
import nightfunkin.platform.KnfEnv
import nightfunkin.play.PlayRequest
import nightfunkin.play.PlayScene
import nightfunkin.play.ResultScene
import nightfunkin.ui.LoadingScene
import nightfunkin.ui.LoadingSceneTarget
import nightfunkin.ui.MainMenuScene
import nightfunkin.ui.SongSelectScene
import nightfunkin.ui.TitleScene

suspend fun main() {
    KnfBootLog.install()
    KnfBootLog.mark("main() entered")

    try {
        Korge(
            windowSize = Size(Constants.GAME_WIDTH.toDouble(), Constants.GAME_HEIGHT.toDouble()),
            virtualSize = Size(Constants.GAME_WIDTH.toDouble(), Constants.GAME_HEIGHT.toDouble()),
            title = Knf.NAME,
            backgroundColor = Colors["#000000"],
        ) {
            // KorGE runs this block in its own coroutine (`views.launchImmediately`),
            // which is NOT covered by `GameWindow.entry`'s try/catch. On
            // Kotlin/Native an uncaught exception here aborts the whole app, so
            // catch it, record it, and draw it instead of dying silently.
            try {
                KnfBootLog.mark("KorGE block started")
                println("=== ${Knf.NAME} v${Knf.VERSION} (${Knf.SHORT}) ===")
                nightfunkin.core.Save.load()
                KnfBootLog.mark("save loaded")

                if (KnfEnv["KNF_DEBUG_INPUT"] == "1") {
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
                KnfBootLog.mark("scenes registered")

                // Test hooks — boot straight into a song instead of the menus:
                //   KNF_SONG=bopeebo KNF_DIFFICULTY=hard KNF_AUTOPLAY=1 ./gradlew run
                val songId = KnfEnv["KNF_SONG"]
                if (!songId.isNullOrBlank()) {
                    PlayRequest.songId = songId
                    KnfEnv["KNF_DIFFICULTY"]?.takeIf { it.isNotBlank() }?.let { PlayRequest.difficulty = it }
                    KnfEnv["KNF_VARIATION"]?.takeIf { it.isNotBlank() }?.let { PlayRequest.variation = it }
                    PlayRequest.autoplay = KnfEnv["KNF_AUTOPLAY"] == "1"
                    // Direct song boots behave like Story Mode by default, which makes the
                    // story-gated cutscenes reachable on their default variation without a
                    // campaign port. `KNF_STORY=0` opts back into Freeplay behaviour.
                    PlayRequest.storyMode = KnfEnv["KNF_STORY"]?.let { it == "1" } ?: true
                    LoadingSceneTarget.toSong = true
                    println(
                        "KNF: requested boot song '$songId' " +
                                "(autoplay=${PlayRequest.autoplay}, story=${PlayRequest.storyMode})"
                    )
                }

                // Test hook for the menu itself (the normal route is Title -> Main -> Freeplay).
                if (KnfEnv["KNF_FREEPLAY"] == "1") {
                    println("KNF: booting directly into Freeplay")
                    KnfBootLog.mark("changing scene: SongSelect")
                    scenes.changeTo<SongSelectScene>()
                } else {
                    KnfBootLog.mark("changing scene: Loading")
                    scenes.changeTo<LoadingScene>()
                }
                KnfBootLog.mark("scene change returned")

                WindowBop.enabled = KnfEnv["KNF_WINDOW_BOP"] == "1"
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                KnfBootLog.error(t)
                runCatching {
                    solidRect(
                        Constants.GAME_WIDTH.toDouble(),
                        Constants.GAME_HEIGHT.toDouble(),
                        Colors.BLACK,
                    )
                    text(
                        "KNF failed to start:\n\n" + t.stackTraceToString().take(4000),
                        textSize = 22.0,
                        color = Colors.WHITE,
                    )
                }
            }
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        KnfBootLog.error(t)
    }

    KnfBootLog.mark("main() finished")
}

/**
 * Desktop window "bop".
 *
 * FNF bumps `camHUD.zoom` on every bop step and decays it with
 * `pow(0.95, elapsed * 60)`. The OS window is resized from that same value, so
 * the window and the HUD breathe together: same beat gate, same curve, same
 * amplitude.
 *
 * The previous version kept its own clock and stretched one beat over ten beat
 * lengths (`SPEED = 0.1`), which is why the window pulse visibly lagged behind
 * the HUD pulse.
 */
object WindowBop {
    /** Set once from `KNF_WINDOW_BOP=1`; window resizing is desktop-only. */
    var enabled: Boolean = false

    private var lastWidth = Constants.GAME_WIDTH
    private var lastHeight = Constants.GAME_HEIGHT

    /** Mirrors the HUD camera zoom into the OS window size. */
    fun apply(views: Views?, hudZoom: Double) {
        if (!enabled || views == null) return
        val w = (Constants.GAME_WIDTH * hudZoom).roundToInt()
        val h = (Constants.GAME_HEIGHT * hudZoom).roundToInt()
        if (w == lastWidth && h == lastHeight) return
        lastWidth = w
        lastHeight = h
        // Resizing the OS window is a desktop concept; mobile backends may not
        // support it, so never let the bop crash the frame.
        runCatching { views.gameWindow.setSize(w, h) }
    }

    /** Restores the base size when the song scene goes away. */
    fun reset(views: Views?) {
        if (!enabled) return
        lastWidth = Constants.GAME_WIDTH
        lastHeight = Constants.GAME_HEIGHT
        if (views != null) {
            runCatching { views.gameWindow.setSize(lastWidth, lastHeight) }
        }
    }
}
