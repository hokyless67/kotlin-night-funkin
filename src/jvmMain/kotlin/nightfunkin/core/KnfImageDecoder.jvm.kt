package nightfunkin.core

/**
 * Desktop already decodes natively: the JVM build's `nativeImageFormatProvider`
 * goes through `ImageIO` (see `jvmMain/korlibs/image/format`), so there is
 * nothing to gain here. Oversized sheets are shrunk by the common fallback.
 */
internal actual fun knfDecodeImageNative(data: ByteArray, limit: Int): KnfDecodedImage? = null
