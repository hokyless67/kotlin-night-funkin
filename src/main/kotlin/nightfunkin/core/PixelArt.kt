package nightfunkin.core

import korlibs.korge.view.View
import korlibs.korge.view.filter.IdentityFilter
import korlibs.korge.view.filter.filter

/**
 * Pixel-art filtering.
 *
 * FNF turns antialiasing off per sprite from the `isPixel` flag - on the
 * character, on the stage prop, and on every judgement and combo digit in the
 * note style:
 *
 * ```haxe
 * // SparrowCharacter.loadSpritesheet
 * if (_data.isPixel) { this.isPixel = true; this.antialiasing = false; }
 * // NoteStyle.buildJudgementSprite
 * var isPixel = isJudgementSpritePixel(rating);
 * result.antialiasing = !isPixel;
 * result.pixelPerfectRender = isPixel;
 * result.pixelPerfectPosition = isPixel;
 * ```
 *
 * KorGE 6 has no `antialiasing` flag; sampling is chosen by the view's `filter`,
 * where [IdentityFilter.Nearest] is the non-interpolating path - the same pair
 * `Scene` uses for its own `sceneSmoothing`.
 */
fun View.setPixelArt(isPixel: Boolean) {
    filter = if (isPixel) IdentityFilter.Nearest else null
}