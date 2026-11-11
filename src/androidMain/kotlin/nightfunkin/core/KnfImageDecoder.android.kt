package nightfunkin.core

import android.graphics.BitmapFactory
import korlibs.image.bitmap.Bitmap
import korlibs.image.bitmap.Bitmap32
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes through Android's native `BitmapFactory` (libpng/libjpeg).
 *
 * The pixels are copied out as premultiplied ARGB ints: `Bitmap32` stores
 * colours as `0xAABBGGRR`, which is exactly how an `ARGB_8888` bitmap is laid
 * out in memory on Android's little-endian CPUs. Premultiplication already
 * happened inside the native decoder, so `KnfFiles` does not have to walk the
 * whole sheet again.
 */
internal actual fun knfDecodeImageNative(data: ByteArray): Bitmap? {
    val options = BitmapFactory.Options().apply {
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        // Keep the raw, premultiplied pixels (API 19+); no density scaling:
        // these are game assets, their pixel size is their pixel size.
        inPremultiplied = true
        inScaled = false
    }
    val bitmap = runCatching { BitmapFactory.decodeByteArray(data, 0, data.size, options) }
        .getOrNull() ?: return null
    try {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val rowInts = bitmap.rowBytes / 4
        val buffer = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.LITTLE_ENDIAN)
        bitmap.copyPixelsToBuffer(buffer)
        val ints = IntArray(width * height)
        if (rowInts == width) {
            buffer.rewind()
            buffer.asIntBuffer().get(ints)
        } else {
            for (y in 0 until height) {
                buffer.position(y * rowInts * 4)
                buffer.asIntBuffer().get(ints, y * width, width)
            }
        }
        return Bitmap32(width, height, ints, premultiplied = true)
    } finally {
        runCatching { bitmap.recycle() }
    }
}
