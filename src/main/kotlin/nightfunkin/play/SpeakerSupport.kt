package nightfunkin.play

import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.RGBA
import korlibs.korge.view.Container
import korlibs.korge.view.Image
import korlibs.math.geom.Anchor2D
import korlibs.math.geom.degrees
import nightfunkin.audio.MusicPlayer
import nightfunkin.core.AnimateAtlas
import nightfunkin.core.KnfFiles
import nightfunkin.core.TextureAtlas
import nightfunkin.core.setPixelArt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The A-Bot speaker rig the supporting characters sit on.
 *
 * Nene's character script (`nene.hxc`) is not just an animation sheet: it builds
 * A-Bot out of several stage sprites, all placed relative to Nene. KNF only drew
 * Nene herself, which made her look like she was floating in every Weekend song.
 *
 * The layout is copied from `NeneBaseCharacter.setupAbot()`:
 *
 * ```
 * A-Bot body   at (Nene.x - 95, Nene.y + 384)
 * screen BG    +150,  +30
 * eye whites    +40, +250
 * pupils        +50, +238
 * ```
 *
 * Nene (pixel) has its own `applyAbot()` layout in `nene-pixel.hxc`, and Otis
 * (speaker) uses the same A-Bot rig from `otis-speaker.hxc` with a different
 * body offset.
 */
class SpeakerSupport private constructor(
    private val owner: Character,
    private val bodyOffsetX: Double,
    private val bodyOffsetY: Double,
    private val audio: MusicPlayer?,
    private val rig: Rig,
) : Container() {

    private interface Rig {
        fun update(dt: Double)
        fun onBeat()
    }

    init {
        addChild(rig as Container)
        syncPosition()
        visible = owner.visible
    }

    /**
     * `BaseCharacter.x` already includes `_data.offsets` unscaled. FNF's scripts
     * then add the same offset a second time, scaled:
     *
     * ```haxe
     * abot.x = this.x - 95 - (-globalOffsets[0] * scale.x);
     * ```
     */
    private fun syncPosition() {
        val offsets = owner.data.offsets
        // FNF anchors the rig to the same horizontal box KNF positions the
        // character in. Vertically the all-pose box is taller than the idle
        // pose FNF measures, so only that axis needs the stage delta.
        val insetY = owner.stageInsetY * owner.scaleY
        x = owner.x + bodyOffsetX + offsets.getOrElse(0) { 0.0 } * owner.scaleX
        y = owner.y + insetY + bodyOffsetY + offsets.getOrElse(1) { 0.0 } * owner.scaleY
    }

    fun update(dt: Double) {
        syncPosition()
        visible = owner.visible
        rig.update(dt)
    }

    fun onBeat() = rig.onBeat()

    companion object {
        /** `NeneBaseCharacter` ids and the matching A-Bot flavour. */
        private val NORMAL_NENE_IDS = setOf("nene", "nene-christmas", "nene-tankmen")

        fun forCharacter(character: Character, audio: MusicPlayer?): SpeakerSupport? {
            return when (character.id) {
                in NORMAL_NENE_IDS -> {
                    val rig = NormalRig(dark = false, audio)
                    SpeakerSupport(character, -95.0, 384.0, audio, rig)
                }
                "nene-dark" -> {
                    val rig = NormalRig(dark = true, audio)
                    SpeakerSupport(character, -95.0, 384.0, audio, rig)
                }
                "nene-pixel" -> SpeakerSupport(character, 296.0, 430.0, audio, PixelRig(audio))
                // `otis-speaker.hxc` builds the same rig for Otis.
                "otis-speaker" -> SpeakerSupport(character, 170.0, 455.0, audio, NormalRig(dark = false, audio))
                else -> null
            }
        }

        private val EMPTY_BITMAP =
            Bitmap32(1, 1, intArrayOf(0xFF000000.toInt()), true)

        private fun sparrowImage(assetPath: String): Image? {
            val path = KnfFiles.resolve(assetPath, ".png") ?: return null
            val bitmap = KnfFiles.bitmapOrNull(path) ?: return null
            return Image(bitmap).also { it.setPixelArt(true) }
        }
    }

    // ======================================================================
    // Normal / dark / tankman / Christmas Nene
    // ======================================================================

    /**
     * A-Bot's ordinary `abotSystem` export, plus the screen backdrop, the
     * detached head's eyes and the seven-bar visualizer from `ABotVis`.
     */
    private class NormalRig(dark: Boolean, audio: MusicPlayer?) : Container(), Rig {
        private val abot: AnimateView
        private val viz = Visualizer(audio, pixel = false)
        private var frameIndex = 1
        private var frameTime = 0.0
        private val frameCount: Int
        private val frameRate: Double

        init {
            val bgPath = KnfFiles.resolve("characters/abot/stereoBG", ".png")
            val bg = bgPath?.let(KnfFiles::bitmapOrNull)
            val screen = bg?.let {
                Image(it).also { image ->
                    image.x = 150.0
                    image.y = 30.0
                    if (dark) image.tint = RGBA(0x61, 0x67, 0x85)
                }
            }

            // `FunkinSprite().makeSolidColor(160, 60)`.
            val eyeWhites = Image(
                Bitmap32(160, 60, IntArray(160 * 60) { 0xFFFFFFFF.toInt() }, true),
            )
            eyeWhites.x = 40.0
            eyeWhites.y = 250.0
            if (dark) eyeWhites.tint = RGBA(0x6F, 0x96, 0xCE)

            // Draw order is FNF's zIndex order: eye whites (-20), screen (-18),
            // pupils (-15), visualizer (-11), A-Bot itself (-10).
            addChild(eyeWhites)
            if (screen != null) addChild(screen)

            val pupilDir = KnfFiles.findAnimateDir("shared", "characters/abot/systemEyes")
            if (pupilDir != null) {
                val pupils = AnimateView(AnimateAtlas(pupilDir))
                pupils.x = 50.0
                pupils.y = 238.0
                addChild(pupils)
            }

            viz.x = 207.0
            viz.y = 84.0
            addChild(viz)

            val abotPath = if (dark) {
                "characters/abot/dark/abotSystem"
            } else {
                "characters/abot/abotSystem"
            }
            val abotDir = KnfFiles.findAnimateDir("shared", abotPath)
            val atlas = abotDir?.let { AnimateAtlas(it) } ?: AnimateAtlas(abotPath)
            abot = AnimateView(atlas)
            addChild(abot)

            frameCount = atlas.poses.size
            frameRate = atlas.frameRate
            seek(1)
        }

        private fun seek(index: Int) {
            if (frameCount <= 0) return
            frameIndex = index % frameCount
            frameTime = 0.0
            abot.setFrame(frameIndex)
        }

        override fun onBeat() = seek(1)

        override fun update(dt: Double) {
            viz.update()
            if (frameCount <= 0) return
            frameTime += dt
            val step = 1.0 / frameRate.coerceAtLeast(1.0)
            while (frameTime >= step) {
                frameTime -= step
                frameIndex = (frameIndex + 1) % frameCount
                abot.setFrame(frameIndex)
            }
        }
    }

    /**
     * Nene (pixel)'s A-Bot is a set of plain Sparrow sheets. `nene-pixel.hxc`
     * scales every piece by 6 and places the detached head and speakers around
     * the body.
     */
    private class PixelRig(audio: MusicPlayer?) : Container(), Rig {
        private val body: SparrowView
        private val speaker: SparrowView
        private val head: SparrowView
        private val viz = Visualizer(audio, pixel = true)
        private var dancedLeft = false

        init {
            val back = sparrowImage("characters/abotPixel/aBotPixelBack")
            if (back != null) {
                back.scaleX = 6.1
                back.scaleY = 6.0
                // FlxSprite scales around its frame origin, which `loadGraphic()`
                // centres. A top-left scaled image would push the 74x43 back
                // plate down and to the right by (scale - 1) * half its size.
                back.x = -55.0 - back.bitmap.width * 0.5 * (back.scaleX - 1.0)
                back.y = -back.bitmap.height * 0.5 * (back.scaleY - 1.0)
            }

            // `abotHead` does not round its origin; the body and speaker do.
            head = SparrowView("characters/abotPixel/abotHead", 6.0)
            head.x = -325.0
            head.y = 72.0

            speaker = SparrowView("characters/abotPixel/aBotPixelSpeaker", 6.0, roundOrigin = true)
            speaker.x = -78.0
            speaker.y = 9.0

            // zIndex order: head (-14), speaker (-13), back (-12),
            // visualizer (-11), body (-10).
            addChild(head)
            addChild(speaker)
            if (back != null) addChild(back)

            viz.x = -162.0
            viz.y = 16.0
            addChild(viz)

            body = SparrowView("characters/abotPixel/aBotPixelBody", 6.0, roundOrigin = true)
            addChild(body)

            body.play("danceLeft")
            speaker.play("danceLeft")
        }

        override fun onBeat() {
            speaker.play("danceLeft")
            body.play(if (dancedLeft) "danceRight" else "danceLeft")
            dancedLeft = !dancedLeft
        }

        override fun update(dt: Double) {
            viz.update()
            head.update(dt)
            speaker.update(dt)
            body.update(dt)
        }
    }

    /**
     * `ABotVis` from `funkin.audio.visualize`.
     *
     * The source draws seven sprites from `aBotViz` / `aBotVizPixel`, one per
     * log-spaced frequency band. Each animation has six frames; level 0 hides a
     * bar and levels 1..6 select frames 5..0 (tallest).
     */
    private class Visualizer(
        private val audio: MusicPlayer?,
        pixel: Boolean,
    ) : Container() {
        private val bars = ArrayList<Bar>()

        init {
            if (audio != null) {
                val assetPath = if (pixel) {
                    "characters/abotPixel/aBotVizPixel"
                } else {
                    "characters/abot/aBotViz"
                }
                val atlas = KnfFiles.resolve(assetPath, ".xml")
                    ?.let(TextureAtlas::tryLoad)
                if (atlas != null) {
                    val positionX = if (pixel) {
                        doubleArrayOf(0.0, 7.0 * 6, 8.0 * 6, 9.0 * 6, 10.0 * 6, 6.0 * 6, 7.0 * 6)
                    } else {
                        doubleArrayOf(0.0, 59.0, 56.0, 66.0, 54.0, 52.0, 51.0)
                    }
                    val positionY = if (pixel) {
                        doubleArrayOf(0.0, -2.0 * 6, -1.0 * 6, 0.0, 0.0, 1.0 * 6, 2.0 * 6)
                    } else {
                        doubleArrayOf(0.0, -8.0, -3.5, -0.4, 0.5, 4.7, 7.0)
                    }
                    val scale = if (pixel) 6.0 else 1.0

                    var x = 0.0
                    var y = 0.0
                    for (index in 1..7) {
                        x += positionX[index - 1]
                        y += positionY[index - 1]
                        val frames = atlas.framesByPrefix("viz${index}0")
                        if (frames.isEmpty()) continue
                        val bar = Bar(atlas, frames, scale, pixel)
                        bar.x = x
                        bar.y = y
                        addChild(bar)
                        bars += bar
                    }
                }
            }
        }

        fun update() {
            val levels = audio?.visualizerLevels()
            if (levels == null) {
                for (bar in bars) bar.visible = false
                return
            }
            for (index in bars.indices) {
                val value = levels.getOrElse(index) { 0f }
                var frame = (value * 6f).roundToInt()
                val visible = frame > 0
                frame = (frame - 1).coerceIn(0, 5)
                frame = abs(frame - 5)
                val bar = bars[index]
                bar.visible = visible
                if (visible) bar.setFrame(frame)
            }
        }
    }

    private class Bar(
        private val atlas: TextureAtlas,
        private val frames: List<TextureAtlas.AtlasFrame>,
        scale: Double,
        pixel: Boolean,
    ) : Container() {
        private val image = Image(EMPTY_BITMAP)
        private val originOffsetX: Double
        private val originOffsetY: Double

        init {
            scaleX = scale
            scaleY = scale
            // `ABotVis` assigns the whole atlas before scaling, so every bar
            // shares the first frame's centred origin - not its own frame's.
            val first = atlas.firstFrame()
            val halfWidth = (first?.logicalWidth ?: 0) * 0.5
            val halfHeight = (first?.logicalHeight ?: 0) * 0.5
            originOffsetX = -halfWidth * (scale - 1.0)
            originOffsetY = -halfHeight * (scale - 1.0)
            image.setPixelArt(pixel)
            addChild(image)
            setFrame(0)
        }

        fun setFrame(index: Int) {
            val frame = frames[index.coerceIn(0, frames.lastIndex)]
            image.bitmap = atlas.slice(frame)
            image.x = -frame.frameX + originOffsetX
            image.y = -frame.frameY + originOffsetY
        }
    }

    // ======================================================================
    // Rendering helpers
    // ======================================================================

    /** Draws one frame of an Animate atlas, with the same matrix decomposition Character uses. */
    private class AnimateView(private val atlas: AnimateAtlas) : Container() {
        private val images = ArrayList<Image>()
        private val originX = atlas.bounds.minX
        private val originY = atlas.bounds.minY

        init {
            setFrame(0)
        }

        fun setFrame(index: Int) {
            val pose = atlas.poses.getOrNull(index) ?: return
            while (images.size < pose.parts.size) {
                val img = Image(EMPTY_BITMAP)
                img.anchor = Anchor2D.CENTER
                images += img
                addChild(img)
            }
            for (i in images.indices) {
                val img = images[i]
                val part = pose.parts.getOrNull(i)
                val def = part?.let { atlas.sprites[it.sprite] }
                if (part == null || def == null) {
                    img.visible = false
                    continue
                }
                img.visible = true
                img.bitmap = def.slice

                val (cx, cy) = part.matrix.xform(def.width / 2.0, def.height / 2.0)
                img.x = cx - originX
                img.y = cy - originY

                val m = part.matrix
                img.scaleX = hypot(m.a, m.b)
                img.scaleY = hypot(m.c, m.d)
                if (m.a * m.d - m.c * m.b < 0.0) img.scaleY = -img.scaleY
                img.rotation = (
                    (if (def.turned) -90.0 else 0.0) +
                        Math.toDegrees(atan2(m.b, m.a))
                    ).degrees
            }
        }
    }

    /** A small Sparrow animation player for Nene (pixel)'s A-Bot pieces. */
    private class SparrowView(
        assetPath: String,
        private val pieceScale: Double,
        roundOrigin: Boolean = false,
    ) : Container() {
        private val atlas = KnfFiles.resolve(assetPath, ".xml")
            ?.let(TextureAtlas::tryLoad)
        private val image: Image
        private val originOffsetX: Double
        private val originOffsetY: Double
        private val animations = HashMap<String, List<TextureAtlas.AtlasFrame>>()
        private var frames: List<TextureAtlas.AtlasFrame> = emptyList()
        private var frameIndex = 0
        private var frameTime = 0.0
        private var finished = true

        init {
            scaleX = pieceScale
            scaleY = pieceScale
            val first = atlas?.firstFrame()
            val halfWidth = (first?.logicalWidth ?: 0) * 0.5
            val halfHeight = (first?.logicalHeight ?: 0) * 0.5
            // `FunkinSprite.createSparrow()` centres the origin on the first
            // frame. Flixel scales around that point, so unlike the support
            // container the image itself shifts by `(scale - 1) * origin`.
            val originX = if (roundOrigin) halfWidth.roundToInt().toDouble() else halfWidth
            val originY = if (roundOrigin) halfHeight.roundToInt().toDouble() else halfHeight
            originOffsetX = -originX * (pieceScale - 1.0)
            originOffsetY = -originY * (pieceScale - 1.0)

            image = if (first != null) {
                Image(atlas!!.slice(first)).also {
                    it.x = -first.frameX + originOffsetX
                    it.y = -first.frameY + originOffsetY
                }
            } else {
                Image(EMPTY_BITMAP)
            }
            image.setPixelArt(true)
            addChild(image)
            if (atlas != null) {
                for (prefix in listOf("danceLeft", "danceRight", "toleft", "toright")) {
                    animations[prefix] = atlas.framesByPrefix(prefix)
                }
            }
        }

        fun play(name: String) {
            val list = animations[name] ?: return
            frames = list
            frameIndex = 0
            frameTime = 0.0
            finished = list.isEmpty()
            render()
        }

        fun update(dt: Double) {
            if (atlas == null || finished || frames.isEmpty()) return
            frameTime += dt
            val step = 1.0 / 24.0
            while (frameTime >= step) {
                frameTime -= step
                if (frameIndex < frames.lastIndex) {
                    frameIndex++
                    render()
                } else {
                    finished = true
                    break
                }
            }
        }

        private fun render() {
            val atlas = atlas ?: return
            val frame = frames.getOrNull(frameIndex) ?: return
            image.bitmap = atlas.slice(frame)
            image.x = -frame.frameX + originOffsetX
            image.y = -frame.frameY + originOffsetY
        }
    }

}
