package nightfunkin.core

import korlibs.image.bitmap.Bitmap

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
 * Returns `null` when the platform has no fast path, the data is not an image
 * the native decoder understands, or decoding fails; callers then fall back to
 * korlibs' own decoders.
 */
internal expect fun knfDecodeImageNative(data: ByteArray): Bitmap?
