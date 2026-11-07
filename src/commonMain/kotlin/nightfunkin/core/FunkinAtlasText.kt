package nightfunkin.core

import korlibs.korge.view.Container
import korlibs.korge.view.Image
import korlibs.korge.view.image
import korlibs.math.geom.Anchor2D
import kotlin.math.max

/**
 * Port of FNF's `funkin.ui.AtlasText` for the sparse texture-atlas fonts in
 * `assets/preload/images/fonts/`.
 *
 * Unlike an AngelCode bitmap font, every character is a trimmed rectangle in
 * a Sparrow atlas and is laid out edge-to-edge. The intro credits use
 * `bold.xml`: it contains four frames per glyph at 24 fps, which produces the
 * alternating filled/outlined flicker seen in the original title sequence.
 */
class FunkinAtlasText(
    private val atlas: TextureAtlas,
    initialText: String = "",
) : Container() {

    private data class CharView(
        val image: Image,
        val frames: List<TextureAtlas.AtlasFrame>,
        val x: Double,
        val lineTop: Double,
    )

    private val chars = ArrayList<CharView>()
    private val lineHeight = atlas.frames.values.maxOfOrNull { it.height } ?: 0
    private var maxLineWidth = 0.0
    private var lastFrameIndex = -1

    /** Width of the widest line, used by FNF's `screenCenter(X)`. */
    val contentWidth: Double get() = maxLineWidth

    /** When this text appeared, so letter flicker starts on its first frame. */
    var bornAt: Double = 0.0

    var text: String = ""
        set(value) {
            // AtlasFont.BOLD only contains uppercase glyphs; FNF's AtlasText
            // applies the same restriction automatically.
            val cased = value.trim().uppercase()
            if (field == cased) return
            field = cased
            rebuild()
        }

    init {
        text = initialText
    }

    /** Advances the 24 fps glyph animation to [now] (seconds). */
    fun update(now: Double) {
        val frameIndex = ((now - bornAt).coerceAtLeast(0.0) * GLYPH_FPS).toInt()
        if (frameIndex == lastFrameIndex) return
        lastFrameIndex = frameIndex

        for (view in chars) {
            val frame = view.frames[frameIndex % view.frames.size]
            view.image.bitmap = atlas.slice(frame)
            view.image.y = view.lineTop + lineHeight - frame.height
        }
    }

    private fun rebuild() {
        removeChildren()
        chars.clear()
        lastFrameIndex = -1
        maxLineWidth = 0.0

        var line = 0
        var pen = 0.0
        for (ch in text) {
            when {
                ch == '\n' -> {
                    maxLineWidth = max(maxLineWidth, pen)
                    line++
                    pen = 0.0
                }

                ch == ' ' -> pen += SPACE_WIDTH

                else -> {
                    val frames = atlas.framesByPrefix(prefixFor(ch))
                    if (frames.isEmpty()) continue

                    val frame = frames.first()
                    val image = image(atlas.slice(frame)).also {
                        it.anchor = Anchor2D.TOP_LEFT
                        it.smoothing = false
                    }
                    image.x = pen
                    image.y = (line * lineHeight + lineHeight - frame.height).toDouble()

                    chars += CharView(
                        image = image,
                        frames = frames,
                        x = pen,
                        lineTop = line * lineHeight.toDouble(),
                    )
                    pen += frame.width
                }
            }
        }

        maxLineWidth = max(maxLineWidth, pen)
        update(bornAt)
    }

    companion object {
        /** FNF's `AtlasText` advances spaces by 40 pixels, not by a glyph. */
        private const val SPACE_WIDTH = 40.0
        private const val GLYPH_FPS = 24.0

        private val boldAtlas by lazy { TextureAtlas.load("preload/images/fonts/bold.xml") }

        /** `bold.xml`, the font used by TitleState's credits and the pause menu. */
        fun bold(initialText: String = ""): FunkinAtlasText =
            FunkinAtlasText(boldAtlas, initialText)

        private fun prefixFor(ch: Char): String = when (ch) {
            '&' -> "-andpersand-"
            '\'' -> "-apostraphie-"
            '\\' -> "-back slash-"
            ',' -> "-comma-"
            '-' -> "-dash-"
            '!' -> "-exclamation point-"
            '/' -> "-forward slash-"
            '>' -> "-greater than-"
            '<' -> "-less than-"
            '*' -> "-multiply x-"
            '.' -> "-period-"
            '?' -> "-question mark-"
            '"' -> "-end quote-"
            else -> ch.toString()
        }
    }
}
