package nightfunkin.core

import korlibs.korge.view.Container
import korlibs.korge.view.Image
import korlibs.math.geom.Anchor2D

/**
 * A small FlxSprite-style view for Starling/Flixel XML atlases.
 *
 * Only the parts the UI screens need are implemented: one animation at a
 * time, a frame rate, looping, and the sprite-sheet's trimmed-frame offset.
 * Character sheets use [AnimateAtlas] instead, because those are symbol
 * timelines rather than a flat list of frames.
 */
class SparrowSprite(
    private val atlas: TextureAtlas,
    frames: List<TextureAtlas.AtlasFrame>,
    var fps: Double = 24.0,
    var loop: Boolean = true,
) : Container() {

    private var frames: List<TextureAtlas.AtlasFrame> = frames

    private val image: Image = Image(atlas.slice(frames.first())).also {
        it.anchor = Anchor2D.TOP_LEFT
        addChild(it)
    }

    private var elapsed = 0.0
    private var appliedFrame = -1

    var frameIndex: Int = 0
        private set

    /** False holds the current frame, like `FlxAnimationController.paused`. */
    var playing: Boolean = frames.isNotEmpty()

    init {
        applyFrame(0)
    }

    /** Switches to [newFrames], optionally restarting at its first frame. */
    fun play(newFrames: List<TextureAtlas.AtlasFrame>, restart: Boolean = true) {
        if (newFrames.isEmpty()) return
        frames = newFrames
        playing = true
        if (restart || frameIndex >= frames.size) {
            frameIndex = 0
            elapsed = 0.0
            appliedFrame = -1
            applyFrame(0)
        }
    }

    /** Restarts the current animation from frame zero. */
    fun restart() {
        if (frames.isEmpty()) return
        frameIndex = 0
        elapsed = 0.0
        appliedFrame = -1
        playing = true
        applyFrame(0)
    }

    /** Advances the animation by [dt] seconds. */
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
                elapsed = 0.0
                break
            }
        }
        if (changed) applyFrame(frameIndex)
    }

    private fun applyFrame(index: Int) {
        if (index == appliedFrame) return
        val frame = frames.getOrNull(index) ?: return
        appliedFrame = index
        image.bitmap = atlas.slice(frame)
        // Flixel stores the trimmed rect at (x, y) and the logical frame at
        // (x - frameX, y - frameY), so the negative offset keeps the sprite's
        // origin fixed across differently trimmed frames.
        image.x = -frame.frameX.toDouble()
        image.y = -frame.frameY.toDouble()
    }
}
