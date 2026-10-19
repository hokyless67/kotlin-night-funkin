package nightfunkin.play

import korlibs.image.color.Colors
import korlibs.korge.view.Container
import korlibs.korge.view.Image
import korlibs.korge.view.SolidRect
import korlibs.korge.view.addUpdater
import korlibs.korge.view.image
import korlibs.korge.view.solidRect
import korlibs.math.geom.Anchor2D
import korlibs.math.geom.Size
import nightfunkin.core.BitmapFontDef
import nightfunkin.core.BitmapText
import nightfunkin.core.Constants
import nightfunkin.core.KnfFiles
import nightfunkin.core.NoteStyle
import nightfunkin.core.setPixelArt
import kotlin.math.abs

/**
 * The in-game HUD.
 *
 * Ported from FNF's `PlayState.initHealthBar` and friends. FNF's gameplay HUD
 * is deliberately small - it has exactly one text element:
 *
 * ```haxe
 * scoreText = new FlxBitmapText(0, 0, '', FlxBitmapFont.fromAngelCode(Paths.font("vcr-bmp.png"), Paths.font("vcr-bmp.fnt")));
 * ...
 * scoreText.x = healthBarBG.x + healthBarBG.width - 190;
 * scoreText.y = healthBarBG.y + 30;
 * scoreText.alignment = RIGHT;
 * scoreText.borderStyle = OUTLINE;
 * scoreText.borderColor = FlxColor.BLACK;
 * scoreText.letterSpacing = -1;
 * ```
 *
 * plus the health bar with its two faces and the judgement / combo popups.
 */
class Hud(
    parent: Container,
    private val noteStyle: NoteStyle,
    downscroll: Boolean = false,
) : Container() {

    companion object {
        /** FNF's `healthBar.png` is 601x19; the fill sits 4px inside it. */
        const val BAR_WIDTH = 601.0
        const val BAR_HEIGHT = 19.0
        const val BAR_INSET = 4.0

        /** FNF's `scoreText.x = healthBarBG.x + healthBarBG.width - 190`. */
        const val SCORE_OFFSET_X = 190.0
        const val SCORE_OFFSET_Y = 30.0
    }

    private val barX = (Constants.GAME_WIDTH - BAR_WIDTH) / 2.0

    /**
     * FNF's `healthBarYPos`: the bar hugs the same edge the notes scroll away
     * from, so upscroll puts it near the bottom and downscroll near the top.
     */
    private val barY =
        if (downscroll) Constants.GAME_HEIGHT * 0.1
        else Constants.GAME_HEIGHT * 0.9

    private val barBack = solidRect(BAR_WIDTH - BAR_INSET * 2, BAR_HEIGHT - BAR_INSET * 2, Colors["#ff0000"])
    private val barFill = solidRect(0.0, BAR_HEIGHT - BAR_INSET * 2, Colors["#66ff33"])

    private var playerIcon: HealthIcon? = null
    private var opponentIcon: HealthIcon? = null

    /** FNF's `scoreText`, drawn with the VCR bitmap font. */
    private val scoreText: BitmapText? = loadVcrFont()?.let { BitmapText(it.first, it.second) }

    /** FNF's `PopUpStuff`: judgement sprites and the combo digits. */
    val popUps = PopUpStuff(noteStyle)

    private var healthTarget = Constants.HEALTH_STARTING
    var health: Float = Constants.HEALTH_STARTING
        private set
    private var healthLerp = Constants.HEALTH_STARTING

    init {
        // FNF's bar background is the `healthBar` texture.
        KnfFiles.resolve("healthBar", ".png")?.let { path ->
            KnfFiles.bitmapOrNull(path)?.let { bmp ->
                val bg = image(bmp)
                bg.x = barX
                bg.y = barY
                addChild(bg)
            }
        }

        barBack.x = barX + BAR_INSET
        barBack.y = barY + BAR_INSET
        addChild(barBack)

        barFill.x = barX + BAR_INSET
        barFill.y = barY + BAR_INSET
        addChild(barFill)

        scoreText?.let { text ->
            text.letterSpacing = -1
            text.outline = true
            text.x = barX + BAR_WIDTH - SCORE_OFFSET_X
            text.y = barY + SCORE_OFFSET_Y
            addChild(text)
        }

        addChild(popUps)
        parent.addChild(this)
    }

    /**
     * FNF's `FlxBitmapFont.fromAngelCode(Paths.font("vcr-bmp.png"), Paths.font("vcr-bmp.fnt"))`.
     */
    private fun loadVcrFont(): Pair<BitmapFontDef, korlibs.image.bitmap.Bitmap>? {
        val fntPath = KnfFiles.resolve("vcr-bmp", ".fnt") ?: KnfFiles.resolve("fonts/vcr-bmp", ".fnt")
        val pngPath = KnfFiles.resolve("vcr-bmp", ".png") ?: KnfFiles.resolve("fonts/vcr-bmp", ".png")
        if (fntPath == null || pngPath == null) {
            println("KNF: VCR font not found (fnt=$fntPath png=$pngPath)")
            return null
        }
        val def = runCatching { BitmapFontDef.parse(KnfFiles.text(fntPath)) }.getOrNull() ?: return null
        val page = KnfFiles.bitmapOrNull(pngPath) ?: return null
        return def to page
    }

    // ======================================================================
    // state
    // ======================================================================

    fun setScore(value: Int) {
        // FNF: `'Score: ${FlxStringUtil.formatMoney(songScore, false, true)}'`
        scoreText?.text = "Score: " + groupDigits(value)
    }

    /** `FlxStringUtil.formatMoney(value, showDecimals, commaSeparated)`. */
    private fun groupDigits(value: Int): String {
        val s = value.toString()
        val sb = StringBuilder()
        for ((i, c) in s.withIndex()) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
            sb.append(c)
        }
        return sb.toString()
    }

    fun setHealth(value: Float) {
        health = value.coerceIn(Constants.HEALTH_MIN, Constants.HEALTH_MAX)
    }

    fun addHealth(delta: Float) = setHealth(health + delta)

    fun resetHealth() {
        health = Constants.HEALTH_STARTING
        healthLerp = Constants.HEALTH_STARTING
        setScore(0)
    }

    fun isDead(): Boolean = health <= Constants.HEALTH_MIN + 0.0001f

    // ======================================================================
    // health icons
    // ======================================================================

    /**
     * Installs the two faces that ride the bar.
     *
     * FNF's `BaseCharacter.initHealthIcon` flips the *player's* icon once,
     * because "BF is looking the other way", and leaves the opponent's alone.
     */
    fun setHealthIcons(player: Character, opponent: Character) {
        playerIcon?.image?.removeFromParent()
        opponentIcon?.image?.removeFromParent()

        val p = HealthIcon(
            characterId = player.healthIconId,
            playerId = 0,
            isPixel = player.healthIconIsPixel,
            flipX = !player.healthIconFlipX,
        )
        val o = HealthIcon(
            characterId = opponent.healthIconId,
            playerId = 1,
            isPixel = opponent.healthIconIsPixel,
            flipX = opponent.healthIconFlipX,
        )
        playerIcon = p
        opponentIcon = o
        addChild(p.image)
        addChild(o.image)
        updateIconPositions()
    }

    // ======================================================================
    // per-frame
    // ======================================================================

    fun update(dt: Double) {
        // FNF: healthLerp = lerp(healthLerp, health, 0.15)
        healthLerp += (health - healthLerp) * Constants.HEALTH_LERP_RATE

        // `FlxBar` with `FILL_RIGHT_TO_LEFT`: the green fill is anchored to the
        // right edge and the red shows on the left.
        val fillWidth = (BAR_WIDTH - BAR_INSET * 2) *
            (healthLerp / Constants.HEALTH_MAX).coerceIn(0f, 1f)
        barFill.unscaledSize = Size(fillWidth.toDouble(), BAR_HEIGHT - BAR_INSET * 2)
        barFill.x = barX + BAR_WIDTH - BAR_INSET - fillWidth

        playerIcon?.update(dt)
        opponentIcon?.update(dt)
        updateIconPositions()
    }

    /**
     * FNF's `HealthIcon.updatePosition`.
     *
     * Both faces straddle the fill's leading edge: the player's sits 26px to the
     * right of it and the opponent's 26px to the left.
     *
     * FNF writes the positions as the sprite's *left* edge, while KorGE anchors
     * the image at its centre, so the half-width is added back here.
     */
    private fun updateIconPositions() {
        val value = healthLerp
        val fillWidth = (BAR_WIDTH - BAR_INSET * 2) *
            (value / Constants.HEALTH_MAX).coerceIn(0f, 1f)
        // `FlxBar` with `FILL_RIGHT_TO_LEFT`: the green fill is anchored to the
        // right edge, so its leading edge is what moves.
        val boundary = barX + BAR_INSET + (BAR_WIDTH - BAR_INSET * 2) - fillWidth

        playerIcon?.let { icon ->
            // `View.width` is the *unscaled* size, and the player's icon is
            // mirrored, so `scaledWidth` is negative - the sign is dropped here.
            val w = abs(icon.image.scaledWidth)
            icon.image.x = boundary - HealthIcon.POSITION_OFFSET + w / 2.0
            icon.image.y = barY + BAR_INSET
            icon.updateFace(value)
        }
        opponentIcon?.let { icon ->
            val w = abs(icon.image.scaledWidth)
            icon.image.x = boundary - (w - HealthIcon.POSITION_OFFSET) + w / 2.0
            icon.image.y = barY + BAR_INSET
            icon.updateFace(Constants.HEALTH_MAX - value)
        }
    }

    /** Flashes the icons; FNF does this on every beat. */
    fun bopIcons(stepLengthMs: Double) {
        playerIcon?.bop(stepLengthMs)
        opponentIcon?.bop(stepLengthMs)
    }

    // ======================================================================
    // popups
    // ======================================================================

    /** FNF's `PopUpStuff.displayRating`. */
    fun showJudgement(j: Judgement) {
        if (j == Judgement.MISS) return
        popUps.displayRating(j.label)
    }

    /** FNF's `PopUpStuff.displayCombo`, which only shows from a combo of ten. */
    fun showCombo(combo: Int) {
        if (combo >= 10) popUps.displayCombo(combo)
    }

    /**
     * FNF breaks the combo counter when the combo ends:
     * `if (Highscore.tallies.combo >= 10) comboPopUps.displayCombo(0);`
     */
    fun showComboBreak() {
        popUps.displayCombo(0)
    }

    /**
     * FNF's `Countdown.showCountdownGraphic`: the note style's graphic for the
     * step, centred on the screen, fading out over one beat.
     */
    fun showCountdown(step: String, beatLengthMs: Double) {
        val asset = noteStyle.countdown(step) ?: return
        val img = Image(asset.slice)
        img.setPixelArt(asset.isPixel)
        img.scaleX = asset.scale.toDouble()
        img.scaleY = asset.scale.toDouble()
        img.anchor = Anchor2D.CENTER
        img.x = Constants.GAME_WIDTH / 2.0 + asset.offsets.getOrElse(0) { 0.0 }
        img.y = Constants.GAME_HEIGHT / 2.0 + asset.offsets.getOrElse(1) { 0.0 }
        addChild(img)
        var elapsed = 0.0
        img.addUpdater { dt ->
            val s = dt.inWholeNanoseconds / 1_000_000_000.0
            elapsed += s
            // FNF fades it out over one beat with a cube-in-out ease.
            val t = (elapsed / (beatLengthMs / 1000.0)).coerceIn(0.0, 1.0)
            img.alpha = 1.0 - t * t * (3.0 - 2.0 * t)
            if (t >= 1.0) img.removeFromParent()
        }
    }
}
