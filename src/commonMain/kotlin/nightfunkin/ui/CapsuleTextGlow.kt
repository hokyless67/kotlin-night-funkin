package nightfunkin.ui

import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.RGBA
import korlibs.image.font.TextToBitmapResult
import nightfunkin.core.crossBlur
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Lays a korlibs text raster out like `FlxText`'s graphic.
 *
 * `FlxText` sizes its bitmap from the text field (`textWidth + 4` by
 * `textHeight + 4`) and draws the field one pixel up-left of it
 * (`drawTextFieldTo`'s native `translate(-1, -1)`), so the first glyph pen sits
 * at `(GUTTER - 1, GUTTER - 1) = (1, 1)`. Reproducing the same padding keeps
 * the glow room at the right/bottom edges identical and makes
 * `whiteText.width` (used by the marquee) match.
 */
internal fun flxTextBitmap(result: TextToBitmapResult): Bitmap32 {
    val src = result.bmp.toBMP32IfRequired()
    val width = ceil(result.metrics.width + 4.0).toInt().coerceAtLeast(src.width + 1)
    val height = (ceil(result.fmetrics.ascent - result.fmetrics.descent).toInt() + 4)
        .coerceAtLeast(src.height + 1)
    val out = Bitmap32(width, height, src.premultiplied)
    Bitmap32.copyRect(src, 0, 0, out, 1, 1, src.width, src.height)
    return out
}

/**
 * `CapsuleText.blurredText`: the cyan copy behind the title carrying
 * `GaussianBlurShader(1)`.
 *
 * The shader works within the sprite's texture rect, so the result keeps the
 * bitmap size and the glyph edges; [crossBlur] reproduces that exactly.
 */
internal fun shaderBlurredText(src: Bitmap32, amount: Double = 1.0): Bitmap32 =
    crossBlur(src, amount)

/**
 * `GlowFilter`'s combine pass: `src + glow * (1 - src.a)`.
 *
 * `FlxText` bakes the glow directly into the white text's graphic, so the two
 * must be one bitmap before the `CapsuleText` group alpha dims them. Keeping
 * them as separate layers with `alpha = 0.6` lets the glyphs show the cyan
 * through and reads as a much stronger halo on unselected capsules.
 */
internal fun bakeGlow(glow: Bitmap32, text: Bitmap32): Bitmap32 {
    val width = text.width
    val height = text.height
    val out = Bitmap32(width, height, text.premultiplied)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val s = text.getInt(x, y)
            val g = glow.getInt(x, y)
            val sa = (s ushr 24) and 0xFF
            val ga = (g ushr 24) and 0xFF
            val inv = 255 - sa
            fun channel(shift: Int): Int {
                val value = ((s ushr shift) and 0xFF) + (((g ushr shift) and 0xFF) * inv + 127) / 255
                return value.coerceIn(0, 255)
            }
            val a = (sa + (ga * inv + 127) / 255).coerceIn(0, 255)
            out.setInt(
                x,
                y,
                (a shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0),
            )
        }
    }
    return out
}

/**
 * `GlowFilter(glowColor, 1, 5, 5, 210, BitmapFilterQuality.MEDIUM)` around the
 * white title.
 *
 * OpenFL's native glow is not a Gaussian: it runs Lime's `StackBlur` on the
 * source `quality` times (quality 2 → two horizontal + two vertical box passes)
 * with `radius = round(blur) / 2`, then recolours the blurred alpha with the
 * filter colour. The software path used for `BitmapData.draw` never reads
 * `strength` (Lime's `gaussianBlur` ignores it), so it is kept at 1 by default.
 *
 * The result is premultiplied because KorGE uploads premultiplied textures.
 */
internal fun glowFilter(
    src: Bitmap32,
    color: RGBA,
    blur: Int = 5,
    quality: Int = 2,
    strength: Double = 1.0,
): Bitmap32 {
    val width = src.width
    val height = src.height
    val mask = IntArray(width * height) { i ->
        (src.getInt(i % width, i / width) ushr 24) and 0xFF
    }
    // `StackBlur.__stackBlurCanvasRGBA`: radius is half the blur size and the
    // whole blur is repeated `quality` times.
    val radius = (blur.toDouble().roundToInt() shr 1).coerceIn(0, 254)
    val iterations = quality.coerceIn(1, 3)
    val scratch = IntArray(mask.size)
    repeat(iterations) {
        boxBlurH(mask, scratch, width, height, radius)
        boxBlurT(scratch, mask, width, height, radius)
    }

    val out = Bitmap32(width, height, premultiplied = true)
    val r = color.r
    val g = color.g
    val b = color.b
    for (y in 0 until height) {
        for (x in 0 until width) {
            val i = y * width + x
            val a = (mask[i] * strength).toInt().coerceIn(0, 255)
            out.setInt(
                x,
                y,
                (a shl 24) or
                    (((b * a + 127) / 255) shl 16) or
                    (((g * a + 127) / 255) shl 8) or
                    ((r * a + 127) / 255),
            )
        }
    }
    return out
}

/** Horizontal pass of Lime's `StackBlur`, clamping samples at the edges. */
private fun boxBlurH(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
    val div = radius * 2 + 1
    for (y in 0 until height) {
        val row = y * width
        var sum = (radius + 1) * src[row]
        for (i in 1..radius) sum += src[row + minOf(i, width - 1)]
        for (x in 0 until width) {
            dst[row + x] = (sum + div / 2) / div
            sum += src[row + minOf(x + radius + 1, width - 1)]
            sum -= src[row + maxOf(x - radius, 0)]
        }
    }
}

/** Vertical pass of Lime's `StackBlur`, clamping samples at the edges. */
private fun boxBlurT(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
    val div = radius * 2 + 1
    for (x in 0 until width) {
        var sum = (radius + 1) * src[x]
        for (i in 1..radius) sum += src[minOf(i, height - 1) * width + x]
        for (y in 0 until height) {
            dst[y * width + x] = (sum + div / 2) / div
            sum += src[minOf(y + radius + 1, height - 1) * width + x]
            sum -= src[maxOf(y - radius, 0) * width + x]
        }
    }
}
