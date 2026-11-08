package nightfunkin.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import korlibs.korge.view.Container
import korlibs.korge.view.Image
import korlibs.korge.view.addUpdater
import korlibs.math.geom.degrees
import nightfunkin.audio.SfxPlayer
import nightfunkin.core.Constants
import nightfunkin.core.KnfFiles
import nightfunkin.play.ResultPlayerRegistry
import kotlin.random.Random

/**
 * FNF 0.8.6's sticker transition (`StickerSubState.hx`): a pile of stickers
 * pops onto the screen one by one, covering everything; when the last one
 * lands, the next scene loads behind them, and the stickers peel off one by
 * one (`degenStickers`) revealing it.
 *
 * The original opens a substate OVER the live state; KNF adds this overlay
 * container to the current scene the same way. When the last sticker pops,
 * [onCovered] fires — the caller switches scenes, and the target scene
 * consumes [StickerSceneTarget.consumeReveal] for the peel-off phase.
 *
 * Trigger points mirror the original: pause-menu exit, game-over exit and the
 * results screen.
 */
class StickerOverlay(
    playerCharacterId: String,
    private val sfx: SfxPlayer,
    private val onCovered: () -> Unit,
) : Container() {

    private val random = Random.Default
    private val placements = ArrayList<StickerPlacement>()
    private val images = ArrayList<Image>()
    private var elapsed = 0.0
    private var covered = false
    private var doneAt = Double.MAX_VALUE

    init {
        val packId = resolveStickerPackFor(playerCharacterId)
        val packStickers = loadPack(packId)
        val sounds = stickerSoundStems()

        if (packStickers.isEmpty()) {
            // Nothing to show — hand off on the first updater tick, right
            // after this overlay has been attached to the scene.
            doneAt = 0.0
        } else {
            // `StickerSubState.regenStickers`: initialize stickers at each
            // point on the screen, then shuffle up the order they will pop.
            // This ensures stickers consistently cover the screen.
            var xPos = -100.0
            var yPos = -100.0
            while (xPos <= Constants.GAME_WIDTH) {
                val path = packStickers.random(random)
                val bmp = KnfFiles.bitmap("shared/images/$path.png")
                val placement = StickerPlacement(
                    path = path,
                    x = xPos,
                    y = yPos,
                    angleDegrees = (-60..70).random(random).toDouble(),
                    soundStem = sounds.randomOrNull(random).orEmpty(),
                )
                placements += placement
                images += Image(bmp).also { img ->
                    img.smoothing = true
                    img.x = placement.x
                    img.y = placement.y
                    img.rotation = placement.angleDegrees.degrees
                    img.visible = false
                    addChild(img)
                }
                // Half-width overlap keeps neighbouring stickers dense.
                xPos += bmp.width * 0.5
                if (xPos >= Constants.GAME_WIDTH && yPos <= Constants.GAME_HEIGHT) {
                    xPos = -100.0
                    yPos += Random.nextDouble(70.0, 120.0)
                }
            }
            placements.shuffle(random)

            // Creates a new sticker for the very center.
            val lastPath = packStickers.random(random)
            val lastBmp = KnfFiles.bitmap("shared/images/$lastPath.png")
            val last = StickerPlacement(
                path = lastPath,
                x = (Constants.GAME_WIDTH - lastBmp.width) / 2.0,
                y = (Constants.GAME_HEIGHT - lastBmp.height) / 2.0,
                angleDegrees = 0.0,
                frameDelay = 2,
                soundStem = sounds.randomOrNull(random).orEmpty(),
            )
            placements += last
            images += Image(lastBmp).also { img ->
                img.smoothing = true
                img.x = last.x
                img.y = last.y
                img.visible = false
                addChild(img)
            }

            // Appear times spread over the first 0.9 s (`FlxMath.remapToRange`).
            placements.forEachIndexed { index, placement ->
                placement.appearAt = 0.9 * index / placements.size
                placement.popAt = placement.appearAt + (1.0 / 24.0) * placement.frameDelay
            }
            doneAt = placements.last().popAt
        }

        addUpdater { dt ->
            elapsed += dt.secondsDouble()
            for ((index, img) in images.withIndex()) {
                val placement = placements[index]
                if (!img.visible && elapsed >= placement.appearAt) {
                    img.visible = true
                    if (placement.soundStem.isNotEmpty()) sfx.play(placement.soundStem)
                }
                // The small scale pop plays `frameDelay` sprite frames after
                // the sticker appears (the last one always pops hard).
                if (img.visible && !placement.popped && elapsed >= placement.popAt) {
                    placement.popped = true
                    img.scaleX = placement.popScale
                    img.scaleY = placement.popScale
                }
            }
            if (!covered && placements.last().popped) {
                covered = true
                // `switchState(() -> targetState(this))`: the popped stickers
                // are carried into the next state for the peel-off phase.
                StickerSceneTarget.handOff(placements)
                onCovered()
            }
        }
    }

    private companion object {
        const val DEFAULT_STICKER_PACK = "default"

        private val knfJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    /** `StickerRegistry` + `StickerPack` data file. */
    private fun loadPack(id: String): List<String> = runCatching {
        knfJson.decodeFromString<StickerPackData>(KnfFiles.text("preload/data/stickerpacks/$id.json")).stickers
    }.getOrElse {
        println("KNF: sticker pack '$id' failed: ${it.message}")
        emptyList()
    }

    /** `StickerSubState`: random sound folder, random click inside it. */
    private fun stickerSoundStems(): List<String> {
        val folders = KnfFiles.assetsRoot.child("shared/sounds/stickersounds").listNames().orEmpty()
        if (folders.isEmpty()) return emptyList()
        val selection = folders.random(random)
        return KnfFiles.assetsRoot.child("shared/sounds/stickersounds/$selection")
            .listNames().orEmpty()
            .filter { it.endsWith(".ogg") || it.endsWith(".mp3") }
            .mapNotNull {
                // `SfxPlayer` needs a resolvable path: extensions matter.
                KnfFiles.resolveSound("shared/sounds/stickersounds/$selection/${it.substringBeforeLast('.')}")
            }
    }
}

@Serializable
private data class StickerPackData(
    val name: String = "",
    val artist: String = "",
    val stickers: List<String> = emptyList(),
)

/** One popped sticker, in the order it appears (`StickerSprite` + its timer). */
class StickerPlacement(
    val path: String,
    val x: Double,
    val y: Double,
    val angleDegrees: Double,
    /** Seconds into the transition when this sticker pops visible. */
    var appearAt: Double = 0.0,
    /** Seconds into the transition when the small scale pop plays. */
    var popAt: Double = 0.0,
    /** Random 0..2 sprite frames of delay before the scale pop. */
    var frameDelay: Int = (0..2).random(),
    /** The transient scale the pop lands on. */
    var popScale: Double = Random.nextDouble(0.97, 1.02),
    /** The key-click sound assigned to this sticker's pop. */
    var soundStem: String = "",
    var popped: Boolean = false,
)

/**
 * Global hand-off between the two phases (`StickerSubState` carried through
 * the state constructor in the original).
 */
object StickerSceneTarget {
    /** Reveal payload for the target scene; null during the cover phase. */
    var reveal: StickerReveal? = null

    fun beginCover() {
        reveal = null
    }

    fun handOff(placements: List<StickerPlacement>) {
        reveal = StickerReveal(placements)
    }

    fun consumeReveal(): StickerReveal? = reveal.also { reveal = null }
}

/** The peel-off payload (`degenStickers` input). */
class StickerReveal(
    val placements: List<StickerPlacement>,
)

/** `playerCharacter.getStickerPackID` from the player registry data. */
fun resolveStickerPackFor(playerCharacterId: String): String {
    val owner = ResultPlayerRegistry.ownerOf(playerCharacterId)
    return ResultPlayerRegistry.stickerPackOf(owner) ?: DEFAULT_STICKER_PACK
}

const val DEFAULT_STICKER_PACK = "default"
