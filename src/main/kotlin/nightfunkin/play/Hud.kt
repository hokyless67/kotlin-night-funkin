package nightfunkin.play

import korlibs.image.color.Colors
import korlibs.image.color.RGBA
import korlibs.korge.view.Container
import korlibs.korge.view.FixedSizeContainer
import korlibs.korge.view.SolidRect
import korlibs.korge.view.Text
import korlibs.korge.view.addUpdater
import korlibs.korge.view.solidRect
import korlibs.korge.view.text
import korlibs.math.geom.Size
import nightfunkin.core.Constants
import kotlin.math.abs
import kotlin.math.sin

/**
 * Health bar, score, combo and judgement popups.
 *
 * Lives on the HUD layer (never zooms with the stage) and mirrors FNF's
 * `PlayState.updateHealthBar` — the bar lerps toward the real value at 0.15.
 */
class Hud(parent: Container, downscroll: Boolean = false) : Container() {

    private val barWidth = 592.0
    private val barHeight = 19.0
    private val barX = (Constants.GAME_WIDTH - barWidth) / 2.0

    /**
     * `PlayState.createHealthBar`: the bar hugs the same edge the notes scroll
     * away from, so upscroll puts it near the bottom and downscroll near the top.
     */
    private val barY =
        if (downscroll) Constants.GAME_HEIGHT * 0.1
        else Constants.GAME_HEIGHT * 0.9 - barHeight

    private val barFrame = solidRect(barWidth + 8, barHeight + 8, Colors["#0b0b12"])
    private val barBack = solidRect(barWidth, barHeight, Colors["#5a1c00"])
    private val barFill = solidRect(0.0, barHeight, Colors["#66ff33"])

    private val playerIcon = solidRect(46.0, 46.0, Colors["#3aa0ff"])
    private val opponentIcon = solidRect(46.0, 46.0, Colors["#ff4444"])

    private val scoreText: Text
    private val comboText: Text
    private val songNameText: Text
    private val songInfoText: Text
    private val rankText: Text
    private val popupLayer = Container()

    private var healthTarget = Constants.HEALTH_STARTING
    var health: Float = Constants.HEALTH_STARTING
        private set
    private var healthLerp = Constants.HEALTH_STARTING
    private var iconBopTimer = 0.0

    init {
        barFrame.x = barX - 4
        barFrame.y = barY - 4
        addChild(barFrame)

        barBack.x = barX
        barBack.y = barY
        addChild(barBack)

        barFill.x = barX
        barFill.y = barY
        addChild(barFill)

        addChild(playerIcon)
        addChild(opponentIcon)

        // With upscroll the receptors sit at the very top, so the score and combo
        // stack in the top-left corner the way FNF does.
        scoreText = text("Score: 0", textSize = 30.0, color = Colors.WHITE).apply {
            x = 24.0
            y = 34.0
        }
        addChild(scoreText)

        comboText = text("", textSize = 30.0, color = Colors.WHITE).apply {
            x = 30.0
            y = 72.0
            alpha = 0.0
        }
        addChild(comboText)

        songNameText = text("", textSize = 18.0, color = Colors["#c9c9d4"]).apply {
            x = 24.0
            y = Constants.GAME_HEIGHT - 52.0
        }
        addChild(songNameText)

        songInfoText = text("", textSize = 15.0, color = Colors["#8b8b99"]).apply {
            x = 24.0
            y = Constants.GAME_HEIGHT - 30.0
        }
        addChild(songInfoText)

        rankText = text("", textSize = 26.0, color = Colors.WHITE).apply {
            x = Constants.GAME_WIDTH - 230.0
            y = barY + 30.0
        }
        addChild(rankText)

        addChild(popupLayer)
        parent.addChild(this)
    }

    // ======================================================================
    // state
    // ======================================================================

    fun setSongLabels(songName: String, artist: String, difficulty: String) {
        songNameText.text = songName
        songInfoText.text = "$artist  •  $difficulty"
    }

    fun setScore(value: Int) {
        scoreText.text = "Score: $value"
    }

    fun setCombo(combo: Int) {
        if (combo < 2) {
            comboText.alpha = 0.0
            return
        }
        comboText.text = "Combo: $combo"
        comboText.alpha = 1.0
    }

    fun setHealth(value: Float) {
        health = value.coerceIn(Constants.HEALTH_MIN, Constants.HEALTH_MAX)
    }

    fun addHealth(delta: Float) = setHealth(health + delta)

    fun resetHealth() {
        health = Constants.HEALTH_STARTING
        healthLerp = Constants.HEALTH_STARTING
        setScore(0)
        setCombo(0)
        rankText.text = ""
    }

    fun isDead(): Boolean = health <= Constants.HEALTH_MIN + 0.0001f

    // ======================================================================
    // per-frame
    // ======================================================================

    fun update(dt: Double) {
        // FNF: healthLerp = lerp(healthLerp, health, 0.15)
        healthLerp += (health - healthLerp) * Constants.HEALTH_LERP_RATE

        val t = (healthLerp / Constants.HEALTH_MAX).coerceIn(0.0f, 1.0f).toDouble()
        barFill.unscaledSize = Size(barWidth * t, barHeight)

        if (iconBopTimer > 0.0) iconBopTimer -= dt
        val bop = 1.0 + 0.08 * abs(sin(iconBopTimer * 18.0))

        playerIcon.scaleX = bop
        playerIcon.scaleY = bop
        playerIcon.x = barX + barWidth * t - 54.0
        playerIcon.y = barY - 15.0

        opponentIcon.scaleX = bop
        opponentIcon.scaleY = bop
        opponentIcon.x = barX + barWidth * t - 2.0
        opponentIcon.y = barY - 15.0
    }

    /** Flashes the icons; FNF does this on every healthy hit. */
    fun bopIcons() {
        iconBopTimer = 0.18
    }

    // ======================================================================
    // popups
    // ======================================================================

    fun showJudgement(j: Judgement) {
        val t = text(j.label, textSize = 38.0, color = RGBA(j.color))
        t.x = Constants.GAME_WIDTH / 2.0 - t.width / 2.0
        t.y = 92.0
        popupLayer.addChild(t)
        t.addUpdater { dt ->
            val s = dt.toSeconds()
            t.y += 24.0 * s
            t.alpha -= (1.5 * s).toFloat()
            if (t.alpha <= 0f) t.removeFromParent()
        }
    }

    fun showCountdown(label: String) {
        val t = text(label, textSize = 104.0, color = Colors.WHITE)
        t.x = Constants.GAME_WIDTH / 2.0 - t.width / 2.0
        t.y = Constants.GAME_HEIGHT / 2.0 - t.height / 2.0
        popupLayer.addChild(t)
        t.addUpdater { dt ->
            t.alpha -= (0.7 * dt.toSeconds()).toFloat()
            if (t.alpha <= 0f) t.removeFromParent()
        }
    }

    fun setRank(rank: String) {
        rankText.text = rank
    }

    fun setWatermark(left: String, right: String) {
        songInfoText.text = "$left   |   $right"
    }
}

private fun kotlin.time.Duration.toSeconds(): Double = inWholeNanoseconds / 1_000_000_000.0

private val SolidRect.unusedSizeHelper: Size get() = unscaledSize
