package nightfunkin.core

import korlibs.image.bitmap.Bitmap
import korlibs.image.bitmap.Bitmap32
import kotlin.math.max
import kotlin.math.min

/**
 * A decoded image and how much its source was shrunk to fit the GPU.
 *
 * `scaleX`/`scaleY` are `decodedSize / sourceSize`. Atlases keep their frame
 * coordinates in source pixels and multiply them by these factors when cutting
 * a sprite out; renderers compensate with `1 / scale` so the sprite keeps its
 * authored size and position.
 */
internal class KnfDecodedImage(
    val bitmap: Bitmap,
    val scaleX: Double,
    val scaleY: Double,
) {
    val scaled: Boolean get() = scaleX != 1.0 || scaleY != 1.0
}

/**
 * Fast, platform-native image decoding.
 *
 * KorGE's pure Kotlin PNG decoder is fine on desktop (the JVM build routes
 * through ImageIO instead) but on Android it runs the inflate, the per-scanline
 * unfilter and the per-pixel palette/alpha expansion entirely in Kotlin. The
 * title screen alone ships a 4096x4096 `gfDanceTitle.png`, which took ~16 s to
 * decode on a phone - a black screen right after the loading state.
 *
 * Android therefore decodes through `BitmapFactory` (the same libpng-backed
 * path the original Haxe/OpenFL game uses) and hands the pixels straight to
 * [Bitmap] as premultiplied ARGB, which is what the GL backend wants.
 *
 * [limit] caps the longest side. FNF ships a handful of sheets above any
 * mobile GPU's texture limit (`BOYFRIEND.png` is 8192x4096, GF's Tankman sheet
 * and Pico's death explosion are just as wide). Handing those to OpenGL
 * produced a black rectangle where the character should be - or a 128 MB
 * texture allocation the driver refused - so they are decoded pre-shrunk when
 * the platform can do it for free.
 *
 * Returns `null` when the platform has no fast path, the data is not an image
 * the native decoder understands, or decoding fails; callers then fall back to
 * korlibs' own decoders.
 */
internal expect fun knfDecodeImageNative(data: ByteArray, limit: Int): KnfDecodedImage?

/**
 * Box-filter downscale used by platforms without a native pre-shrinking decoder.
 *
 * Only reached for the few oversized sheets and only on platforms whose
 * decoders cannot sample (desktop never, because its limit is 16384).
 */
internal fun knfShrinkToLimit(bitmap: Bitmap, limit: Int): KnfDecodedImage {
    val longest = max(bitmap.width, bitmap.height)
    if (longest <= limit || bitmap.width <= 0 || bitmap.height <= 0) {
        return KnfDecodedImage(bitmap, 1.0, 1.0)
    }
    val ratio = limit.toDouble() / longest
    val dstW = max(1, (bitmap.width * ratio).toInt())
    val dstH = max(1, (bitmap.height * ratio).toInt())
    val src = bitmap.toBMP32IfRequired()
    val stepX = src.width.toDouble() / dstW
    val stepY = src.height.toDouble() / dstH
    val out = Bitmap32(dstW, dstH, premultiplied = src.premultiplied)
    for (y in 0 until dstH) {
        val y0 = (y * stepY).toInt()
        val y1 = min(src.height, max(y0 + 1, ((y + 1) * stepY).toInt()))
        for (x in 0 until dstW) {
            val x0 = (x * stepX).toInt()
            val x1 = min(src.width, max(x0 + 1, ((x + 1) * stepX).toInt()))
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            var count = 0
            for (sy in y0 until y1) {
                for (sx in x0 until x1) {
                    val v = src.getInt(sx, sy)
                    a += v ushr 24
                    r += (v ushr 16) and 0xFF
                    g += (v ushr 8) and 0xFF
                    b += v and 0xFF
                    count++
                }
            }
            if (count == 0) continue
            out.setInt(
                x,
                y,
                ((a / count) shl 24) or ((r / count) shl 16) or
                    ((g / count) shl 8) or (b / count),
            )
        }
    }
    return KnfDecodedImage(
        out,
        dstW.toDouble() / src.width,
        dstH.toDouble() / src.height,
    )
}
