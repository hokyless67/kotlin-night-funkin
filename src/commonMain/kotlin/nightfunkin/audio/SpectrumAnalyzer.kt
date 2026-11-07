package nightfunkin.audio

import korlibs.audio.sound.AudioData
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin

/**
 * The seven-band analyzer used by A-Bot's screen.
 *
 * `ABotVis` uses `funkin.vis`'s `SpectralAnalyzer`: a Hann-windowed FFT with
 * log-spaced bands and a -65..-25 dB display range. This is the same shape,
 * narrowed to the band count A-Bot actually draws. It is evaluated on demand
 * from the decoded instrumental, so seeking and retries need no extra state.
 */
class SpectrumAnalyzer(
    private val data: AudioData,
    private val barCount: Int = 7,
) {
    private val fftSize = 1024
    private val sampleRate = data.rate.coerceAtLeast(1)
    private val channels = data.channels.coerceAtLeast(1)
    private val window = DoubleArray(fftSize) { index ->
        0.5 - 0.5 * cos(2.0 * PI * index / (fftSize - 1).coerceAtLeast(1))
    }
    private val real = DoubleArray(fftSize)
    private val imag = DoubleArray(fftSize)
    private val smoothed = DoubleArray(barCount)
    private val result = FloatArray(barCount)
    private val binLo = IntArray(barCount)
    private val binHi = IntArray(barCount)

    init {
        // `SpectralAnalyzer.minFreq` starts at 10 Hz, but 1024-point bins at
        // 44.1 kHz are ~43 Hz wide. Starting at 30 Hz keeps the first bar from
        // being permanently empty while staying in the audible bass range.
        val minFreq = 30.0
        val maxFreq = 22000.0
        val logStep = (ln(maxFreq) - ln(minFreq)) / barCount
        for (index in 0 until barCount) {
            val low = minFreq * exp(logStep * index)
            val high = minFreq * exp(logStep * (index + 1))
            binLo[index] = floor(low * fftSize / sampleRate).toInt().coerceAtLeast(1)
            binHi[index] = ceil(high * fftSize / sampleRate).toInt()
                .coerceAtLeast(binLo[index])
                .coerceAtMost(fftSize / 2)
        }
    }

    /** Returns the smoothed 0..1 level of every band at [positionMs]. */
    fun levels(positionMs: Double): FloatArray {
        val total = data.totalSamples
        if (total <= 0) {
            result.fill(0f)
            return result
        }

        val start = (positionMs.coerceAtLeast(0.0) / 1000.0 * sampleRate).toInt()
        for (index in 0 until fftSize) {
            val sampleIndex = start + index
            val value = if (sampleIndex in 0 until total) {
                var sum = 0.0
                for (channel in 0 until channels) {
                    sum += data[channel, sampleIndex].float
                }
                sum / channels
            } else {
                0.0
            }
            real[index] = value * window[index]
            imag[index] = 0.0
        }

        fft(real, imag)

        for (band in 0 until barCount) {
            var peak = 1e-9
            for (bin in binLo[band]..binHi[band]) {
                val amplitude = hypot(real[bin], imag[bin]) / (fftSize / 2.0)
                if (amplitude > peak) peak = amplitude
            }
            val db = 20.0 * log10(peak)
            val level = ((db + 65.0) / 40.0).coerceIn(0.0, 1.0)
            // `SpectralAnalyzer` defaults to a 0.1 smoothing constant, which
            // ABotVis keeps. Mostly-current values make the bars dance.
            smoothed[band] = 0.1 * smoothed[band] + 0.9 * level
            result[band] = smoothed[band].toFloat()
        }
        return result
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imag[i]
                imag[i] = imag[j]
                imag[j] = ti
            }
        }

        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val stepReal = cos(angle)
            val stepImag = sin(angle)
            var offset = 0
            while (offset < n) {
                var twiddleReal = 1.0
                var twiddleImag = 0.0
                val half = length / 2
                for (k in 0 until half) {
                    val even = offset + k
                    val odd = even + half
                    val oddReal = real[odd] * twiddleReal - imag[odd] * twiddleImag
                    val oddImag = real[odd] * twiddleImag + imag[odd] * twiddleReal
                    real[odd] = real[even] - oddReal
                    imag[odd] = imag[even] - oddImag
                    real[even] += oddReal
                    imag[even] += oddImag
                    val nextReal = twiddleReal * stepReal - twiddleImag * stepImag
                    twiddleImag = twiddleReal * stepImag + twiddleImag * stepReal
                    twiddleReal = nextReal
                }
                offset += length
            }
            length = length shl 1
        }
    }
}
