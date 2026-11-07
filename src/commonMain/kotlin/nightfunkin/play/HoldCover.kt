package nightfunkin.play

import korlibs.korge.view.Container
import korlibs.korge.view.Image
import korlibs.math.geom.Anchor2D
import nightfunkin.core.AtlasSlice
import nightfunkin.core.Constants
import nightfunkin.core.NoteStyle
import nightfunkin.core.setPixelArt

/**
 * The glow that covers the receptor while a hold note is held.
 *
 * Ported from FNF's `play/notes/NoteHoldCover.hx`:
 *
 * ```haxe
 * public function playStart():Void   { glow.animation.play('holdCoverStart' + color); }
 * public function playContinue():Void { glow.animation.play('holdCover' + color); }
 * public function playEnd():Void     { glow.animation.play('holdCoverEnd' + color); }
 *
 * public function onAnimationFinished(animationName:String):Void {
 *     if (animationName.startsWith('holdCoverStart')) playContinue();
 *     if (animationName.startsWith('holdCoverEnd')) { this.visible = false; this.kill(); }
 * }
 * ```
 *
 * The position is FNF's, hardcoded offsets included:
 *
 * ```haxe
 * cover.x = this.x + getXPos(dir) + STRUMLINE_SIZE / 2 - cover.width / 2 + offsets[0] - 12;
 * cover.y = this.y + INITIAL_OFFSET + STRUMLINE_SIZE / 2 + offsets[1] - 96;
 * ```
 */
class HoldCover(
    private val host: Container,
    private val direction: Int,
    private val asset: NoteStyle.HoldCoverAsset,
    private val noteStyle: NoteStyle,
) : Container() {

    private enum class Phase { Start, Hold, End, Done }

    private val image = Image(korlibs.image.bitmap.Bitmap32(1, 1, intArrayOf(0), false)).also {
        it.anchor = Anchor2D.TOP_LEFT
        it.setPixelArt(asset.isPixel)
        addChild(it)
    }

    private var phase = Phase.Done
    private var frames: List<NoteStyle.TrimmedFrame> = emptyList()
    private var frameIndex = 0
    private var timer = 0.0
    private var finished = true

    /** Lane-local x of this direction's receptor. */
    var laneX: Double = 0.0
        private set

    /** Lane-local y of the receptor line. */
    var strumlineY: Double = 0.0
        private set

    init {
        playStart()
    }

    /** Position the cover immediately, before its first rendered frame. */
    fun setPosition(laneX: Double, strumlineY: Double) {
        this.laneX = laneX
        this.strumlineY = strumlineY
        redraw()
    }

    fun playStart() {
        if (asset.start.isEmpty()) {
            // No start animation - go straight to the hold pose.
            playHold()
            return
        }
        phase = Phase.Start
        setFrames(asset.start)
    }

    fun playHold() {
        if (asset.hold.isEmpty()) {
            phase = Phase.Done
            visible = false
            return
        }
        phase = Phase.Hold
        // FNF marks the middle hold animation as `looped = true`; the start
        // and end poses play once.
        setFrames(asset.hold)
    }

    fun playEnd() {
        if (phase == Phase.End || phase == Phase.Done) return
        if (asset.end.isEmpty()) {
            phase = Phase.Done
            visible = false
            return
        }
        phase = Phase.End
        setFrames(asset.end)
    }

    private fun setFrames(list: List<NoteStyle.TrimmedFrame>) {
        frames = list
        frameIndex = 0
        timer = 0.0
        finished = list.size <= 1
        visible = true
        redraw()
    }

    fun update(dt: Double) {
        if (frames.size > 1 && !finished) {
            val step = 1.0 / 24.0
            timer += dt
            while (timer >= step) {
                timer -= step
                frameIndex++
                if (frameIndex >= frames.size) {
                    frameIndex = frames.size - 1
                    finished = true
                    timer = 0.0
                    break
                }
            }
            redraw()
        }
        if (finished) {
            when (phase) {
                Phase.Start -> playHold()
                Phase.Hold -> {
                    // `animData[1].looped = true` in NoteStyle.buildHoldCoverAnimations.
                    if (frames.size > 1) {
                        frameIndex = 0
                        timer = 0.0
                        finished = false
                        redraw()
                    }
                }
                Phase.End -> {
                    phase = Phase.Done
                    visible = false
                    host.removeChild(this)
                }
                else -> {}
            }
        }
    }

    private fun redraw() {
        val frame = frames.getOrNull(frameIndex) ?: return
        val slice = frame.slice
        image.bitmap = slice
        val scale = asset.scale
        val fw = (if (frame.frameWidth > 0) frame.frameWidth else slice.width).toDouble()
        // FNF positions the cover by its *current* frame's size and applies
        // the skin's offsets through the cover scale:
        //
        // ```haxe
        // cover.x = this.x + getXPos(dir) + STRUMLINE_SIZE / 2 - cover.width / 2 + offsets[0] * cover.scale.x;
        // cover.y = this.y + INITIAL_OFFSET + STRUMLINE_SIZE / 2 + offsets[1] * cover.scale.y;
        // ```
        //
        // `cover.width` comes from `updateHitbox()` after the scale is set, so
        // it is the logical frame width times that scale. The artwork itself
        // sits inside the frame, so its trim also scales.
        image.anchor = Anchor2D.TOP_LEFT
        image.scaleX = scale
        image.scaleY = scale
        image.x = laneX + Constants.STRUMLINE_SIZE / 2.0 - fw * scale / 2.0 +
            asset.offsets.getOrElse(0) { 0.0 } * scale - 12.0 - frame.offsetX * scale
        image.y = strumlineY + Constants.INITIAL_OFFSET + Constants.STRUMLINE_SIZE / 2.0 +
            asset.offsets.getOrElse(1) { 0.0 } * scale - 96.0 - frame.offsetY * scale
    }

    fun kill() {
        host.removeChild(this)
    }
}
