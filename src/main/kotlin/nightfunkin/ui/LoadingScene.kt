package nightfunkin.ui

import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.Colors
import korlibs.image.color.RGBA
import korlibs.image.font.Font
import korlibs.image.font.TtfFont
import korlibs.io.resources.Resourceable
import korlibs.korge.scene.Scene
import korlibs.korge.view.Container
import korlibs.korge.view.Image
import korlibs.korge.view.RoundRect
import korlibs.korge.view.SContainer
import korlibs.korge.view.Text
import korlibs.korge.view.roundRect
import korlibs.korge.view.solidRect
import korlibs.korge.view.text
import korlibs.math.geom.RectCorners
import korlibs.math.geom.Size
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nightfunkin.core.Constants
import nightfunkin.core.KnfFiles
import nightfunkin.data.SongRegistry
import nightfunkin.play.PlayScene
import nightfunkin.play.Stage
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FNF 0.8.6's `FunkinPreloader` - the VFD boot loader - ported to KorGE.
 *
 * A black screen, a 16-piece progress bar, the DS-Digital status readout, and
 * the animated hex/scanline overlay from `VFDOverlay.hx`. Each of the eleven
 * preload stages is held on screen for at least `PRELOADER_MIN_STAGE_TIME`
 * seconds, then the display fades out with `easeInOutCirc` over `FADE_TIME`
 * seconds before the first real scene starts.
 */
class LoadingScene : Scene() {

    private companion object {
        const val BAR_PADDING = 20.0
        const val BAR_HEIGHT = 12.0
        const val PIECES_COUNT = 16
        const val PIECE_GAP = 8.0
        const val MIN_STAGE_TIME = 0.1
        const val FADE_TIME = 2.5
        const val TOTAL_STEPS = 11
        const val ELLIPSIS_TIME = 0.5

        val BAR_COLOR = Colors["#A4FF11"]
        val BACKGROUND = Colors.BLACK

        val STEPS = listOf(
            PreloaderState.DownloadingAssets,
            PreloaderState.PreloadingPlayAssets,
            PreloaderState.InitializingScripts,
            PreloaderState.CachingGraphics,
            PreloaderState.CachingAudio,
            PreloaderState.CachingData,
            PreloaderState.ParsingSpritesheets,
            PreloaderState.ParsingStages,
            PreloaderState.ParsingCharacters,
            PreloaderState.ParsingSongs,
        )
    }

    private var currentState = PreloaderState.NotStarted
    private var completeTime = Double.MAX_VALUE

    private lateinit var progressPieces: List<RoundRect>
    private lateinit var progressLeftText: Text
    private lateinit var progressRightText: Text
    private lateinit var progressLines: Container
    private lateinit var rightTextGroup: Container
    private lateinit var vfdOverlay: Image

    private var progressY = 0.0

    override suspend fun SContainer.sceneInit() {
        val width = Constants.GAME_WIDTH.toDouble()
        val height = Constants.GAME_HEIGHT.toDouble()

        solidRect(width, height, BACKGROUND)

        progressY = height * 0.67
        progressLines = Container().also {
            it.y = progressY
            addChild(it)
        }
        createProgressLines(progressLines, width)

        val maxBarWidth = width - BAR_PADDING * 2
        val pieceWidth = maxBarWidth / PIECES_COUNT
        val pieces = ArrayList<RoundRect>(PIECES_COUNT)
        for (i in 0 until PIECES_COUNT) {
            val piece = roundRect(
                Size(pieceWidth - PIECE_GAP, BAR_HEIGHT),
                RectCorners(4.0, 4.0, 4.0, 4.0),
                fill = BAR_COLOR,
            )
            piece.x = i * pieceWidth
            piece.y = progressY + 8.0
            piece.alpha = 0.1
            pieces += piece
        }
        progressPieces = pieces

        val dsDigital = loadFont("fonts/DS-DIGIB.TTF")
        val quantico = loadFont("fonts/Quantico-Regular.ttf")
        val inconsolataBlack = loadFont("fonts/Inconsolata-Black.ttf")
        val inconsolataBold = loadFont("fonts/Inconsolata-Bold.ttf")

        // FNF measures the height while this is still the single-line
        // "Downloading assets..." placeholder, then never repositions it when
        // the real two-line status text replaces it.
        progressLeftText = makeText(
            x = BAR_PADDING,
            y = progressY,
            value = "Downloading assets...",
            size = 32.0,
            color = BAR_COLOR,
            font = dsDigital,
        )
        progressLeftText.y = progressY - progressLeftText.height * 2.5

        progressRightText = makeText(
            x = BAR_PADDING,
            y = height - BAR_PADDING - BAR_HEIGHT - 16.0 - 4.0,
            value = "0%",
            size = 16.0,
            color = BAR_COLOR,
            font = dsDigital,
        )

        rightTextGroup = Container().also {
            it.x = width * 0.64
            it.y = progressLeftText.y
            addChild(it)
        }
        buildRightTextGroup(rightTextGroup, BAR_COLOR, quantico, inconsolataBlack, inconsolataBold)

        vfdOverlay = Image(createVfdOverlay(width.toInt(), height.toInt())).also {
            it.smoothing = false
            it.x = 0.0
            it.y = 0.0
            addChild(it)
        }
    }

    override suspend fun SContainer.sceneMain() {
        val startNanos = System.nanoTime()
        fun now(): Double = (System.nanoTime() - startNanos) / 1_000_000_000.0

        // FNF opens on `NotStarted`; this frame is the initial "Loading 0/11".
        updateGraphics(0.0, 0.0)
        delay(16L)

        for (state in STEPS) {
            currentState = state
            runPreloadStep(state)
            val stepStart = now()

            while (true) {
                val elapsed = now()
                val stepElapsed = elapsed - stepStart
                val stepProgress = (stepElapsed / MIN_STAGE_TIME).coerceIn(0.0, 1.0)
                updateGraphics(stepProgress, elapsed)
                if (stepElapsed >= MIN_STAGE_TIME) break
                delay(16L)
            }
        }

        currentState = PreloaderState.Complete
        completeTime = now()
        updateGraphics(1.0, completeTime)

        while (true) {
            val elapsed = now()
            val finished = elapsed - completeTime
            if (finished >= FADE_TIME) break
            updateGraphics(1.0, elapsed)
            delay(16L)
        }

        if (LoadingSceneTarget.toSong && SongRegistry.get(bootSongId()) != null) {
            launch { sceneContainer.changeTo<PlayScene>() }
        } else {
            launch { sceneContainer.changeTo<TitleScene>() }
        }
    }

    private fun updateGraphics(percent: Double, elapsed: Double) {
        val clampedPercent = percent.coerceIn(0.0, 1.0)
        val piecesToRender = (clampedPercent * progressPieces.size).toInt()
        val pieceAlpha = if (completeTime <= elapsed) {
            1.0 - easeInOutCirc((elapsed - completeTime) / FADE_TIME)
        } else {
            1.0
        }

        for ((i, piece) in progressPieces.withIndex()) {
            piece.alpha = when {
                completeTime <= elapsed -> pieceAlpha
                i <= piecesToRender -> 0.9
                else -> 0.1
            }
        }

        val ellipsis = ".".repeat((elapsed / ELLIPSIS_TIME).toInt() % 3 + 1)
        val label = currentState.leftLabel ?: return
        progressLeftText.text = "$label \n${currentState.step}/$TOTAL_STEPS $ellipsis"
        progressLeftText.alpha = pieceAlpha

        progressRightText.text = "${floor(clampedPercent * 100.0).toInt()}%"
        progressRightText.alpha = pieceAlpha
        progressRightText.x = Constants.GAME_WIDTH - BAR_PADDING - progressRightText.width

        rightTextGroup.y = progressLeftText.y
        rightTextGroup.alpha = pieceAlpha
        progressLines.alpha = pieceAlpha

        vfdOverlay.alpha = (0.95 + 0.05 * sin(elapsed * 100.0)).coerceIn(0.0, 1.0)
    }

    private fun createProgressLines(parent: Container, width: Double) {
        parent.solidRect(width + 4.0, 2.0, BAR_COLOR).also {
            it.x = -2.0
            it.y = 0.0
        }
        parent.solidRect(width + 4.0, 2.0, BAR_COLOR).also {
            it.x = -2.0
            it.y = 28.0
        }
        parent.solidRect(2.0, 30.0, BAR_COLOR).also {
            it.x = -2.0
            it.y = 0.0
        }
        parent.solidRect(2.0, 30.0, BAR_COLOR).also {
            it.x = width
            it.y = 0.0
        }
    }

    private fun buildRightTextGroup(
        parent: Container,
        color: RGBA,
        quantico: Font?,
        inconsolataBlack: Font?,
        inconsolataBold: Font?,
    ) {
        parent.roundRect(Size(64.0, 20.0), RectCorners(5.0, 5.0, 5.0, 5.0), fill = color).also {
            it.y = 40.0
        }
        parent.roundRect(Size(58.0, 20.0), RectCorners(5.0, 5.0, 5.0, 5.0), fill = color).also {
            it.x = 70.0
            it.y = 40.0
        }
        parent.roundRect(Size(128.0, 20.0), RectCorners(5.0, 5.0, 5.0, 5.0), fill = color).also {
            it.y = 40.0
            it.alpha = 0.1
        }

        parent.makeText(11.0, 36.0, "DSP", 20.0, Colors.BLACK, quantico)
        parent.makeText(79.0, 36.0, "KNF", 20.0, Colors.BLACK, quantico)
        parent.makeText(-100.0, 40.0, "ENHANCED", 16.0, color, inconsolataBlack)
        parent.makeText(0.0, 0.0, "NATURAL STEREO", 36.0, color, inconsolataBold)
    }

    private fun Container.makeText(
        x: Double,
        y: Double,
        value: String,
        size: Double,
        color: RGBA,
        font: Font?,
    ): Text = text(value, textSize = size, color = color).also {
        it.x = x
        it.y = y
        if (font != null) it.font = Resourceable.Fixed(font)
    }

    private fun loadFont(path: String): Font? =
        KnfFiles.fileOrNull(path)?.let { file ->
            runCatching { TtfFont(file.readBytes(), file.name) }.getOrNull()
        }

    /**
     * The static part of FNF's `VFDOverlay` fragment shader, baked once at the
     * virtual resolution. The overlay is black with alpha, exactly like the
     * shader output; only the small `flicker` term is animated at runtime.
     */
    private fun createVfdOverlay(width: Int, height: Int): Bitmap32 {
        val pixels = IntArray(width * height)
        val s = 1.7320508
        val edgeWidth = 0.5
        val edgeSharpness = 0.11
        val root3 = sqrt(3.0)
        val norm = sqrt(1.0 + 3.0)
        val nx = 1.0 / norm
        val ny = root3 / norm

        for (y in 0 until height) {
            val v = -2.0 * ((y + 0.5) / height) * 50.0
            for (x in 0 until width) {
                val u = -2.0 * ((x + 0.5) / width) * 50.0

                val cx1 = floor(u) + 0.5
                val cy1 = floor(v / s) + 0.5
                val cx2 = floor(u - 0.5) + 0.5
                val cy2 = floor((v - 1.0) / s) + 0.5

                val ox1 = u - cx1
                val oy1 = v - cy1 * s
                val ox2 = u - (cx2 + 0.5)
                val oy2 = v - (cy2 + 0.5) * s

                val d1 = ox1 * ox1 + oy1 * oy1
                val d2 = ox2 * ox2 + oy2 * oy2
                val hx: Double
                val hy: Double
                if (d1 < d2) {
                    hx = ox1
                    hy = oy1
                } else {
                    hx = ox2
                    hy = oy2
                }

                val px = abs(hx)
                val py = abs(hy)
                val edgeDist = max(px * nx + py * ny, px)
                val outline = smoothstep(edgeWidth - edgeSharpness, edgeWidth, edgeDist)
                val colorMix = 0.3 * outline
                val sinShit = smoothstep(-3.0, 1.0, sin(v * 3.0))

                // `u_time` starts at zero, so flicker is 1.0 in the baked copy.
                var alpha = colorMix * 0.5 + sinShit * 0.5
                val specs = fract(sin(u * 12.9898 + v * 78.233) * 43758.5453)
                alpha = alpha * 0.9 + specs * 0.1

                val a = (alpha.coerceIn(0.0, 1.0) * 255.0).roundToInt()
                pixels[y * width + x] = a shl 24
            }
        }

        return Bitmap32(width, height, pixels, true)
    }

    private fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun fract(value: Double): Double = value - floor(value)

    private fun easeInOutCirc(x: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        val result = if (x < 0.5) {
            (1.0 - sqrt(1.0 - 4.0 * x * x)) / 2.0
        } else {
            (sqrt(1.0 - 4.0 * (1.0 - x) * (1.0 - x)) + 1.0) / 2.0
        }
        return if (result.isNaN()) 1.0 else result
    }

    private fun runPreloadStep(state: PreloaderState) {
        when (state) {
            PreloaderState.PreloadingPlayAssets -> runCatching { SongRegistry.scan() }
            PreloaderState.CachingData -> preloadJsonTexts("preload/data/notestyles")
            PreloaderState.ParsingStages -> preloadStageData()
            PreloaderState.ParsingCharacters -> preloadJsonTexts("preload/data/characters")
            PreloaderState.ParsingSongs -> preloadSongMetadata()
            else -> Unit
        }
    }

    private fun preloadStageData() {
        for (id in jsonIds("preload/data/stages")) {
            runCatching { Stage.file(id) }
        }
    }

    private fun preloadSongMetadata() {
        for (song in runCatching { SongRegistry.songs }.getOrDefault(emptyList())) {
            runCatching { song.metadata }
        }
    }

    private fun preloadJsonTexts(directory: String) {
        for (id in jsonIds(directory)) {
            runCatching { KnfFiles.text("$directory/$id.json") }
        }
    }

    private fun jsonIds(directory: String): List<String> =
        File(KnfFiles.assetsRoot, directory)
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") }
            ?.sorted()
            .orEmpty()

    private fun bootSongId(): String = System.getenv("KNF_SONG").orEmpty()

    private enum class PreloaderState(val leftLabel: String?, val step: Int) {
        NotStarted("Loading", 0),
        DownloadingAssets("Downloading assets", 1),
        PreloadingPlayAssets("Preloading assets", 2),
        InitializingScripts("Initializing scripts", 3),
        CachingGraphics("Caching graphics", 4),
        CachingAudio("Caching audio", 5),
        CachingData("Caching data", 6),
        ParsingSpritesheets("Parsing spritesheets", 7),
        ParsingStages("Parsing stages", 8),
        ParsingCharacters("Parsing characters", 9),
        ParsingSongs("Parsing songs", 10),
        Complete("Finishing up", 11),
    }
}

/**
 * Keeps the boot target outside the scene, because KorGE constructs scenes via
 * its Injector and `changeTo` cannot pass constructor arguments.
 */
object LoadingSceneTarget {
    var toSong: Boolean = false
}
