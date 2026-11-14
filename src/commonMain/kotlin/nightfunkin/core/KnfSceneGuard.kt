package nightfunkin.core

import korlibs.image.color.Colors
import korlibs.korge.view.SContainer
import korlibs.korge.view.solidRect
import korlibs.korge.view.text
import kotlinx.coroutines.CancellationException
import nightfunkin.platform.KnfPlatform

/**
 * Runs a scene's `sceneInit` body and reports failures instead of swallowing
 * them.
 *
 * `SceneContainer._changeTo` catches anything `sceneInit` throws, prints it,
 * and then carries on with `sceneMain`. Without a console that reads as "the
 * screen just went black": the scene view exists but is empty, and every phone
 * report of a black screen has to be guessed at. This writes the failure (and
 * the heap state at entry) to `Documents/KNF/knf.log` and draws it on screen,
 * so the next report names the actual exception.
 */
suspend fun SContainer.guardedSceneInit(
    name: String,
    block: suspend SContainer.() -> Unit,
) {
    // Every scene lays itself out from `Constants.GAME_WIDTH/GAME_HEIGHT`, so
    // make sure they describe the real window before the block builds anything.
    syncViewport()
    KnfBootLog.mark("$name.sceneInit start; heap=${KnfPlatform.memoryReport()}")
    try {
        block()
        KnfBootLog.mark("$name.sceneInit done; heap=${KnfPlatform.memoryReport()}")
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        KnfBootLog.mark("$name.sceneInit FAILED: ${t::class.simpleName}: ${t.message}")
        KnfBootLog.error(t)
        runCatching {
            solidRect(
                Constants.GAME_WIDTH.toDouble(),
                Constants.GAME_HEIGHT.toDouble(),
                Colors["#1a0000"],
            )
            val label = text(
                "KNF: $name failed\n\n" + t.stackTraceToString().take(1500),
                textSize = 15.0,
                color = Colors["#ff8080"],
            )
            label.x = 24.0
            label.y = 32.0
        }
    }
}
