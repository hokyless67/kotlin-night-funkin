package nightfunkin.core

import korlibs.image.bitmap.Bitmap32
import korlibs.io.compression.deflate.ZLib
import korlibs.io.compression.uncompress
import kotlin.math.abs

/**
 * KNF's own PNG decoder for the colour types KorGE 6 does not implement.
 *
 * `korlibs.image.format.PNG` only decodes 8-bit RGBA (colour type 6); every
 * other colour type falls into a `TODO("Bytes: $bpp")` and throws. The JDK
 * used to cover those through `javax.imageio`, which does not exist on mobile
 * targets — so this decoder handles the rest of the spec instead:
 *
 *  - colour types 0 (greyscale), 2 (RGB), 3 (indexed), 4 (grey+alpha)
 *  - bit depths 1/2/4/8/16 (16-bit is reduced to 8 by dropping the low byte)
 *  - optional `tRNS` transparency for types 0/2/3
 *  - non-interlaced images only (FNF's exported sheets never interlace)
 *
 * Output is a `Bitmap32` whose ints are packed little-endian as `0xAABBGGRR`,
 * exactly like the old ImageIO path, and still *not* premultiplied —
 * [KnfFiles] premultiplies everything in one pass afterwards.
 */
object KnfPng {
    /**
     * True for PNGs that must bypass KorGE's own decoder: anything but 8-bit
     * RGBA. Layout mirrors the old `needsImageIo` check so behaviour is
     * byte-for-byte identical, just routed to [decode].
     */
    fun needsOwnDecoder(d: ByteArray): Boolean {
        if (d.size < 30) return false
        val pngSignature = d[0] == 0x89.toByte() &&
            d[1] == 'P'.code.toByte() && d[2] == 'N'.code.toByte() && d[3] == 'G'.code.toByte()
        if (!pngSignature) return false
        // IHDR payload: bit depth at byte 24, colour type at byte 25.
        val bitDepth = d[24].toInt() and 0xFF
        val colorType = d[25].toInt() and 0xFF
        return colorType != 6 || bitDepth != 8
    }

    fun decode(data: ByteArray): Bitmap32 {
        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = 0
        var interlace = 0
        var palette: IntArray? = null
        var paletteAlpha: ByteArray? = null
        var trnsGrey = -1
        var trnsColor: IntArray? = null
        val idat = ArrayList<ByteArray>()

        var i = 8 // skip the 8-byte signature
        while (i + 8 <= data.size) {
            val length = readU32BE(data, i)
            val type = readChunkType(data, i + 4)
            val payload = i + 8
            val end = payload + length
            if (end + 4 > data.size) error("KNF PNG: truncated chunk '$type'")
            when (type) {
                "IHDR" -> {
                    width = readU32BE(data, payload)
                    height = readU32BE(data, payload + 4)
                    bitDepth = data[payload + 8].toInt() and 0xFF
                    colorType = data[payload + 9].toInt() and 0xFF
                    val compression = data[payload + 10].toInt() and 0xFF
                    val filterMethod = data[payload + 11].toInt() and 0xFF
                    interlace = data[payload + 12].toInt() and 0xFF
                    if (compression != 0 || filterMethod != 0) {
                        error("KNF PNG: unsupported compression=$compression filter=$filterMethod")
                    }
                }
                "PLTE" -> {
                    palette = IntArray(length / 3) { index ->
                        val b = payload + index * 3
                        (data[b].toInt() and 0xFF) shl 16 or
                            ((data[b + 1].toInt() and 0xFF) shl 8) or
                            (data[b + 2].toInt() and 0xFF)
                    }
                }
                "tRNS" -> when (colorType) {
                    3 -> paletteAlpha = data.copyOfRange(payload, end)
                    0 -> trnsGrey = readU16BE(data, payload)
                    2 -> trnsColor = intArrayOf(
                        readU16BE(data, payload),
                        readU16BE(data, payload + 2),
                        readU16BE(data, payload + 4),
                    )
                }
                "IDAT" -> idat += data.copyOfRange(payload, end)
                "IEND" -> break
            }
            i = end + 4 // skip the CRC
        }

        if (width <= 0 || height <= 0) error("KNF PNG: bad size ${width}x$height")
        if (interlace != 0) error("KNF PNG: interlaced images are not supported")
        if (idat.isEmpty()) error("KNF PNG: no IDAT chunks")

        val channels = when (colorType) {
            0 -> 1
            2 -> 3
            3 -> 1
            4 -> 2
            6 -> 4
            else -> error("KNF PNG: unknown colour type $colorType")
        }
        val depthOk = when (colorType) {
            0 -> bitDepth in intArrayOf(1, 2, 4, 8, 16)
            2, 4, 6 -> bitDepth == 8 || bitDepth == 16
            3 -> bitDepth in intArrayOf(1, 2, 4, 8)
            else -> false
        }
        if (!depthOk) error("KNF PNG: colour type $colorType with bit depth $bitDepth")

        val idatBytes = idat.reduce { acc, chunk -> acc + chunk }
        val raw = idatBytes.uncompress(ZLib)

        val bitsPerPixel = channels * bitDepth
        // PNG's filter bpp rounds up to whole bytes, minimum one.
        val filterBpp = ((bitsPerPixel + 7) / 8).coerceAtLeast(1)
        val bytesPerRow = (width * bitsPerPixel + 7) / 8
        val expected = (bytesPerRow + 1) * height
        if (raw.size < expected) error("KNF PNG: short stream (${raw.size} < $expected)")

        val rows = unfilter(raw, height, bytesPerRow, filterBpp)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = rows[y]
            val base = y * width
            when (colorType) {
                0 -> {
                    val transparent = trnsGrey >= 0 && trnsGrey < (1 shl bitDepth)
                    for (x in 0 until width) {
                        val sample = readSample(row, x, bitDepth)
                        val grey = scale(sample, bitDepth)
                        val a = if (transparent && sample == trnsGrey) 0 else 255
                        pixels[base + x] = pack(a, grey, grey, grey)
                    }
                }
                2 -> {
                    val transparentColor = trnsColor
                    for (x in 0 until width) {
                        val s0 = readSample(row, x * 3, bitDepth)
                        val s1 = readSample(row, x * 3 + 1, bitDepth)
                        val s2 = readSample(row, x * 3 + 2, bitDepth)
                        val r = scale(s0, bitDepth)
                        val g = scale(s1, bitDepth)
                        val b = scale(s2, bitDepth)
                        val a = if (transparentColor != null &&
                            transparentColor.contentEquals(intArrayOf(s0, s1, s2))
                        ) 0 else 255
                        pixels[base + x] = pack(a, r, g, b)
                    }
                }
                3 -> {
                    val pal = palette ?: error("KNF PNG: indexed image without PLTE")
                    for (x in 0 until width) {
                        val index = readSample(row, x, bitDepth)
                        if (index >= pal.size) error("KNF PNG: palette index $index out of range")
                        val rgb = pal[index]
                        val a = paletteAlpha?.getOrNull(index)?.toInt()?.and(0xFF) ?: 255
                        pixels[base + x] = pack(a, (rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
                    }
                }
                4 -> {
                    for (x in 0 until width) {
                        val grey = scale(readSample(row, x * 2, bitDepth), bitDepth)
                        val a = scale(readSample(row, x * 2 + 1, bitDepth), bitDepth)
                        pixels[base + x] = pack(a, grey, grey, grey)
                    }
                }
                6 -> {
                    for (x in 0 until width) {
                        val r = scale(readSample(row, x * 4, bitDepth), bitDepth)
                        val g = scale(readSample(row, x * 4 + 1, bitDepth), bitDepth)
                        val b = scale(readSample(row, x * 4 + 2, bitDepth), bitDepth)
                        val a = scale(readSample(row, x * 4 + 3, bitDepth), bitDepth)
                        pixels[base + x] = pack(a, r, g, b)
                    }
                }
            }
        }
        return Bitmap32(width, height, pixels, false)
    }

    // ---- scanline un-filtering ------------------------------------------

    private fun unfilter(
        raw: ByteArray,
        height: Int,
        bytesPerRow: Int,
        filterBpp: Int,
    ): Array<ByteArray> {
        val rows = Array(height) { ByteArray(bytesPerRow) }
        var pos = 0
        for (y in 0 until height) {
            val filter = raw[pos].toInt() and 0xFF
            pos++
            val line = rows[y]
            raw.copyInto(line, 0, pos, pos + bytesPerRow)
            pos += bytesPerRow
            val prev = if (y > 0) rows[y - 1] else null
            when (filter) {
                0 -> Unit
                1 -> for (x in filterBpp until bytesPerRow) {
                    line[x] = ((line[x].toInt() and 0xFF) + (line[x - filterBpp].toInt() and 0xFF)).toByte()
                }
                2 -> for (x in 0 until bytesPerRow) {
                    line[x] = ((line[x].toInt() and 0xFF) + ((prev?.get(x)?.toInt() ?: 0) and 0xFF)).toByte()
                }
                3 -> for (x in 0 until bytesPerRow) {
                    val left = if (x >= filterBpp) line[x - filterBpp].toInt() and 0xFF else 0
                    val up = (prev?.get(x)?.toInt() ?: 0) and 0xFF
                    line[x] = ((line[x].toInt() and 0xFF) + ((left + up) ushr 1)).toByte()
                }
                4 -> for (x in 0 until bytesPerRow) {
                    val a = if (x >= filterBpp) line[x - filterBpp].toInt() and 0xFF else 0
                    val b = (prev?.get(x)?.toInt() ?: 0) and 0xFF
                    val c = if (x >= filterBpp) (prev?.get(x - filterBpp)?.toInt() ?: 0) and 0xFF else 0
                    line[x] = ((line[x].toInt() and 0xFF) + paeth(a, b, c)).toByte()
                }
                else -> error("KNF PNG: unknown filter $filter")
            }
        }
        return rows
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    // ---- sample access ----------------------------------------------------

    /** Reads the [index]-th sample of the row as an unsigned raw value. */
    private fun readSample(row: ByteArray, index: Int, depth: Int): Int = when {
        depth == 8 -> row[index].toInt() and 0xFF
        depth == 16 -> (row[index * 2].toInt() and 0xFF shl 8) or (row[index * 2 + 1].toInt() and 0xFF)
        else -> {
            // Sub-byte depths pack MSB-first.
            val bitIndex = index * depth
            val byte = row[bitIndex shr 3].toInt() and 0xFF
            val shift = 8 - depth - (bitIndex and 7)
            (byte shr shift) and ((1 shl depth) - 1)
        }
    }

    /** Maps a raw sample to the 0..255 range (16-bit keeps the high byte). */
    private fun scale(sample: Int, depth: Int): Int = when {
        depth == 8 -> sample
        depth == 16 -> sample shr 8
        else -> sample * 255 / ((1 shl depth) - 1)
    }

    /** `Bitmap32` packs little-endian as `0xAABBGGRR`. */
    private fun pack(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (b shl 16) or (g shl 8) or r

    private fun readU32BE(d: ByteArray, offset: Int): Int =
        ((d[offset].toInt() and 0xFF) shl 24) or
            ((d[offset + 1].toInt() and 0xFF) shl 16) or
            ((d[offset + 2].toInt() and 0xFF) shl 8) or
            (d[offset + 3].toInt() and 0xFF)

    private fun readU16BE(d: ByteArray, offset: Int): Int =
        ((d[offset].toInt() and 0xFF) shl 8) or (d[offset + 1].toInt() and 0xFF)

    private fun readChunkType(d: ByteArray, offset: Int): String =
        buildString(4) {
            for (k in 0 until 4) append(d[offset + k].toInt().toChar())
        }
}
