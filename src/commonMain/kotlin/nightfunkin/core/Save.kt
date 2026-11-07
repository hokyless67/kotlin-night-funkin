package nightfunkin.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nightfunkin.platform.KnfFile
import nightfunkin.platform.KnfPlatform

@Serializable
data class SaveData(
    /** Global audio offset in ms. Positive = notes must be hit later. */
    val globalOffsetMs: Float = 0f,
    val downscroll: Boolean = true,
    val ghostTapping: Boolean = true,
    val muted: Boolean = false,
    val volumes: Map<String, Float> = emptyMap(),
    val songRatings: Map<String, String> = emptyMap(),
    val highscores: Map<String, Int> = emptyMap(),
    /** `Scoring.tallyCompletion` for the best/current clear, 0..1. */
    val completions: Map<String, Float> = emptyMap(),
    /**
     * FNF's `save.data.favoriteSongs`: Freeplay's favourited songs, keyed by
     * `FreeplaySongData.idAndVariation` (`songId:variation`).
     */
    val favoriteSongs: List<String> = emptyList(),
    /**
     * FNF's `save.data.unlocks.charactersSeen`. KNF has no Character Select
     * substate to clear the "new character" flag, so both stock playable
     * characters start seen. That selects the regular `BoyfriendCard` backing
     * card, matching the reference build once Pico has been viewed.
     */
    val charactersSeen: List<String> = listOf("bf", "pico"),
    /** Stock 0.8.6 player registry unlocks. */
    val unlockedCharacters: List<String> = listOf("bf", "pico"),
)

/**
 * Tiny persistent settings store, saved as `knf-save.json` inside the
 * platform's user files dir (`KnfPlatform.userFilesDir`) - the user home on
 * desktop, the app storage dir on Android, `Documents/KNF` on iOS.
 *
 * FNF exposes an audio offset for exactly this reason: every machine has a
 * different output latency, and no engine clock can compensate for it.
 */
object Save {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    private val file: KnfFile by lazy {
        KnfFile(KnfPlatform.userFilesDir()).child("knf-save.json")
    }

    var data: SaveData = SaveData()
        private set

    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        data = runCatching {
            if (file.isFile()) {
                json.decodeFromString<SaveData>(file.readText())
            } else {
                SaveData()
            }
        }.getOrElse {
            println("KNF: could not read save, starting fresh: ${it.message}")
            SaveData()
        }

        // Older KNF builds copied FNF's fresh-save `charactersSeen: ["bf"]`
        // state, which permanently selected `NewCharacterCard` because KNF has
        // no Character Select to mark Pico as seen. Treat that exact stock
        // value as the already-viewed state so Freeplay uses `BoyfriendCard`.
        if (data.charactersSeen == listOf("bf")) {
            data = data.copy(charactersSeen = listOf("bf", "pico"))
            save()
        }
    }

    fun save() {
        runCatching {
            file.writeText(json.encodeToString(SaveData.serializer(), data))
        }.onFailure { println("KNF: could not write save: ${it.message}") }
    }

    // ---- typed accessors -------------------------------------------------

    var globalOffsetMs: Float
        get() = data.globalOffsetMs
        set(value) {
            data = data.copy(globalOffsetMs = value.coerceIn(-500f, 500f))
            save()
        }

    var downscroll: Boolean
        get() = data.downscroll
        set(value) {
            data = data.copy(downscroll = value)
            save()
        }

    var ghostTapping: Boolean
        get() = data.ghostTapping
        set(value) {
            data = data.copy(ghostTapping = value)
            save()
        }

    fun setSongRating(songId: String, rating: String) {
        data = data.copy(songRatings = data.songRatings + (songId to rating))
        save()
    }

    fun songRating(songId: String): String? = data.songRatings[songId]

    fun submitHighscore(key: String, score: Int): Boolean {
        val best = data.highscores[key] ?: 0
        if (score <= best) return false
        data = data.copy(highscores = data.highscores + (key to score))
        save()
        return true
    }

    fun highscore(key: String): Int = data.highscores[key] ?: 0

    fun setCompletion(key: String, value: Float) {
        val best = maxOf(data.completions[key] ?: 0f, value.coerceIn(0f, 1f))
        data = data.copy(completions = data.completions + (key to best))
        save()
    }

    fun completion(key: String): Float = data.completions[key] ?: 0f

    // ---- freeplay favourites (`Save.isSongFavorited` et al.) --------------

    fun isSongFavorited(id: String): Boolean = id in data.favoriteSongs

    fun favoriteSong(id: String) {
        if (id in data.favoriteSongs) return
        data = data.copy(favoriteSongs = data.favoriteSongs + id)
        save()
    }

    fun unfavoriteSong(id: String) {
        if (id !in data.favoriteSongs) return
        data = data.copy(favoriteSongs = data.favoriteSongs - id)
        save()
    }

    /** Mirrors `PlayerRegistry.hasNewCharacter()`. */
    fun hasNewCharacter(): Boolean =
        data.unlockedCharacters.any { it !in data.charactersSeen }

    fun countUnlockedCharacters(): Int = data.unlockedCharacters.size
}
