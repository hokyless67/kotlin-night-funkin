package nightfunkin.core

import korlibs.korge.view.RectBase
import korlibs.korge.view.Text
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
 * `antialiasing = false` is only a texture-sampling mode in FNF. KorGE's
 * `Image`/`Text` expose exactly that as `smoothing`, so use it directly:
 * a `filter` here would wrap every pixel sprite in a render-to-texture pass.
 * On a pixel week that is dozens of full off-screen passes per frame (notes,
 * holds, receptors, judgements, combo digits, props, characters), which is
 * what made those levels crawl on mobile.
 *
 * Views without a sampling flag (custom containers) keep the nearest filter
 * as a fallback.
 */
fun View.setPixelArt(isPixel: Boolean) {
    when (this) {
        is RectBase -> {
            smoothing = !isPixel
            // Clear a Nearest filter left by the old implementation; never
            // touch a real shader filter (rim lights, stage effects).
            if (!isPixel && filter is IdentityFilter) filter = null
        }

        is Text -> smoothing = !isPixel

        else -> filter = if (isPixel) IdentityFilter.Nearest else null
    }
}
