package nightfunkin.core

import korlibs.image.bitmap.Bitmap
import korlibs.image.bitmap.Bitmap32
import korlibs.image.bitmap.sliceWithSize
import korlibs.korge.view.Container
import korlibs.korge.view.Image
import korlibs.korge.view.image
import korlibs.math.geom.RectangleInt
import korlibs.math.geom.slice.RectSlice

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
        if (!frame.rotated) {
            bitmap.sliceWithSize(frame.x, frame.y, frame.width, frame.height)
        } else {
            val upright = extractRotatedCcw(bitmap, frame.x, frame.y, frame.width, frame.height)
            RectSlice(upright, RectangleInt(0, 0, upright.width, upright.height))
        }
    }

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
        return img
    }

    companion object {
        private val cache = HashMap<String, TextureAtlas>()

        /** Loads and caches the atlas whose XML lives at [atlasPath]. */
        fun load(atlasPath: String): TextureAtlas = cache.getOrPut(atlasPath) {
            val xml = KnfFiles.text(atlasPath)
            val bitmapPath = Regex("imagePath\\s*=\\s*\"([^\"]+)\"")
                .find(xml)?.groupValues?.get(1)
                ?: error("KNF: atlas $atlasPath has no imagePath")
            val resolved = resolveSibling(atlasPath, bitmapPath)
            TextureAtlas(KnfFiles.bitmap(resolved), parseFrames(xml))
        }

        /** Like [load], but returns null instead of throwing on a broken file. */
        fun tryLoad(atlasPath: String): TextureAtlas? = runCatching { load(atlasPath) }.getOrNull()

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
        fun loadPacker(listPath: String): TextureAtlas = cache.getOrPut(listPath) {
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
            TextureAtlas(KnfFiles.bitmap(png), frames)
        }

        fun tryLoadPacker(listPath: String): TextureAtlas? =
            runCatching { loadPacker(listPath) }.getOrNull()

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
