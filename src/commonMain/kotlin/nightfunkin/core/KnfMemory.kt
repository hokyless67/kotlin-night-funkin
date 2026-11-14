package nightfunkin.core

import korlibs.image.bitmap.Bitmap
import korlibs.korge.annotations.KorgeExperimental
import korlibs.korge.internal.KorgeInternal
import korlibs.korge.view.Views
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import nightfunkin.platform.KnfEnv
import nightfunkin.platform.KnfPlatform

/**
 * FNF's texture/sound cache management, ported for a phone's GL budget.
 *
 * `FunkinMemory.hx` never lets two screens' atlases live at once: permanent
 * textures (UI, fonts, note skins) are cached once, and everything else is
 * "current"; on a state switch `preparePurgeTextureCache` + `purgeTextureCache`
 * destroy the previous state's graphics, `LoadingState` adds
 * `clearFreeplay()` right before the gameplay state is created, and the sticker
 * transition runs `purgeCache(true)` when the pile is complete and
 * `clearStickers()` after the peel-off.
 *
 * KorGE has no such notion - its `AgBitmapTextureManager` simply keeps every
 * uploaded texture for 60 frames - and `SceneContainer` starts the incoming
 * scene's `sceneInit` *before* destroyng the outgoing one. On a phone that
 * overlap is the peak: week 7 alone is ~150 MB of stage sheets plus three
 * character sheets, so loading it while Freeplay still holds its own art is
 * what ends in a refused GL upload (a black screen) or a failed stem decode.
 *
 * KNF therefore runs the same purges at the same points, and additionally
 * hand-rolls the one thing KorGE cannot do: it drops the *decoded* bitmaps of
 * caches that outlive a scene (module-level `lazy` fields) so their memory is
 * collectable before the next scene decodes its own sheets.
 */
object KnfMemory {

    /** Hooks that drop module-level art caches (Freeplay capsules, ...). */
    private val artCachePurgeHooks = ArrayList<() -> Unit>()

    /**
     * Registers a cache to empty when a gameplay scene starts
     * (`FunkinMemory.clearFreeplay` / `purgeTextureCache`).
     */
    fun registerArtCache(purge: () -> Unit) {
        if (purge !in artCachePurgeHooks) artCachePurgeHooks += purge
    }

    /**
     * Called at the very start of a heavy scene's `sceneInit`.
     *
     * Order matters: drop the module-level caches first (so nothing outside the
     * atlas cache still references their bitmaps), then the atlas cache, then
     * the uploaded GL textures, then ask for a collection while the main thread
     * is blocked preparing the new scene anyway.
     */
    fun beginScene(views: Views, name: String) {
        if (!KnfPlatform.isMobile) return
        for (hook in artCachePurgeHooks) runCatching { hook() }
        TextureAtlas.clearCache()
        releaseTextures(views)
        // KorGE evicts any texture that has not been drawn for 60 frames.
        // KNF drops textures explicitly on every scene switch (`releaseTextures`
        // above), so that idle eviction only ever costs a *re-upload* mid-song -
        // a note splash that waits 60 frames between hits paid for its sheet
        // again. A window longer than any song keeps the cache stable.
        runCatching { views.renderContext.agBitmapTextureManager.framesBetweenGC = 100_000 }
        KnfPlatform.requestMemoryReclaim()
        log(views, "enter $name")
    }

    /** `FunkinMemory.clearStickers`, called when the peel-off finishes. */
    fun clearStickers(views: Views, stickers: List<Bitmap>) {
        if (!KnfPlatform.isMobile) return
        releaseBitmaps(views, stickers)
        log(views, "stickers released")
    }

    /**
     * Called at the end of a heavy scene's `sceneInit`, and again from its
     * `sceneAfterInit`.
     *
     * Decoding a song's three stems plus the stage leaves a few hundred
     * megabytes of temporary buffers behind (compressed bytes, the decoder's
     * growing sample arrays). On a phone the heap is capped - a session that
     * prints `heap=441/450MB of 512MB` right after `sceneInit` is full of that
     * garbage. Without collecting it, the first allocation burst after the
     * countdown (the first judged note builds the score text and the popups)
     * has to trigger a *blocking* GC of the whole heap: the freeze players see
     * on the first note. Collecting here happens while the loading screen and
     * the countdown cover the display, so it is invisible.
     *
     * The second call matters on top of that: KorGE destroys the outgoing
     * scene *after* the incoming `sceneInit`, so its art is still reachable
     * during the first call and only the `sceneAfterInit` one can reclaim it.
     */
    fun endScene(views: Views, name: String) {
        if (!KnfPlatform.isMobile) return
        // ART answers an explicit GC request with a collection that can still
        // be sweeping when it returns, and a single pass has measured 4 MB
        // reclaimed out of ~176 MB that was already garbage. Asking twice is
        // what actually brings the heap back before the countdown ends.
        KnfPlatform.requestMemoryReclaim()
        KnfPlatform.requestMemoryReclaim()
        log(views, "ready $name")
        if (!debug) return
        println("KNF_MEM [atlases] ${TextureAtlas.cacheStats()}")
        println("KNF_MEM [scene probe] leavingSceneAlive=$leavingSceneAlive")
        reportHolders(views)
    }

    /** `KNF_DEBUG_MEM=1` turns on the atlas/job/scene dumps. */
    private val debug: Boolean get() = KnfEnv["KNF_DEBUG_MEM"] == "1"

    /**
     * Names the two ways a destroyed scene can survive: a coroutine that is
     * still running on the views' dispatcher, or a view tree that is still
     * attached to the stage.
     */
    private fun reportHolders(views: Views) {
        runCatching {
            val job = views.coroutineContext[Job]
            val children = job?.children?.toList().orEmpty()
            println("KNF_MEM [jobs] views children=${children.size}")
            for (child in children) dumpJob(child, "  ", 2)
        }.onFailure { println("KNF_MEM [jobs] unavailable: $it") }
        runCatching {
            val stage = views.stage
            val names = stage.children.map { it::class.simpleName ?: "?" }
            println("KNF_MEM [views] stage=${stage.numChildren} $names")
        }.onFailure { println("KNF_MEM [views] unavailable: $it") }
    }

    private fun dumpJob(job: Job, indent: String, depth: Int) {
        val marker = if (job.toString() == leavingSceneJobLabel) "  <- outgoing scene job" else ""
        println("KNF_MEM [jobs] $indent$job$marker")
        if (depth <= 0) return
        for (child in job.children) dumpJob(child, "$indent  ", depth - 1)
    }

    /**
     * Keeps a weak handle on the outgoing scene so the next scene can tell a
     * slow collection apart from a real retention: a scene still reachable
     * after `sceneAfterInit` + GC is being held by something global.
     */
    private var leavingSceneTracked = false

    /**
     * `Job.toString()` of the outgoing scene's supervisor. Only the string is
     * kept: a `Job` gives access to its children, and those children are the
     * coroutines that reference the scene.
     */
    private var leavingSceneJobLabel: String? = null

    fun probeLeavingScene(scene: Any) {
        if (!KnfPlatform.isMobile) return
        KnfPlatform.trackWeak(scene)
        leavingSceneTracked = true
        leavingSceneJobLabel = (scene as? CoroutineScope)?.coroutineContext?.get(Job)?.toString()
    }

    val leavingSceneAlive: Boolean
        get() = leavingSceneTracked && KnfPlatform.trackedStillAlive()

    /** Drops the GPU textures of [bitmaps]; they re-upload if drawn again. */
    @OptIn(KorgeInternal::class, KorgeExperimental::class)
    fun releaseBitmaps(views: Views, bitmaps: List<Bitmap>) {
        if (bitmaps.isEmpty()) return
        val manager = views.renderContext.agBitmapTextureManager
        for (bitmap in bitmaps) {
            runCatching { manager.removeBitmap(bitmap, "KNF art purge") }
        }
    }

    /**
     * Deletes every texture KorGE currently has on the GPU.
     *
     * `AgBitmapTextureManager` only re-uploads a bitmap when it is drawn again,
     * so this is always safe; it just moves the upload cost to the next frame.
     */
    @OptIn(KorgeInternal::class, KorgeExperimental::class)
    fun releaseTextures(views: Views) {
        val manager = views.renderContext.agBitmapTextureManager
        val bitmaps = manager.getBitmapsWithTextureInfoCopy().keys.toList()
        for (bitmap in bitmaps) {
            runCatching { manager.removeBitmap(bitmap, "KNF scene switch") }
        }
    }

    /**
     * Writes one line with the heap and GL texture usage.
     *
     * Phones are where this matters and logcat is not always reachable, so the
     * numbers also land in `Documents/KNF/knf.log` (see `KnfBootLog`). Kept
     * cheap: a couple of counters plus a coroutine-free `Runtime` query.
     */
    @OptIn(KorgeInternal::class, KorgeExperimental::class)
    fun log(views: Views, tag: String) {
        if (!KnfPlatform.isMobile) return
        val textures = runCatching {
            korlibs.memory.unit.ByteUnits.fromBytes(
                views.renderContext.agBitmapTextureManager.managedTextureMemory,
            )
        }.getOrNull()
        println("KNF_MEM [$tag] heap=${KnfPlatform.memoryReport()} textures=$textures")
    }
}
