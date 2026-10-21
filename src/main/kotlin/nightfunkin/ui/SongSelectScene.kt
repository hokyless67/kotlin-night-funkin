package nightfunkin.ui

import korlibs.image.color.Colors
import korlibs.korge.scene.Scene
import korlibs.korge.view.Container
import korlibs.korge.view.SContainer
import korlibs.korge.view.Text
import korlibs.korge.view.addUpdater
import korlibs.korge.view.solidRect
import korlibs.korge.view.text
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nightfunkin.audio.MenuMusic
import nightfunkin.audio.SongPreviewPlayer
import nightfunkin.core.Constants
import nightfunkin.core.KnfInput
import nightfunkin.data.SongEntry
import nightfunkin.data.SongRegistry
import nightfunkin.play.PlayRequest
import nightfunkin.play.PlayScene
import kotlin.math.max
import kotlin.math.min

/**
 * Song select: a scrollable list of every song found under
 * `preload/data/songs`, plus difficulty picking. This is KNF's Freeplay.
 */
class SongSelectScene : Scene() {

    private val entries: List<SongEntry> by lazy { SongRegistry.songs }
    private val menuMusic = MenuMusic.player
    private val preview = SongPreviewPlayer()
    private var previewJob: Job? = null
    private var previewGeneration = 0
    private var selected = 0
    private var difficultyIndex = Constants.DEFAULT_DIFFICULTY_LIST.indexOf(Constants.DEFAULT_DIFFICULTY)
    private var variationIndex = 0
    private var time = 0.0
    private var statusText = ""
    private var statusTimer = 0.0

    private lateinit var listLayer: Container
    private lateinit var infoText: Text
    private lateinit var diffText: Text
    private lateinit var varText: Text
    private lateinit var statusLine: Text

    override suspend fun SContainer.sceneInit() {
        val header = text("FREEPLAY", textSize = 46.0, color = Colors.WHITE)
        header.x = 56.0
        header.y = 34.0
        addChild(header)

        val count = text("${entries.size} songs detected", textSize = 18.0, color = Colors["#6a6a7a"])
        count.x = 56.0
        count.y = 88.0
        addChild(count)

        listLayer = Container()
        addChild(listLayer)

        infoText = text("", textSize = 22.0, color = Colors["#9a9aae"])
        infoText.x = 56.0
        infoText.y = 560.0
        addChild(infoText)

        diffText = text("", textSize = 28.0, color = Colors.WHITE)
        diffText.x = 56.0
        diffText.y = 610.0
        addChild(diffText)

        varText = text("", textSize = 20.0, color = Colors["#7a7a90"])
        varText.x = 56.0
        varText.y = 652.0
        addChild(varText)

        statusLine = text("", textSize = 20.0, color = Colors["#66FF66"])
        statusLine.x = Constants.GAME_WIDTH - 420.0
        statusLine.y = 40.0
        addChild(statusLine)

        val help = text(
            "↑↓ / W S  select song    ← → / A D  difficulty    Q E  variation    ENTER play    ESC back",
            textSize = 16.0,
            color = Colors["#4f4f60"],
        )
        help.x = 56.0
        help.y = 690.0
        addChild(help)

        if (entries.isEmpty()) {
            setStatus("No songs found — is the FNF 'assets' folder reachable?")
        }
        rebuildList()
        refreshInfo()

        // FNF's first selection pauses freakyMenu, waits 0.25 s, then starts the
        // selected song's partial instrumental preview.
        if (entries.isNotEmpty()) {
            menuMusic.pause()
            requestPreview()
        }
    }

    private fun currentVariations(): List<String> =
        listOf(Constants.DEFAULT_VARIATION) + (entries.getOrNull(selected)?.metadata?.playData?.songVariations ?: emptyList())

    private fun currentVariation(): String {
        val vars = currentVariations()
        val index = variationIndex.coerceIn(0, (vars.size - 1).coerceAtLeast(0))
        return vars.getOrElse(index) { Constants.DEFAULT_VARIATION }
    }

    private fun currentDifficulties(): List<String> =
        entries.getOrNull(selected)?.difficultiesFor(currentVariation())
            ?: Constants.DEFAULT_DIFFICULTY_LIST

    /** Keep the usual difficulty when it exists; otherwise use the first one. */
    private fun resetDifficultyForVariation() {
        val diffs = currentDifficulties()
        val defaultIndex = diffs.indexOf(Constants.DEFAULT_DIFFICULTY)
        difficultyIndex = if (defaultIndex >= 0) defaultIndex else 0
    }

    private fun rebuildList() {
        listLayer.removeChildren()
        val start = maxOf(0, selected - 8)
        val end = minOf(entries.size, start + 16)
        for (i in start until end) {
            val song = entries[i]
            val isSel = i == selected
            val t = listLayer.text(
                if (isSel) "▶  ${song.name}" else "   ${song.name}",
                textSize = if (isSel) 28.0 else 24.0,
                color = if (isSel) Colors.WHITE else Colors["#7d7d90"],
            )
            t.x = 56.0
            t.y = 130.0 + (i - start) * 27.0
        }
    }

    private fun refreshInfo() {
        val song = entries.getOrNull(selected)
        if (song == null) {
            infoText.text = ""
            diffText.text = ""
            varText.text = ""
            return
        }
        val vars = currentVariations()
        variationIndex = variationIndex.coerceIn(0, (vars.size - 1).coerceAtLeast(0))

        val variation = vars.getOrElse(variationIndex) { Constants.DEFAULT_VARIATION }
        val metadata = song.metadataFor(variation)
        val diffs = song.difficultiesFor(variation)
        difficultyIndex = difficultyIndex.coerceIn(0, (diffs.size - 1).coerceAtLeast(0))
        infoText.text = "artist: ${metadata.artist}    charter: ${metadata.charter ?: "?"}" +
            "    stage: ${metadata.playData.stage}"
        diffText.text = diffs.joinToString("   ") { if (it == diffs[difficultyIndex]) "[$it]" else it }
        varText.text = "variation: " + vars.joinToString("   ") {
            if (it == vars[variationIndex]) "[$it]" else it
        } + "    song: ${metadata.songName}"
    }

    private fun setStatus(msg: String) {
        statusText = msg
        statusTimer = 3.0
        statusLine.text = msg
    }

    /**
     * `FreeplayState.changeSelection`: pause the menu track, wait
     * `FADE_IN_DELAY`, then load the current variation's instrumental slice.
     * Selection changes cancel an in-flight preview so a slow load can never
     * start playing the previous song.
     */
    private fun requestPreview() {
        previewGeneration++
        val generation = previewGeneration
        previewJob?.cancel()
        previewJob = null
        preview.stop()

        val song = entries.getOrNull(selected) ?: return
        val variation = currentVariation()
        val metadata = song.metadataFor(variation)
        val path = song.instrumentalPath(variation) ?: return
        val previewStart = metadata.playData.previewStart
        val previewEnd = metadata.playData.previewEnd

        previewJob = launch {
            delay(FADE_IN_DELAY_MS)
            if (generation != previewGeneration) return@launch
            preview.play(path, previewStart, previewEnd, isCurrent = { generation == previewGeneration })
        }
    }

    private fun stopPreview() {
        previewGeneration++
        previewJob?.cancel()
        previewJob = null
        preview.stop()
    }

    override suspend fun SContainer.sceneMain() {
        val knfInput = KnfInput(keys)
        addUpdater { dt ->
            val d = dt.secondsDouble()
            time += d
            knfInput.update()

            if (statusTimer > 0.0) {
                statusTimer -= d
                if (statusTimer <= 0.0) statusLine.text = ""
            }

            preview.update()

            if (entries.isNotEmpty()) {
                // W / Up = previous song, S / Down = next song
                if (knfInput.justPressed(2)) {
                    selected = (selected - 1 + entries.size) % entries.size
                    variationIndex = 0
                    resetDifficultyForVariation()
                    rebuildList(); refreshInfo()
                    requestPreview()
                }
                if (knfInput.justPressed(1)) {
                    selected = (selected + 1) % entries.size
                    variationIndex = 0
                    resetDifficultyForVariation()
                    rebuildList(); refreshInfo()
                    requestPreview()
                }
                // Q / E cycle song variations (erect, nightmare, ...). The
                // difficulty list belongs to the variation, not to the song.
                val vars = currentVariations()
                val variationDelta = when {
                    knfInput.nextVariation -> 1
                    knfInput.previousVariation -> -1
                    else -> 0
                }
                if (variationDelta != 0) {
                    variationIndex = (variationIndex + variationDelta + vars.size) % vars.size
                    resetDifficultyForVariation()
                    refreshInfo()
                    // `changeDiff` only reloads the preview when the variation
                    // (and therefore the instrumental suffix) changes.
                    requestPreview()
                }
                // A / Left and D / Right cycle this variation's difficulties.
                val diffs = currentDifficulties()
                if (knfInput.justPressed(0)) {
                    difficultyIndex = (difficultyIndex - 1 + diffs.size) % diffs.size
                    refreshInfo()
                }
                if (knfInput.justPressed(3)) {
                    difficultyIndex = (difficultyIndex + 1) % diffs.size
                    refreshInfo()
                }
            }

            if (knfInput.accept) launch { startSelected() }
            if (knfInput.back) {
                stopPreview()
                launch { sceneContainer.changeTo<MainMenuScene>() }
            }
        }
    }

    override suspend fun sceneDestroy() {
        stopPreview()
        preview.dispose()
    }

    private suspend fun startSelected() {
        val song = entries.getOrNull(selected) ?: return
        val diffs = currentDifficulties()
        val vars = currentVariations()
        if (song.difficulty(diffs[difficultyIndex], vars[variationIndex]) == null) {
            setStatus("${song.name} has no chart for ${diffs[difficultyIndex]}")
            return
        }
        PlayRequest.songId = song.id
        PlayRequest.difficulty = diffs[difficultyIndex]
        PlayRequest.variation = vars[variationIndex]
        stopPreview()
        sceneContainer.changeTo<PlayScene>()
    }

    private companion object {
        /** `FreeplayState.FADE_IN_DELAY`. */
        const val FADE_IN_DELAY_MS = 250L
    }
}
