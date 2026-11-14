package nightfunkin.core

/**
 * iOS decodes through CoreGraphics already (korlibs' `nativeImageFormatProvider`
 * is backed by `CGImage`), so korlibs' own path is the fast one here. Oversized
 * sheets are shrunk by the common fallback.
 */
internal actual fun knfDecodeImageNative(data: ByteArray, limit: Int): KnfDecodedImage? = null
