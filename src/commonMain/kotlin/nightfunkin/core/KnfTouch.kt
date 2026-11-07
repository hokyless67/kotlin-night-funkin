package nightfunkin.core

import nightfunkin.platform.KnfEnv
import nightfunkin.platform.KnfPlatform

/**
 * Global bus between the [nightfunkin.ui.TouchControls] overlay (which writes)
 * and every [KnfInput] instance (which reads and edge-detects once per frame).
 *
 * Lane state is *held* (a finger on a zone keeps the lane down), action flags
 * are held for one frame per tap/button press.
 */
object KnfTouch {
    /**
     * Touch controls exist on mobile targets, on desktop in mobile mode
     * (`KNF_MOBILE=1`), or with `KNF_TOUCH=1` (touch overlay only, desktop
     * everything else).
     */
    val enabled: Boolean get() = KnfPlatform.isMobile || KnfEnv["KNF_TOUCH"] == "1"

    /** Lane held state; menu drags reuse these lanes as UI navigation. */
    val lanes = BooleanArray(4)

    /** CONFIRM, held for one frame per tap. */
    var acceptDown: Boolean = false

    /** BACK, held for one frame per two-finger tap. */
    var backDown: Boolean = false

    /** PAUSE, held for one frame per pause-button press. */
    var pauseDown: Boolean = false

    fun clearFrameActions() {
        acceptDown = false
        backDown = false
        pauseDown = false
    }

    fun reset() {
        lanes.fill(false)
        clearFrameActions()
    }
}
