package nightfunkin.core

import korlibs.korge.view.Container
import korlibs.korge.view.Image
import korlibs.math.geom.Anchor2D
import korlibs.math.geom.degrees
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * A small standalone Adobe Animate atlas sprite.
 *
 * [Character] and [nightfunkin.play.Stage] already know how to flatten a
 * timeline pose into images, but stage-script cutscenes also need one-off
 * animate sprites (`PicoDopplegangerSprite`, `PicoBloodPool`, ...). This keeps
 * the same matrix/bounds conventions in one place.
 */
class AnimateSpriteView(
    val atlas: AnimateAtlas,
    private val isPixel: Boolean = false,
    private val applyStageMatrix: Boolean = false,
) : Container() {

    private val box = atlas.bounds.takeUnless { it.empty } ?: atlas.boundsOf(0)
    private val originX = if (applyStageMatrix) 0.0 else box.minX
    private val originY = if (applyStageMatrix) 0.0 else box.minY
    private val images = ArrayList<Image>()
    private val placeholder = atlas.sprites.values.firstOrNull()?.slice

    var loop: Boolean = false
        private set
    var fps: Double = atlas.frameRate
    var playing: Boolean = false
        private set
    var finished: Boolean = true
        private set
    var frameIndex: Int = 0
        private set
    var currentAnimation: String = ""
        private set

    /** Fired once when a non-looping animation reaches its last frame. */
    var onFinish: (() -> Unit)? = null

    private var frames: List<Int> = emptyList()
    private var elapsed = 0.0

    init {
        val parts = atlas.poses.maxOfOrNull { it.parts.size } ?: 0
        if (placeholder != null) {
            repeat(parts) {
                val img = Image(placeholder)
                img.anchor = Anchor2D.CENTER
                img.setPixelArt(isPixel)
                addChild(img)
                images += img
            }
        }
    }

    /** Plays a label by prefix, or the whole main timeline when [name] is empty. */
    fun play(name: String, loop: Boolean = false, restart: Boolean = true) {
        val label = if (name.isEmpty()) emptyList() else atlas.labelFrames(name)
        val nextFrames = label.ifEmpty { atlas.poses.indices.toList() }
        playFrames(name, nextFrames, loop, restart)
    }

    /** Plays an explicit list of main-timeline pose indices. */
    fun playFrames(
        name: String,
        nextFrames: List<Int>,
        loop: Boolean = false,
        restart: Boolean = true,
    ) {
        if (nextFrames.isEmpty()) return
        if (!restart && name == currentAnimation && playing) return
        this.loop = loop
        frames = nextFrames
        currentAnimation = name
        finished = false
        playing = true
        elapsed = 0.0
        frameIndex = 0
        render(frames[0])
    }

    /** Pauses on the current frame. */
    fun stop() {
        playing = false
    }

    fun update(dt: Double) {
        if (!playing || frames.isEmpty() || fps <= 0.0) return
        elapsed += dt
        val step = 1.0 / fps
        var changed = false
        while (elapsed >= step) {
            elapsed -= step
            if (frameIndex + 1 < frames.size) {
                frameIndex++
                changed = true
            } else if (loop) {
                frameIndex = 0
                changed = true
            } else {
                playing = false
                finished = true
                onFinish?.invoke()
                break
            }
        }
        if (changed) render(frames[frameIndex])
    }

    private fun render(index: Int) {
        val pose = atlas.poses.getOrNull(index) ?: return
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
            val m = if (applyStageMatrix) {
                part.matrix.under(atlas.stageMatrix)
            } else {
                part.matrix
            }
            val (cx, cy) = m.xform(def.width / 2.0, def.height / 2.0)
            img.x = cx - originX
            img.y = cy - originY
            img.scaleX = hypot(m.a, m.b)
            img.scaleY = hypot(m.c, m.d)
            if (m.a * m.d - m.c * m.b < 0.0) img.scaleY = -img.scaleY
            img.rotation = ((if (def.turned) -90.0 else 0.0) +
                Math.toDegrees(atan2(m.b, m.a))).degrees
        }
    }
}
