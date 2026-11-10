package nightfunkin.core

import korlibs.image.bitmap.Bitmap
import korlibs.image.bitmap.BitmapCoords
import korlibs.image.bitmap.sliceWithSize
import korlibs.korge.view.Container
import korlibs.korge.view.Image
import korlibs.math.geom.Anchor2D

/**
 * An AngelCode BMFont, in the XML flavour FNF ships.
 *
 * FNF's score is drawn with `FlxBitmapText` and `vcr-bmp.png` / `vcr-bmp.fnt`:
 *
 * ```haxe
 * scoreText = new FlxBitmapText(0, 0, '', FlxBitmapFont.fromAngelCode(Paths.font("vcr-bmp.png"), Paths.font("vcr-bmp.fnt")));
 * ```
 *
 * The `.fnt` is XML, so it is parsed with a regex rather than a DOM.
 */
class BitmapFontDef(
    val size: Int,
    val lineHeight: Int,
    val base: Int,
    val pageFile: String,
    val glyphs: Map<Int, Glyph>,
) {
    data class Glyph(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val xOffset: Int,
        val yOffset: Int,
        val xAdvance: Int,
    )

    companion object {
        /** Parses an AngelCode XML `.fnt`. */
        fun parse(xml: String): BitmapFontDef {
            fun attr(tag: String, name: String, fallback: Int = 0): Int {
                val m = Regex("\\b" + name + "\\s*=\\s*\"(-?\\d+)\"").find(tag)
                return m?.groupValues?.get(1)?.toIntOrNull() ?: fallback
            }

            val info = Regex("<info\\b[^>]*/?>").find(xml)?.value ?: ""
            val common = Regex("<common\\b[^>]*/?>").find(xml)?.value ?: ""
            val page = Regex("<page\\b[^>]*/?>").find(xml)?.value ?: ""

            val glyphs = LinkedHashMap<Int, Glyph>()
            for (m in Regex("<char\\b[^>]*/?>").findAll(xml)) {
                val tag = m.value
                val id = attr(tag, "id", -1)
                if (id < 0) continue
                glyphs[id] = Glyph(
                    x = attr(tag, "x"),
                    y = attr(tag, "y"),
                    width = attr(tag, "width"),
                    height = attr(tag, "height"),
                    xOffset = attr(tag, "xoffset"),
                    yOffset = attr(tag, "yoffset"),
                    xAdvance = attr(tag, "xadvance"),
                )
            }

            return BitmapFontDef(
                size = attr(info, "size", 16),
                lineHeight = attr(common, "lineHeight", 18),
                base = attr(common, "base", 14),
                pageFile = Regex("file\\s*=\\s*\"([^\"]+)\"").find(page)?.groupValues?.get(1) ?: "",
                glyphs = glyphs,
            )
        }
    }
}

/**
 * A run of text drawn with a [BitmapFontDef].
 *
 * FNF's `FlxBitmapText` supports an outline border, which it renders by
 * stamping the text four times in the border colour and once in the text
 * colour on top; this does the same with a tinted copy of every glyph.
 */
class BitmapText(
    private val font: BitmapFontDef,
    private val page: Bitmap,
) : Container() {

    private val outlineLayer = Container()
    private val textLayer = Container()

    /** FNF's `FlxBitmapText.letterSpacing`, applied between glyphs. */
    var letterSpacing: Int = 0

    /** Draw the black border behind the glyphs, like `FlxTextBorderStyle.OUTLINE`. */
    var outline: Boolean = true

    private var current = ""

    /** Cached slice per glyph code; `sliceWithSize` would allocate one per rebuild. */
    private val glyphSlices = HashMap<Int, BitmapCoords>()
    /** Glyph views are pooled so a score change does not rebuild the whole run. */
    private val outlineViews = ArrayList<Image>()
    private val textViews = ArrayList<Image>()

    init {
        addChild(outlineLayer)
        addChild(textLayer)
    }

    /** The text to draw. */
    var text: String = ""
        set(value) {
            if (current == value) return
            current = value
            rebuild()
        }

    /** Width of the laid-out text, in pixels. */
    val textWidth: Int
        get() {
            var pen = 0
            for (ch in current) {
                val glyph = font.glyphs[ch.code] ?: continue
                pen += glyph.xAdvance + letterSpacing
            }
            return if (current.isEmpty()) 0 else pen - letterSpacing
        }

    private fun rebuild() {
        var outlineIndex = 0
        var textIndex = 0

        var pen = 0
        for (ch in current) {
            val glyph = font.glyphs[ch.code] ?: continue
            if (glyph.width > 0 && glyph.height > 0) {
                val slice = glyphSlices.getOrPut(ch.code) {
                    page.sliceWithSize(glyph.x, glyph.y, glyph.width, glyph.height)
                }
                if (outline) {
                    for ((dx, dy) in OUTLINE_OFFSETS) {
                        val img = outlineViews.getOrNull(outlineIndex) ?: Image(slice).also {
                            it.anchor = Anchor2D.TOP_LEFT
                            it.tint = korlibs.image.color.Colors.BLACK
                            outlineLayer.addChild(it)
                            outlineViews.add(it)
                        }
                        img.bitmap = slice
                        img.x = (pen + glyph.xOffset + dx).toDouble()
                        img.y = (glyph.yOffset + dy).toDouble()
                        img.visible = true
                        outlineIndex++
                    }
                }
                val img = textViews.getOrNull(textIndex) ?: Image(slice).also {
                    it.anchor = Anchor2D.TOP_LEFT
                    textLayer.addChild(it)
                    textViews.add(it)
                }
                img.bitmap = slice
                img.x = (pen + glyph.xOffset).toDouble()
                img.y = glyph.yOffset.toDouble()
                img.visible = true
                textIndex++
            }
            pen += glyph.xAdvance + letterSpacing
        }

        // Hide the slots left over from a longer previous string.
        for (i in outlineIndex until outlineViews.size) outlineViews[i].visible = false
        for (i in textIndex until textViews.size) textViews[i].visible = false
    }

    companion object {
        /** The four directions Flixel stamps an outline in. */
        private val OUTLINE_OFFSETS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
    }
}
