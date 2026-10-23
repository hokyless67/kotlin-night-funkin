package nightfunkin.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import nightfunkin.core.Constants

// ===========================================================================
// On-disk schema (Friday Night Funkin' 0.8.6, song metadata v2.2.4 / chart v2.0.0)
//
// Files:
//   preload/data/songs/<id>/<id>-metadata.json            (variation "default")
//   preload/data/songs/<id>/<id>-metadata-<variation>.json
//   preload/data/songs/<id>/<id>-chart.json
//   preload/data/songs/<id>/<id>-chart-<variation>.json
// ===========================================================================

@Serializable
data class SongTimeChangeRaw(
    @SerialName("t") val timeStamp: Float = 0f,
    @SerialName("bpm") val bpm: Float = 100f,
    @SerialName("n") val numerator: Int = 4,
    @SerialName("d") val denominator: Int = 4,
    @SerialName("bt") val beatTuplets: List<Int> = listOf(4, 4, 4, 4),
    @SerialName("b") val beatTime: Float = 0f,
)

@Serializable
data class SongOffsets(
    @SerialName("instrumental") val instrumental: Float = 0f,
    @SerialName("altInstrumentals") val altInstrumentals: Map<String, Float> = emptyMap(),
    @SerialName("vocals") val vocals: Map<String, Float> = emptyMap(),
    @SerialName("altVocals") val altVocals: Map<String, Map<String, Float>> = emptyMap(),
) {
    fun instrumentalOffsetFor(instId: String): Float =
        if (instId.isNotEmpty()) altInstrumentals[instId] ?: instrumental else instrumental

    fun vocalOffsetFor(charId: String, instId: String): Float {
        if (instId.isNotEmpty()) {
            altVocals[instId]?.get(charId)?.let { return it }
        }
        return vocals[charId] ?: 0f
    }
}

@Serializable
data class SongCharacterData(
    val player: String = "bf",
    // FNF's `SongCharacterData.girlfriend` defaults to an empty string. Songs
    // that want a girlfriend declare her explicitly; the Tutorial deliberately
    // has none because its opponent *is* GF.
    val girlfriend: String = "",
    val opponent: String = "dad",
    val instrumental: String = "",
    val altInstrumentals: List<String> = emptyList(),
    val opponentVocals: List<String>? = null,
    val playerVocals: List<String>? = null,
) {
    val resolvedOpponentVocals: List<String> get() = opponentVocals ?: listOf(opponent)
    val resolvedPlayerVocals: List<String> get() = playerVocals ?: listOf(player)
}

@Serializable
data class SongPlayData(
    val album: String? = null,
    val ratings: Map<String, Int> = emptyMap(),
    val songVariations: List<String> = emptyList(),
    val difficulties: List<String> = listOf(Constants.DEFAULT_DIFFICULTY),
    val characters: SongCharacterData = SongCharacterData(),
    val stage: String = "mainStage",
    val noteStyle: String = "funkin",
    val stickerPack: String? = null,
    val discordRPCImage: String = "",
    val previewStart: Double = 0.0,
    val previewEnd: Double = 0.2,
)

@Serializable
data class SongMetadata(
    val version: String = "2.2.4",
    val songName: String = "Unknown",
    val artist: String = "",
    val charter: String? = null,
    val divisions: Int? = null,
    val looped: Boolean = false,
    val timeFormat: String = "ms",
    val timeChanges: List<SongTimeChangeRaw> = listOf(SongTimeChangeRaw(0f, 100f)),
    val offsets: SongOffsets = SongOffsets(),
    val playData: SongPlayData = SongPlayData(),
    val generatedBy: String = "",
)

@Serializable
data class NoteParamData(
    @SerialName("n") val name: String,
    @SerialName("v") val value: JsonElement? = null,
)

@Serializable
data class SongNoteData(
    /** Hit time in milliseconds. */
    @SerialName("t") val time: Float,
    /** `data % 4` = arrow direction, `data / 4` = strumline index (0 = player). */
    @SerialName("d") val data: Int,
    /** Sustain length in milliseconds; `0` means a tap. */
    @SerialName("l") val length: Float = 0f,
    /** Note kind id, e.g. `"mine"`, `"alt"`. */
    @SerialName("k") val kind: String? = null,
    @SerialName("p") val params: List<NoteParamData> = emptyList(),
) {
    val direction: Int get() = data % Constants.STRUMLINE_SIZE_DEFAULT
    val strumlineIndex: Int get() = data / Constants.STRUMLINE_SIZE_DEFAULT
    val isPlayerNote: Boolean get() = strumlineIndex == 0
    val isHoldNote: Boolean get() = length > 0f
}

@Serializable
data class SongEventData(
    @SerialName("t") val time: Float,
    @SerialName("e") val kind: String,
    /** Either a scalar (legacy form, e.g. `"v": 1`) or an object. */
    @SerialName("v") val value: JsonElement? = null,
    /** Runtime flag, never serialised. */
    @SerialName("activated") var activated: Boolean = false,
) {
    private fun obj(): JsonObject? = value as? JsonObject

    fun getInt(key: String): Int? = (obj()?.get(key) as? JsonPrimitive)?.intOrNull
    fun getFloat(key: String): Float? = (obj()?.get(key) as? JsonPrimitive)?.floatOrNull
    fun getString(key: String): String? = (obj()?.get(key) as? JsonPrimitive)?.content
    fun getBool(key: String): Boolean? = (obj()?.get(key) as? JsonPrimitive)?.booleanOrNull

    /**
     * Legacy charts store a bare value (`"v": 1`) instead of an object. FNF folds
     * that into a single-key struct; we do the same under [defaultKey].
     */
    fun valueAsStruct(defaultKey: String = "key"): JsonObject {
        obj()?.let { return it }
        val prim = value as? JsonPrimitive ?: return JsonObject(emptyMap())
        return JsonObject(mapOf(defaultKey to prim))
    }
}

@Serializable
data class SongChartData(
    val version: String = "2.0.0",
    val scrollSpeed: Map<String, Float> = emptyMap(),
    val events: List<SongEventData> = emptyList(),
    val notes: Map<String, List<SongNoteData>> = emptyMap(),
    val generatedBy: String = "",
)
