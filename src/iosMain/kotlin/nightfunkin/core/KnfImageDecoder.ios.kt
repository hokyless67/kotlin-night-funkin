package nightfunkin.core

import korlibs.image.bitmap.Bitmap

/**
 * iOS decodes through CoreGraphics already (korlibs' `nativeImageFormatProvider`
 * is backed by `CGImage`), so korlibs' own path is the fast one here.
 */
internal actual fun knfDecodeImageNative(data: ByteArray): Bitmap? = null
