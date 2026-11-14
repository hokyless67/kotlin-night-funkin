package nightfunkin.ui

import korlibs.image.bitmap.Bitmap

/**
 * The frame the outgoing scene leaves on screen when Freeplay opens.
 *
 * FNF opens Freeplay as a *substate* (`openSubState`) and MainMenuState sets
 * `persistentDraw = true`, so the menu keeps rendering behind the capsule /
 * backing-card entrance until the freeplay backdrop fades in. KorGE swaps
 * scenes instead, so the menu hands its last rendered frame over here and
 * `SongSelectScene` draws it as its bottom layer.
 *
 * The scene keeps the frame as its bottom layer for the whole visit: the
 * freeplay backdrop covers it from the DJ intro on, and the exit animation
 * uncovers it again as the layers fly away (FNF closes the substate onto the
 * still-drawn menu). It is released when a song starts or the scene dies, so
 * the extra bitmap never overlaps gameplay.
 *
 * The last frame is *also* remembered for the session. Results rebuilds
 * Freeplay from scratch (`ResultState` -> `FreeplayState.build`, which is a
 * fresh `MainMenuState` with the substate on top), and there is no menu render
 * to hand over on that path - without the remembered frame the exit would
 * reveal the black fallback until the new menu state appears.
 */
object FreeplayBackdrop {

    private var pending: Bitmap? = null
    private var remembered: Bitmap? = null

    /** Gives Freeplay a fresh backdrop; `null` keeps the previous one. */
    fun offer(frame: Bitmap?) {
        if (frame == null) return
        pending = frame
        remembered = frame
    }

    /** Takes the pending frame, falling back to the last menu render. */
    fun consume(): Bitmap? {
        val frame = pending ?: remembered
        pending = null
        return frame
    }

    /** Drops a frame nobody picked up (e.g. the switch failed). */
    fun clear() {
        pending = null
        remembered = null
    }
}
