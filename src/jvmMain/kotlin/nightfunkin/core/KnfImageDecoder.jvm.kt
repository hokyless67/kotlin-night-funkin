package nightfunkin.core

import korlibs.image.bitmap.Bitmap

/**
 * Desktop already decodes natively: the JVM build's `nativeImageFormatProvider`
 * goes through `ImageIO` (see `jvmMain/korlibs/image/format`), so there is
 * nothing to gain here.
 */
internal actual fun knfDecodeImageNative(data: ByteArray): Bitmap? = null
