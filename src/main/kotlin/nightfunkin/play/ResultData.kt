package nightfunkin.play

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nightfunkin.core.KnfFiles
import java.io.File

/** Everything the results screen needs; set by [PlayScene] before switching scenes. */
data class ResultData(
    val songId: String,
    val title: String,
    val difficultyId: String,
    val variationId: String,
    val characterId: String,
    val score: Int,
    val tallies: ScoreTallies,
    val isNewHighscore: Boolean,
)

/** Stashed before switching to [ResultScene]. */
object ResultRequest {
    var data: ResultData? = null
}

// ===========================================================================
// preload/data/players/<id>.json
// ===========================================================================

/**
 * One results-screen animation entry from a playable character JSON.
 *
 * Mirrors `PlayerData.PlayerResultsAnimationData` in 0.8.6. Almost every field
 * is optional there, so the defaults here match the original's fallbacks.
 */
@Serializable
data class PlayerResultsAnimationData(
    val renderType: String = "animateatlas",
    val assetPath: String? = null,
    val scriptClass: String? = null,
    val startFrameLabel: String? = null,
    val loopFrameLabel: String? = null,
    val loopFrame: Int? = null,
    val looped: Boolean? = null,
    val scale: Double? = null,
    val delay: Double? = null,
    val offsets: List<Double>? = null,
    val zIndex: Int? = null,
    /** `both`, `naughty` or `safe`. */
    val filter: String = "both",
    val sound: String? = null,
)

@Serializable
data class PlayerResultsData(
    val music: Map<String, String> = emptyMap(),
    @SerialName("perfectGold") val perfectGold: List<PlayerResultsAnimationData> = emptyList(),
    val perfect: List<PlayerResultsAnimationData> = emptyList(),
    val excellent: List<PlayerResultsAnimationData> = emptyList(),
    val great: List<PlayerResultsAnimationData> = emptyList(),
    val good: List<PlayerResultsAnimationData> = emptyList(),
    @SerialName("loss") val loss: List<PlayerResultsAnimationData> = emptyList(),
) {
    fun animationsFor(rank: ScoringRank): List<PlayerResultsAnimationData> = when (rank) {
        ScoringRank.PERFECT_GOLD -> perfectGold
        ScoringRank.PERFECT -> perfect
        ScoringRank.EXCELLENT -> excellent
        ScoringRank.GREAT -> great
        ScoringRank.GOOD -> good
        ScoringRank.SHIT -> loss
    }

    fun musicFor(rank: ScoringRank): String? = music[rank.name]
}

@Serializable
data class PlayerData(
    val name: String = "",
    val ownedChars: List<String> = emptyList(),
    val results: PlayerResultsData? = null,
)

/**
 * FNF's `PlayerRegistry` subset needed by the results screen: find which
 * playable character owns the chart's player sprite, then read its results
 * animations.
 */
object ResultPlayerRegistry {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val entries: Map<String, PlayerData> by lazy {
        val dir = File(KnfFiles.assetsRoot, PLAYERS_DIR)
        dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            ?.mapNotNull { file ->
                runCatching {
                    val data = json.decodeFromString<PlayerData>(file.readText())
                    file.nameWithoutExtension to data
                }.onFailure {
                    println("KNF: could not read player '${file.name}': ${it.message}")
                }.getOrNull()
            }
            ?.toMap()
            .orEmpty()
    }

    /** `PlayerRegistry.getCharacterOwnerId`; falls back to BF like `ResultState`. */
    fun ownerOf(characterId: String): String =
        entries.entries.firstOrNull { (_, data) -> characterId in data.ownedChars }?.key ?: "bf"

    fun resultsFor(characterId: String): PlayerResultsData? =
        entries[ownerOf(characterId)]?.results

    fun ownerName(characterId: String): String =
        entries[ownerOf(characterId)]?.name ?: ownerOf(characterId)

    private const val PLAYERS_DIR = "preload/data/players"
}
