package nightfunkin.data

import kotlinx.serialization.json.Json
import nightfunkin.core.Constants
import nightfunkin.core.KnfFiles
import nightfunkin.timing.SongTimeChange
import java.io.File

private val knfJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * One song as it will actually be played: a metadata + chart pair for a given
 * variation and difficulty.
 */
class SongDifficulty(
    val song: SongEntry,
    val variation: String,
    val difficulty: String,
    private val chart: SongChartData,
) {
    /** Variation metadata, e.g. erect's stage/audio/characters/time changes. */
    private val metadata: SongMetadata = song.metadataFor(variation)

    /** Notes sorted by hit time — required for the strumline's streaming index. */
    val notes: List<SongNoteData> =
        chart.notes[difficulty].orEmpty().sortedBy { it.time }

    val events: List<SongEventData> =
        chart.events.sortedBy { it.time }

    val scrollSpeed: Float =
        chart.scrollSpeed[difficulty]
            ?: chart.scrollSpeed[Constants.DEFAULT_DIFFICULTY]
            ?: 1f

    val timeChanges: List<SongTimeChange> =
        metadata.timeChanges.map {
            SongTimeChange(it.timeStamp, it.bpm, it.numerator, it.denominator, it.beatTuplets)
        }

    val songName: String get() = metadata.songName
    val artist: String get() = metadata.artist
    val charter: String? get() = metadata.charter
    val offsets: SongOffsets get() = metadata.offsets
    val characters: SongCharacterData get() = metadata.playData.characters
    val stage: String get() = metadata.playData.stage
    val noteStyle: String get() = metadata.playData.noteStyle
    val rating: Int get() = metadata.playData.ratings[difficulty] ?: 0

    /** Events can be shared across variations; merge in the variation's own if any. */
    fun allEvents(): List<SongEventData> = events
}

/** A song folder on disk plus lazily-loaded metadata/charts. */
class SongEntry(val id: String) {

    private val chartCache = HashMap<String, SongChartData?>()
    private val variationMetadataCache = HashMap<String, SongMetadata>()

    val metadata: SongMetadata by lazy {
        decodeMetadata(KnfFiles.text(KnfFiles.songMetadata(id)))
    }

    /**
     * FNF stores a separate metadata file per variation. Its playData controls
     * the stage, characters, note skin, timing and instrumental, so using the
     * default metadata for an erect/pico run silently renders the wrong song.
     */
    fun metadataFor(variation: String = Constants.DEFAULT_VARIATION): SongMetadata {
        if (variation == Constants.DEFAULT_VARIATION) return metadata
        return variationMetadataCache.getOrPut(variation) {
            KnfFiles.textOrNull(KnfFiles.songMetadata(id, variation))
                ?.let { decodeMetadata(it, metadata) }
                ?: metadata
        }
    }

    val name: String get() = metadata.songName
    val artist: String get() = metadata.artist
    val charter: String? get() = metadata.charter

    val variations: List<String>
        get() = listOf(Constants.DEFAULT_VARIATION) + metadata.playData.songVariations

    val difficulties: List<String> get() = difficultiesFor()

    fun difficultiesFor(variation: String = Constants.DEFAULT_VARIATION): List<String> =
        metadataFor(variation).playData.difficulties
            .ifEmpty { metadata.playData.difficulties }
            .ifEmpty { Constants.DEFAULT_DIFFICULTY_LIST }

    fun chart(variation: String = Constants.DEFAULT_VARIATION): SongChartData? =
        chartCache.getOrPut(variation) {
            val text = KnfFiles.textOrNull(KnfFiles.songChart(id, variation)) ?: return@getOrPut null
            runCatching { knfJson.decodeFromString<SongChartData>(text) }.getOrNull()
        }

    fun difficulty(
        difficulty: String = Constants.DEFAULT_DIFFICULTY,
        variation: String = Constants.DEFAULT_VARIATION,
    ): SongDifficulty? {
        val chart = chart(variation) ?: return null
        if (chart.notes[difficulty] == null) return null
        return SongDifficulty(this, variation, difficulty, chart)
    }

    fun hasInstrumental(instSuffix: String = ""): Boolean =
        KnfFiles.resolveSound(KnfFiles.instrumentalStem(id, instSuffix)) != null

    /**
     * Resolves the playable instrumental file. KorGE's JVM decoder handles MP3
     * but not Ogg, so MP3 wins when both exist (the player compensates for the
     * decoder latency via `Constants.MP3_DELAY_MS`).
     */
    fun instrumentalPath(
        variation: String = Constants.DEFAULT_VARIATION,
        instId: String = "",
    ): String? {
        val chars = charactersOrNull(variation)
        val suffix = if (instId.isNotEmpty() && chars?.altInstrumentals?.contains(instId) == true) "-$instId"
        else chars?.instrumental?.takeIf { it.isNotEmpty() }?.let { "-$it" } ?: ""
        return KnfFiles.resolveSound(KnfFiles.instrumentalStem(id, suffix))
            ?: KnfFiles.resolveSound(KnfFiles.instrumentalStem(id))
    }

    /**
     * Resolves the voices file for a character, walking back through hyphen
     * separated id parts and variation suffixes exactly like FNF's
     * `SongDifficulty.buildPlayerVoiceList` / `buildOpponentVoiceList`.
     */
    fun vocalsPath(charId: String, variation: String = Constants.DEFAULT_VARIATION): String? {
        val varSuffix = if (variation == Constants.DEFAULT_VARIATION) "" else "-$variation"
        val baseCharId = charId.substringBefore('-')
        // `vocalsStem` inserts the dash itself, so pass the bare character id.
        val stems = listOf(
            KnfFiles.vocalsStem(id, "$charId$varSuffix"),
            KnfFiles.vocalsStem(id, "$baseCharId$varSuffix"),
            KnfFiles.vocalsStem(id, charId),
            KnfFiles.vocalsStem(id, baseCharId),
        )
        for (stem in stems) KnfFiles.resolveSound(stem)?.let { return it }
        return null
    }

    fun charactersOrNull(variation: String = Constants.DEFAULT_VARIATION): SongCharacterData? =
        runCatching { metadataFor(variation).playData.characters }.getOrNull()

    private fun decodeMetadata(text: String, fallback: SongMetadata? = null): SongMetadata =
        runCatching { knfJson.decodeFromString<SongMetadata>(text) }
            .getOrElse { fallback ?: SongMetadata(songName = id, artist = "unknown") }

    override fun toString() = "$id ($name)"
}

/** Scans `preload/data/songs/` and hands out [SongEntry] instances. */
object SongRegistry {
    private const val SONGS_DIR = "preload/data/songs"

    val songs: List<SongEntry> by lazy { scan() }

    private val byId = HashMap<String, SongEntry>()

    fun scan(): List<SongEntry> {
        val dir = File(KnfFiles.assetsRoot, SONGS_DIR)
        if (!dir.isDirectory) {
            println("KNF: song directory not found at ${dir.absolutePath}")
            return emptyList()
        }
        val entries = dir.listFiles()
            ?.filter { it.isDirectory }
            ?.filter { File(it, "${it.name}-metadata.json").isFile }
            ?.sortedBy { it.name.lowercase() }
            ?.map { SongEntry(it.name) }
            .orEmpty()

        byId.clear()
        entries.forEach { byId[it.id] = it }
        println("KNF: found ${entries.size} songs in ${dir.absolutePath}")
        return entries
    }

    fun get(id: String): SongEntry? = byId[id] ?: songs.firstOrNull { it.id == id }
}
