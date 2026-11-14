package nightfunkin.core

import korlibs.image.bitmap.Bitmap
import korlibs.image.bitmap.Bitmap32
import korlibs.image.bitmap.sliceWithSize
import korlibs.korge.view.Container
import korlibs.korge.view.Image
import korlibs.korge.view.image
import korlibs.math.geom.RectangleInt
import korlibs.math.geom.slice.RectSlice
import kotlin.math.floor
import kotlin.math.roundToInt

/** A sub-rectangle of a bitmap — what KorGE 6 renders images from. */
typealias AtlasSlice = RectSlice<Bitmap>

/**
 * Loader for Starling/Flixel style XML texture atlases (`<TextureAtlas>` with
 * `<SubTexture>` entries) — the format every FNF sprite sheet ships in.
 *
 * This is what every *pre-0.4* character uses: `senpai.xml`, `spooky_dark.xml`,
 * `bfPixel.xml` and friends sit next to the character folder rather than inside
 * it, because there is no Animate timeline - the animation is just a prefix
 * matched against the frame names.
 */
class TextureAtlas private constructor(
    val bitmap: Bitmap,
    val frames: Map<String, AtlasFrame>,
    /**
     * How much [bitmap] was shrunk to fit the GPU's texture limit (1.0 = as
     * authored). Frame rectangles stay in source pixels; [slice] maps them onto
     * the decoded bitmap and drawing code compensates with `1 / textureScale`.
     */
    val textureScaleX: Double = 1.0,
    val textureScaleY: Double = 1.0,
) {
    data class AtlasFrame(
        val name: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val frameX: Int,
        val frameY: Int,
        val frameWidth: Int,
        val frameHeight: Int,
        /**
         * Starling packs some frames turned 90 degrees *clockwise* inside the
         * sheet, so [width] and [height] describe the stored rect rather than the
         * frame as drawn. `spooky_dark.xml` has 42 such frames.
         */
        val rotated: Boolean = false,
    ) {
        /** Size of the trimmed region as drawn, with [rotated] undone. */
        val trimmedWidth: Int get() = if (rotated) height else width
        val trimmedHeight: Int get() = if (rotated) width else height

        /** Logical size of the frame, accounting for trimmed-off margins. */
        val logicalWidth: Int get() = if (frameWidth > 0) frameWidth else trimmedWidth
        val logicalHeight: Int get() = if (frameHeight > 0) frameHeight else trimmedHeight

        /** Top-left of the trimmed region, in the logical frame's coordinates. */
        val trimmedX: Int get() = x - frameX
        val trimmedY: Int get() = y - frameY
    }

    private val sliceCache = HashMap<String, AtlasSlice>()
    private val blurredCache = HashMap<String, TextureAtlas>()

    /**
     * A copy of this atlas run through FNF's `GaussianBlurShader`
     * (`assets/preload/shaders/gaussianBlur.frag`).
     *
     * That shader is *not* a real Gaussian: it averages a horizontal-only and a
     * vertical-only 7-tap blur, so the source keeps ~20% of its brightness in
     * the centre and smears along the axes. An isotropic [blur] with the same
     * reach spreads that energy over a disc instead and leaves a barely visible
     * haze — the rank badge glow looked dull next to the original because of
     * exactly that.
     *
     * [brightness] scales the RGB output before it is added on top with
     * `BlendMode.ADD` (the sprite is never drawn with normal blending, so
     * pushing the premultiplied colour past the alpha is fine). The shader
     * works inside the sprite's texture rect, so frame rects are reused
     * unchanged (no padding, no offset).
     */
    fun shaderBlurred(amount: Double = 1.0, brightness: Double = 1.0): TextureAtlas =
        blurredCache.getOrPut("$amount/$brightness") {
            TextureAtlas(
                crossBlur(bitmap.toBMP32IfRequired(), amount, brightness),
                frames,
                textureScaleX,
                textureScaleY,
            )
        }

    /** All frames whose name starts with [prefix], in atlas order. */
    fun framesByPrefix(prefix: String): List<AtlasFrame> =
        frames.entries
            .filter { it.key.startsWith(prefix) }
            .sortedBy { it.key }
            .map { it.value }

    fun frame(name: String): AtlasFrame? = frames[name]

    /**
     * The first frame in atlas file order.
     *
     * Flixel's `set_frames` points a sprite at index 0, and `updateHitbox()`
     * then derives `width`/`height` from *that* frame - so this is the frame
     * that fixes a receptor's hitbox for the rest of its life.
     */
    fun firstFrame(): AtlasFrame? = frames.values.firstOrNull()

    /**
     * Lazily slices the frame out of the base bitmap.
     *
     * A rotated frame is physically turned back the moment it is cut out, so
     * everything downstream can treat it as an ordinary upright image and the
     * [AtlasFrame.trimmedWidth] size is the one that gets drawn.
     */
    fun slice(frame: AtlasFrame): AtlasSlice = sliceCache.getOrPut(frame.name) {
        val sx = textureScaleX
        val sy = textureScaleY
        val left = (frame.x * sx).roundToInt().coerceIn(0, maxOf(0, bitmap.width - 1))
        val top = (frame.y * sy).roundToInt().coerceIn(0, maxOf(0, bitmap.height - 1))
        val right = ((frame.x + frame.width) * sx).roundToInt().coerceIn(left + 1, bitmap.width)
        val bottom = ((frame.y + frame.height) * sy).roundToInt().coerceIn(top + 1, bitmap.height)
        val width = right - left
        val height = bottom - top
        if (!frame.rotated) {
            bitmap.sliceWithSize(left, top, width, height)
        } else {
            val upright = extractRotatedCcw(bitmap, left, top, width, height)
            RectSlice(upright, RectangleInt(0, 0, upright.width, upright.height))
        }
    }

    /**
     * Scale factor to apply to an [Image] drawn from [slice] so a downscaled
     * atlas keeps the sprite's authored size.
     */
    val inverseTextureScaleX: Double get() = if (textureScaleX != 0.0) 1.0 / textureScaleX else 1.0
    val inverseTextureScaleY: Double get() = if (textureScaleY != 0.0) 1.0 / textureScaleY else 1.0

    fun sliceByName(name: String): AtlasSlice? = frame(name)?.let { slice(it) }

    fun sliceByPrefix(prefix: String): AtlasSlice? =
        framesByPrefix(prefix).firstOrNull()?.let { slice(it) }

    /** Adds an [Image] for [name] to [container], honouring trimmed-frame offsets. */
    fun addImage(container: Container, name: String): Image? = frame(name)?.let { addImage(container, it) }

    fun addImage(container: Container, frame: AtlasFrame): Image {
        val img = container.image(slice(frame))
        // Keep the *logical* frame origin at (0,0) rather than the trimmed rect.
        img.x = -frame.frameX.toDouble()
        img.y = -frame.frameY.toDouble()
        // Compensate a GPU-limit downscale so the sprite keeps its size.
        img.scaleX = inverseTextureScaleX
        img.scaleY = inverseTextureScaleY
        return img
    }

    companion object {
        private val cache = HashMap<String, TextureAtlas>()

        /**
         * Drops cached atlases.
         *
         * Each [TextureAtlas] owns its decoded [bitmap], so the sheet stays in
         * RAM as long as this map references it. Without this a session kept
         * every stage and character sheet it had ever visited - on a phone that
         * is hundreds of megabytes after a few songs, which is what made the
         * GPU fail to upload new sprites (black squares) and the next song's
         * stems fail to decode.
         *
         * [match] narrows it down to a subset of the keys, which is how
         * `FunkinMemory.clearFreeplay` / `clearStickers` free one kind of art
         * without touching the rest.
         */
        fun clearCache(match: (String) -> Boolean = { true }) {
            if (match("")) {
                cache.clear()
            } else {
                cache.keys.removeAll(match)
            }
        }

        /**
         * One line about what the atlas cache currently pins, for `KNF_MEM`.
         *
         * Sheets are the single biggest thing KNF decodes, so "the heap is
         * still high after a scene change" has to start with "is it this map?".
         */
        fun cacheStats(): String {
            var bytes = 0L
            var topPath = ""
            var topBytes = 0L
            for ((path, atlas) in cache) {
                val size = atlas.bitmap.width.toLong() * atlas.bitmap.height * 4L
                bytes += size
                if (size > topBytes) {
                    topBytes = size
                    topPath = path
                }
            }
            val top = if (topPath.isEmpty()) "-" else
                topPath.substringAfterLast('/') + "=" + topBytes / (1024 * 1024) + "MB"
            return "atlases=${cache.size} atlasBytes=${bytes / (1024 * 1024)}MB top=$top"
        }

        /**
         * Loads and caches the atlas whose XML lives at [atlasPath].
         *
         * [limit] caps the decoded sheet's longest side; stage props pass the
         * smaller backdrop limit on mobile (see
         * [KnfFiles.backdropTextureDimension]). The atlas keeps the resulting
         * scale so frames stay in source coordinates.
         */
        fun load(atlasPath: String, limit: Int = KnfFiles.maxTextureDimension): TextureAtlas =
            cache.getOrPut(atlasPath) {
                val xml = KnfFiles.text(atlasPath)
                val bitmapPath = Regex("imagePath\\s*=\\s*\"([^\"]+)\"")
                    .find(xml)?.groupValues?.get(1)
                    ?: error("KNF: atlas $atlasPath has no imagePath")
                val resolved = resolveSibling(atlasPath, bitmapPath)
                val image = KnfFiles.decodedImage(resolved, limit)
                TextureAtlas(image.bitmap, parseFrames(xml), image.scaleX, image.scaleY)
            }

        /** Like [load], but returns null instead of throwing on a broken file. */
        fun tryLoad(
            atlasPath: String,
            limit: Int = KnfFiles.maxTextureDimension,
        ): TextureAtlas? = runCatching { load(atlasPath, limit) }.getOrNull()

        /**
         * `renderType: "packer"` — spirit's hand-rolled frame list, a flat
         * `name = x y w h` per line against a sibling `.png`:
         *
         * ```
         * idle spirit_0 = 0 1028 128 128
         * idle spirit_1 = 0 1157 128 128
         * ```
         *
         * No trimming and no rotation, so the frame rect is the logical rect.
         */
        fun loadPacker(
            listPath: String,
            limit: Int = KnfFiles.maxTextureDimension,
        ): TextureAtlas = cache.getOrPut(listPath) {
            val png = listPath.substringBeforeLast('.') + ".png"
            val frames = LinkedHashMap<String, AtlasFrame>()
            for (line in KnfFiles.text(listPath).lineSequence()) {
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val name = line.substring(0, eq).trim()
                if (name.isEmpty()) continue
                val nums = line.substring(eq + 1).trim()
                    .split(' ', '\t', ',')
                    .mapNotNull { it.toIntOrNull() }
                if (nums.size < 4) continue
                frames[name] = AtlasFrame(
                    name = name,
                    x = nums[0], y = nums[1],
                    width = nums[2], height = nums[3],
                    frameX = 0, frameY = 0,
                    frameWidth = nums[2], frameHeight = nums[3],
                )
            }
            val image = KnfFiles.decodedImage(png, limit)
            TextureAtlas(image.bitmap, frames, image.scaleX, image.scaleY)
        }

        fun tryLoadPacker(
            listPath: String,
            limit: Int = KnfFiles.maxTextureDimension,
        ): TextureAtlas? = runCatching { loadPacker(listPath, limit) }.getOrNull()

        /**
         * Cuts a `rotated="true"` region out and turns it upright.
         *
         * Starling stores those frames turned 90 degrees clockwise, so undoing
         * it is a 90 degree *counter-clockwise* turn: `out[y, w-1-x] = src[x, y]`.
         * The output is therefore `h` wide and `w` tall.
         */
        private fun extractRotatedCcw(src: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap {
            val out = Bitmap32(h, w, false)
            for (sy in 0 until h) {
                for (sx in 0 until w) {
                    out.setInt(sy, w - 1 - sx, src.getInt(x + sx, y + sy))
                }
            }
            out.premultiplied = src.premultiplied
            return out
        }

        private fun resolveSibling(xmlPath: String, imagePath: String): String {
            if (imagePath.contains('/') || imagePath.contains('\\')) {
                val prefix = xmlPath.substringBeforeLast('/', "")
                return if (prefix.isEmpty()) imagePath else "$prefix/$imagePath"
            }
            return xmlPath.substringBeforeLast('.') + "." + imagePath.substringAfterLast('.')
        }

        private fun parseFrames(xml: String): Map<String, AtlasFrame> {
            val out = LinkedHashMap<String, AtlasFrame>()
            val re = Regex("<SubTexture\\s+([^>]*?)/?>", RegexOption.IGNORE_CASE)
            val attrRe = Regex("(\\w+)\\s*=\\s*\"([^\"]*)\"")
            for (m in re.findAll(xml)) {
                val attrs = attrRe.findAll(m.groupValues[1])
                    .associate { it.groupValues[1] to it.groupValues[2] }
                // TexturePacker appends the source file name; FNF's frame-label
                // prefixes never carry an extension, so drop it.
                val name = attrs["name"]?.removeSuffix(".png") ?: continue
                val w = attrs["width"]?.toIntOrNull() ?: 0
                val h = attrs["height"]?.toIntOrNull() ?: 0
                if (w <= 0 || h <= 0) continue
                out[name] = AtlasFrame(
                    name = name,
                    x = attrs["x"]?.toInt() ?: 0,
                    y = attrs["y"]?.toInt() ?: 0,
                    width = w,
                    height = h,
                    frameX = attrs["frameX"]?.toInt() ?: 0,
                    frameY = attrs["frameY"]?.toInt() ?: 0,
                    frameWidth = attrs["frameWidth"]?.toInt() ?: 0,
                    frameHeight = attrs["frameHeight"]?.toInt() ?: 0,
                    rotated = isTrue(attrs["rotated"]) || isTrue(attrs["rotate"]),
                )
            }
            return out
        }

        private fun isTrue(value: String?): Boolean =
            value != null && value.equals("true", ignoreCase = true)
    }
}

/**
 * Port of the 7-tap passes in `gaussianBlur.frag`.
 *
 * `gaussianBlur.frag` mixes a horizontal-only `blur13` with a vertical-only
 * one 50/50. The taps sit at ±1.4118, ±3.2941 and ±5.1765 texels, multiplied
 * by `_amount * 2` (`GaussianBlurShader(1)`), with the centre holding ~19.6%
 * of the weight. Samples outside the sheet are clamped to the edge like
 * `CLAMP_TO_EDGE`, and the result stays inside the source rect (the FNF shader
 * never renders beyond its sprite quad).
 */
internal fun crossBlur(src: Bitmap32, amount: Double, brightness: Double = 1.0): Bitmap32 {
    val offsets = doubleArrayOf(
        2.823529411764706 * amount,
        6.588235294117647 * amount,
        10.352941176470589 * amount,
    )
    val weights = doubleArrayOf(
        0.2969069646728344,
        0.09447039785044732,
        0.010381362401148057,
    )
    val centerWeight = 0.1964825501511404

    val width = src.width
    val height = src.height
    val out = Bitmap32(width, height, src.premultiplied)
    val horizontal = DoubleArray(4)
    val vertical = DoubleArray(4)

    fun accumulate(sx: Double, sy: Double, weight: Double, acc: DoubleArray) {
        val x0 = floor(sx).toInt()
        val y0 = floor(sy).toInt()
        val tx = sx - x0
        val ty = sy - y0
        for (dy in 0..1) {
            val py = (y0 + dy).coerceIn(0, height - 1)
            val wy = if (dy == 0) 1.0 - ty else ty
            for (dx in 0..1) {
                val px = (x0 + dx).coerceIn(0, width - 1)
                val wx = if (dx == 0) 1.0 - tx else tx
                val factor = weight * wx * wy
                if (factor == 0.0) continue
                val v = src.getInt(px, py)
                acc[0] += factor * (v and 0xFF)
                acc[1] += factor * ((v ushr 8) and 0xFF)
                acc[2] += factor * ((v ushr 16) and 0xFF)
                acc[3] += factor * ((v ushr 24) and 0xFF)
            }
        }
    }

    for (y in 0 until height) {
        for (x in 0 until width) {
            val center = src.getInt(x, y)
            for (ch in 0..3) {
                val value = when (ch) {
                    0 -> center and 0xFF
                    1 -> (center ushr 8) and 0xFF
                    2 -> (center ushr 16) and 0xFF
                    else -> (center ushr 24) and 0xFF
                } * centerWeight
                horizontal[ch] = value
                vertical[ch] = value
            }
            for (i in offsets.indices) {
                val offset = offsets[i]
                val weight = weights[i]
                accumulate(x + offset, y.toDouble(), weight, horizontal)
                accumulate(x - offset, y.toDouble(), weight, horizontal)
                accumulate(x.toDouble(), y + offset, weight, vertical)
                accumulate(x.toDouble(), y - offset, weight, vertical)
            }

            fun channel(index: Int): Int =
                ((horizontal[index] + vertical[index]) * 0.5).toInt().coerceIn(0, 255)

            // Only RGB is scaled: the sprite is ADD-blended, so the alpha
            // channel does not gate the colour.
            fun bright(index: Int): Int =
                (channel(index) * brightness).toInt().coerceIn(0, 255)

            out.setInt(
                x,
                y,
                (channel(3) shl 24) or (bright(2) shl 16) or
                    (bright(1) shl 8) or bright(0),
            )
        }
    }
    return out
}
