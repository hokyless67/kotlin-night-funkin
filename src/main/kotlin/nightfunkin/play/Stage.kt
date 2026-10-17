package nightfunkin.play

import korlibs.image.bitmap.Bitmap
import korlibs.korge.view.Container
import korlibs.korge.view.Image
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nightfunkin.core.KnfFiles
import korlibs.math.geom.degrees

// ===========================================================================
// preload/data/stages/<id>.json
// ===========================================================================

@Serializable
data class StagePropData(
    val name: String = "",
    /** Either an image path, or a `#rrggbb` fill for solid-colour props. */
    val assetPath: String = "",
    val position: List<Double> = listOf(0.0, 0.0),
    val scale: List<Double> = listOf(1.0, 1.0),
    val alpha: Double = 1.0,
    val zIndex: Int = 0,
    /** Parallax factors; `[0, 0]` pins the prop to the camera. */
    val scroll: List<Double> = listOf(1.0, 1.0),
    val angle: Double = 0.0,
    val isPixel: Boolean = false,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
)

@Serializable
data class StageCharacterData(
    val position: List<Double> = listOf(0.0, 0.0),
    val scale: Double? = null,
    val zIndex: Int = 0,
    val alpha: Double = 1.0,
    val angle: Double = 0.0,
    val scroll: List<Double> = listOf(1.0, 1.0),
    val cameraOffsets: List<Double> = listOf(0.0, 0.0),
)

@Serializable
data class StageCharacters(
    @SerialName("bf") val bf: StageCharacterData? = null,
    @SerialName("gf") val gf: StageCharacterData? = null,
    @SerialName("dad") val dad: StageCharacterData? = null,
)

@Serializable
data class StageData(
    val name: String = "",
    val directory: String = "",
    val cameraZoom: Double? = null,
    val props: List<StagePropData> = emptyList(),
    val characters: StageCharacters = StageCharacters(),
)

/** A loaded stage definition: props, character placement and camera zoom. */
class StageDataFile(val id: String) {
    val data: StageData = runCatching {
        stageJson.decodeFromString<StageData>(KnfFiles.text("preload/data/stages/$id.json"))
    }.getOrElse {
        println("KNF: no stage data for '$id': $it")
        StageData(name = id)
    }

    val props: List<StagePropData> get() = data.props
    val cameraZoom: Double get() = data.cameraZoom ?: 1.0
    val characters: StageCharacters get() = data.characters

    fun characterFor(slot: String): StageCharacterData? =
        when (slot) {
            "bf" -> characters.bf
            "gf" -> characters.gf
            "dad" -> characters.dad
            else -> null
        }
}

/**
 * The visible half of a stage: its background props, in FNF's `zIndex` order.
 *
 * Positions are **stage space**, not screen space. FNF lets the camera sit
 * anywhere over a stage-sized world, so KNF applies one shared transform for
 * the props and the characters rather than baking screen coordinates into each
 * prop — that is what `StageSpace` is for.
 */
class Stage(id: String) : Container() {

    private val file = StageDataFile(id)
    private val loaded = ArrayList<Image>()

    /** Parallax factors of each loaded prop, parallel to [loaded]. */
    val scrollFactors = ArrayList<Pair<Double, Double>>()

    init {
        // `Stage.refresh()` sorts every child by zIndex, ascending.
        for (prop in file.props.sortedBy { it.zIndex }) {
            val img = buildProp(prop) ?: continue
            addChild(img)
            loaded += img
            scrollFactors += (prop.scroll.getOrElse(0) { 1.0 } to prop.scroll.getOrElse(1) { 1.0 })
        }
        if (loaded.isEmpty()) println("KNF: stage '$id' has no usable props")
    }

    private fun buildProp(prop: StagePropData): Image? {
        if (prop.assetPath.isEmpty()) return null
        if (prop.assetPath.startsWith("#")) return buildSolidColor(prop)

        val bmp: Bitmap = resolveAsset(prop.assetPath) ?: return null
        return Image(bmp).also { apply(it, prop, bmp.width.toDouble(), bmp.height.toDouble()) }
    }

    /**
     * Solid-colour props: FNF makes a 1x1 sprite and scales it to `scale`, which
     * is how the speaker/lamp blocks in later weeks are drawn.
     */
    private fun buildSolidColor(prop: StagePropData): Image? {
        val w = prop.scale.getOrElse(0) { 1.0 }
        val h = prop.scale.getOrElse(1) { 1.0 }
        if (w <= 0 || h <= 0) return null
        val argb = parseHexColor(prop.assetPath) ?: return null
        val bmp = korlibs.image.bitmap.Bitmap32(
            1, 1, intArrayOf(argb), false,
        )
        return Image(bmp).also { apply(it, prop, w, h, extraScaleX = w, extraScaleY = h) }
    }

    private fun apply(
        img: Image,
        prop: StagePropData,
        width: Double,
        height: Double,
        extraScaleX: Double = 1.0,
        extraScaleY: Double = 1.0,
    ) {
        val sx = prop.scale.getOrElse(0) { 1.0 } * extraScaleX
        val sy = prop.scale.getOrElse(1) { 1.0 } * extraScaleY
        // Flipping mirrors within the sprite's rect, so shift x by its width.
        img.scaleX = if (prop.flipX) -sx else sx
        img.scaleY = if (prop.flipY) -sy else sy
        img.x = prop.position.getOrElse(0) { 0.0 } + if (prop.flipX) width * sx else 0.0
        img.y = prop.position.getOrElse(1) { 0.0 } + if (prop.flipY) height * sy else 0.0
        img.alpha = prop.alpha
        img.rotation = prop.angle.degrees
        img.visible = true
    }

    /**
     * Resolves a prop's `assetPath` to a PNG.
     *
     * Props with no animations are plain `loadTexture` in FNF, so a bare `.png`
     * is all that is needed - but where it lives is not obvious. `school.json`
     * says `weeb/weebSky`, which only exists under `assets/week6/images`, so the
     * lookup has to walk every asset library rather than just `shared`/`preload`.
     * See [KnfFiles.resolve].
     */
    private fun resolveAsset(assetPath: String): Bitmap? {
        val path = KnfFiles.resolve(assetPath, ".png")
        if (path == null) {
            println("KNF: stage prop asset missing: $assetPath")
            println("KNF:   tried " + KnfFiles.resolveCandidates(assetPath, ".png"))
            return null
        }
        return KnfFiles.bitmapOrNull(path)
    }

    private fun parseHexColor(text: String): Int? {
        val hex = text.removePrefix("#")
        if (hex.length != 6 && hex.length != 8) return null
        val value = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 6) (0xFF000000L or value).toInt() else value.toInt()
    }

    val propCount: Int get() = loaded.size

    companion object {
        private val cache = HashMap<String, StageDataFile>()
        fun file(id: String): StageDataFile = cache.getOrPut(id) { StageDataFile(id) }
    }
}

private val stageJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}