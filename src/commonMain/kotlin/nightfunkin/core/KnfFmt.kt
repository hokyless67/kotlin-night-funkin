package nightfunkin.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

/**
 * Tiny platform-independent number formatting, replacing the JVM-only
 * `"%.Nf".format(...)` calls (which have locale- and target-dependent
 * behaviour across Kotlin/Native).
 */
object KnfFmt {
    /** Formats [value] with exactly [places] decimals, e.g. `decimals(1.5, 2) == "1.50"`. */
    fun decimals(value: Double, places: Int): String {
        val factor = 10.0.pow(places)
        val negative = value < 0.0
        val scaled = round(abs(value) * factor).toLong()
        val whole = scaled / factor.toLong()
        val fraction = scaled % factor.toLong()
        return buildString {
            if (negative && (whole != 0L || fraction != 0L)) append('-')
            append(whole)
            if (places > 0) {
                append('.')
                append(fraction.toString().padStart(places, '0'))
            }
        }
    }

    fun decimals(value: Float, places: Int): String = decimals(value.toDouble(), places)
}

/**
 * Degree/radian conversion helpers, replacing the JVM-only `java.lang.Math`
 * `toDegrees`/`toRadians` in the sprite-rotation code.
 */
object KnfMath {
    fun toRadians(degrees: Double): Double = degrees * PI / 180.0

    fun toDegrees(radians: Double): Double = radians * 180.0 / PI
}
