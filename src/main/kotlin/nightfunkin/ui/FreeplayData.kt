package nightfunkin.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nightfunkin.core.KnfFiles
import nightfunkin.data.SongEntry
import nightfunkin.data.SongRegistry
import java.io.File

/**
 * One week's row from `preload/data/levels`.
 *
 * Freeplay orders its capsules by level, not alphabetically, exactly like
 * `LevelRegistry.listSortedLevelIds()`.
 */
internal data class FreeplayLevel(
    val id: String,
    val name: String,
    val capsuleName: String?,
    val capsuleTitleOffsets: List<Double>,
    val songs: List<String>,
)

/** A song and the story week it belongs to. `level == null` means a bonus song. */
internal data class FreeplayEntry(
    val song: SongEntry,
    val level: FreeplayLevel?,
) {
    /** `SongMenuItem.checkWeek`: explicit capsule title first, then the level id. */
    val weekLabel: String?
        get() = level?.let { it.capsuleName ?: formatLevelId(it.id) }

    /** `Level.getCapsuleTitleOffsets()`, applied to the generated week text. */
    val weekLabelOffsets: List<Double>
        get() = level?.capsuleTitleOffsets.orEmpty()
}

/** Enough of the `data/ui/freeplay/albums` JSON files for the right-hand album roll. */
internal data class FreeplayAlbum(
    val id: String,
    val name: String,
    val artAsset: String,
    val titleAsset: String,
    val titleOffsets: List<Double>,
)

@Serializable
private data class LevelCapsuleJson(
    val name: String? = null,
    val offsets: List<Double> = emptyList(),
)

@Serializable
private data class LevelJson(
    val name: String = "",
    val capsule: LevelCapsuleJson? = null,
    val songs: List<String> = emptyList(),
)

@Serializable
private data class AlbumJson(
    val name: String = "",
    val albumArtAsset: String = "",
    val albumTitleAsset: String = "",
    val albumTitleOffsets: List<Double> = emptyList(),
)

/**
 * Freeplay's song catalog.
 *
 * FNF builds this from the level registry (which supplies the capsule week and
 * the exact play order). Songs that exist on disk but are not listed in a week
 * are appended at the end so custom charts do not disappear.
 */
internal object FreeplayCatalog {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** `LevelRegistry.listBaseGameEntryIds()` - the shipped order matters. */
    private val BASE_LEVEL_ORDER = listOf(
        "tutorial",
        "week1",
        "week2",
        "week3",
        "week4",
        "week5",
        "week6",
        "week7",
        "weekend1",
        "sserafim",
    )

    val levels: Map<String, FreeplayLevel> by lazy { loadLevels() }

    val entries: List<FreeplayEntry> by lazy {
        val used = HashSet<String>()
        val out = ArrayList<FreeplayEntry>()

        for (levelId in BASE_LEVEL_ORDER) {
            val level = levels[levelId] ?: continue
            for (songId in level.songs) {
                val song = SongRegistry.get(songId) ?: continue
                out += FreeplayEntry(song, level)
                used += song.id
            }
        }

        // Bonus/custom songs that have no level entry. Keep them visible rather
        // than silently dropping content that SongRegistry discovered.
        SongRegistry.songs
            .asSequence()
            .filter { it.id !in used }
            .sortedBy { it.name.lowercase() }
            .forEach { out += FreeplayEntry(it, null) }

        out
    }

    val albums: Map<String, FreeplayAlbum> by lazy { loadAlbums() }

    private fun loadLevels(): Map<String, FreeplayLevel> {
        val dir = File(KnfFiles.assetsRoot, "preload/data/levels")
        if (!dir.isDirectory) return emptyMap()

        val out = LinkedHashMap<String, FreeplayLevel>()
        for (file in dir.listFiles()?.filter { it.extension == "json" } ?: return emptyMap()) {
            val raw = runCatching {
                json.decodeFromString<LevelJson>(file.readText())
            }.getOrNull() ?: continue
            val id = file.nameWithoutExtension
            out[id] = FreeplayLevel(
                id = id,
                name = raw.name,
                capsuleName = raw.capsule?.name?.takeIf { it.isNotBlank() },
                capsuleTitleOffsets = raw.capsule?.offsets.orEmpty(),
                songs = raw.songs,
            )
        }
        return out
    }

    private fun loadAlbums(): Map<String, FreeplayAlbum> {
        val dir = File(KnfFiles.assetsRoot, "preload/data/ui/freeplay/albums")
        if (!dir.isDirectory) return emptyMap()

        val out = LinkedHashMap<String, FreeplayAlbum>()
        for (file in dir.listFiles()?.filter { it.extension == "json" } ?: return emptyMap()) {
            val raw = runCatching {
                json.decodeFromString<AlbumJson>(file.readText())
            }.getOrNull() ?: continue
            if (raw.albumArtAsset.isBlank()) continue
            out[file.nameWithoutExtension] = FreeplayAlbum(
                id = file.nameWithoutExtension,
                name = raw.name,
                artAsset = raw.albumArtAsset,
                titleAsset = raw.albumTitleAsset,
                titleOffsets = raw.albumTitleOffsets,
            )
        }
        return out
    }
}

/**
 * `SongMenuItem.checkWeek`'s fallback formatter:
 * `bonusWeek2` -> `bonus Week 2`, `week1` -> `Week 1`.
 */
internal fun formatLevelId(id: String): String {
    if (id.isEmpty()) return id
    val out = StringBuilder()
    for (i in id.indices) {
        val ch = id[i]
        if (i > 0) {
            val prev = id[i - 1]
            if ((prev.isLowerCase() && ch.isUpperCase()) ||
                (prev.isDigit() != ch.isDigit())
            ) {
                out.append(' ')
            }
        }
        out.append(ch)
    }
    return out.toString().replaceFirstChar { it.uppercase() }
}
