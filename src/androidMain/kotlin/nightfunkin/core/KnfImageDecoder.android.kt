package nightfunkin.core

import android.graphics.BitmapFactory
import korlibs.image.bitmap.Bitmap32
import kotlin.math.max

/**
 * Decodes through Android's native `BitmapFactory` (libpng/libjpeg).
 *
 * `Bitmap32` stores colours as `0xAABBGGRR`, while Android's public pixel API
 * hands out `0xAARRGGBB`, so the copy also swaps the red/blue bytes and
 * pre-multiplies alpha (the GL backend wants premultiplied colours).
 *
 * The copy goes straight into the final `IntArray` through `getPixels`, without
 * the extra direct `ByteBuffer` the previous version used: decoding a 4096x4096
 * sheet needs three full-size buffers that way (Android bitmap + direct buffer
 * + ints, ~190 MB), which is exactly when a phone runs out of memory and drops
 * sprites. Two buffers is the minimum the platform allows.
 *
 * Sheets above [limit] are decoded with `inSampleSize`, so the full-size bitmap
 * is never allocated at all - FNF's 8192x4096 character sheets otherwise need
 * a 128 MB native bitmap, a 128 MB `IntArray` and a 128 MB GPU texture, which
 * is where the black rectangles on mobile came from.
 */
internal actual fun knfDecodeImageNative(data: ByteArray, limit: Int): KnfDecodedImage? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        inScaled = false
    }
    runCatching { BitmapFactory.decodeByteArray(data, 0, data.size, bounds) }.getOrNull()
    val sourceWidth = bounds.outWidth
    val sourceHeight = bounds.outHeight
    if (sourceWidth <= 0 || sourceHeight <= 0) return null

    var sample = 1
    while (max(sourceWidth, sourceHeight) / sample > limit) sample *= 2

    val options = BitmapFactory.Options().apply {
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        // Keep the raw, premultiplied pixels (API 19+); no density scaling:
        // these are game assets, their pixel size is their pixel size.
        inPremultiplied = true
        inScaled = false
        inSampleSize = sample
    }
    val bitmap = runCatching { BitmapFactory.decodeByteArray(data, 0, data.size, options) }
        .getOrNull() ?: return null
    try {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        // `getPixels` un-premultiplies on the way out (documented behaviour),
        // so the pass below re-applies it together with the channel swap.
        val ints = IntArray(width * height)
        runCatching { bitmap.getPixels(ints, 0, width, 0, 0, width, height) }
            .getOrElse { return null }
        for (i in ints.indices) {
            val v = ints[i]
            val a = v ushr 24
            val r = (v ushr 16) and 0xFF
            val g = (v ushr 8) and 0xFF
            val b = v and 0xFF
            ints[i] = when (a) {
                0 -> 0
                0xFF -> (0xFF shl 24) or (b shl 16) or (g shl 8) or r
                else -> (a shl 24) or ((b * a / 255) shl 16) or ((g * a / 255) shl 8) or (r * a / 255)
            }
        }
        // `inSampleSize` rounds each side down independently, so report the
        // actual factors instead of assuming `1 / sample`.
        return KnfDecodedImage(
            Bitmap32(width, height, ints, premultiplied = true),
            width.toDouble() / sourceWidth,
            height.toDouble() / sourceHeight,
        )
    } finally {
        runCatching { bitmap.recycle() }
    }
}
