package nightfunkin.play

import nightfunkin.core.HapticUtil

/**
 * Port of FNF 0.8.6's `funkin.play.notes.NoteVibrationsHandler`.
 *
 * FNF keeps a per-lane [NoteStatus] on the player's strumline and turns it
 * into haptics in two places: `PlayState.update` calls [tryNoteVibration] once
 * per frame (a press that landed on a note buzzes), and every step hit runs
 * [tryHoldNoteVibration] so a held tail keeps a light rumble going.
 *
 * Amplitudes stack: each confirming lane adds `MAX / 4`, each held lane
 * `MAX / 10` (`MAX / 4` on the frame the hold ends), and the sum is multiplied
 * by 2.5 the way the source does before it is clamped to `MAX`.
 */
class NoteVibrations {

    /** `NoteStatus` from the Haxe handler. */
    enum class Status { IDLE, PRESSED, CONFIRM, HOLD_CONFIRM }

    private val statuses = Array(4) { Status.IDLE }

    fun press(lane: Int) {
        statuses[lane] = Status.PRESSED
    }

    /** `Strumline.hitNote`: the lane struck a note. */
    fun confirm(lane: Int) {
        statuses[lane] = Status.CONFIRM
    }

    /** `Strumline.releaseNote`: the key went up again. */
    fun release(lane: Int) {
        statuses[lane] = Status.IDLE
    }

    /** `Strumline.holdConfirm`: the tail is being held. */
    fun hold(lane: Int) {
        statuses[lane] = Status.HOLD_CONFIRM
    }

    fun idle(lane: Int) {
        statuses[lane] = Status.IDLE
    }

    /** `NoteVibrationsHandler.tryNoteVibration`. */
    fun tryNoteVibration() {
        if (!HapticUtil.hapticsAvailable) return
        var stacking = 0.0
        for (status in statuses) {
            if (status != Status.CONFIRM) continue
            stacking += HapticUtil.MAX_VIBRATION_AMPLITUDE / 4.0
        }
        if (stacking > HapticUtil.MAX_VIBRATION_AMPLITUDE) {
            stacking = HapticUtil.MAX_VIBRATION_AMPLITUDE
        }
        if (stacking > 0.0) HapticUtil.vibrate(0.0, 0.01, stacking * 2.5, 1.0)
    }

    /**
     * `NoteVibrationsHandler.tryHoldNoteVibration`.
     *
     * @param holdNoteEnded the Haxe `holdNoteEnded` flag: the tail released, so
     * each lane contributes `MAX / 4` instead of `MAX / 10`.
     */
    fun tryHoldNoteVibration(holdNoteEnded: Boolean = false) {
        if (!HapticUtil.hapticsAvailable) return
        val divider = if (holdNoteEnded) 4.0 else 10.0
        var stacking = 0.0
        for (status in statuses) {
            if (status != Status.HOLD_CONFIRM) continue
            stacking += HapticUtil.MAX_VIBRATION_AMPLITUDE / divider
        }
        if (stacking > HapticUtil.MAX_VIBRATION_AMPLITUDE) {
            stacking = HapticUtil.MAX_VIBRATION_AMPLITUDE
        }
        if (stacking > 0.0) HapticUtil.vibrate(0.0, 0.01, stacking * 2.5, 1.0)
        // `NoteVibrationsHandler` ends with
        // `if (... && holdNoteEnded) currentNoteStatus == NoteStatus.pressed;` -
        // a comparison, not an assignment, so the statuses stay on holdConfirm.
        // The strumline's own `playPress`/`playStatic` call right after the
        // final vibration is what clears them, here as in the source.
    }
}
