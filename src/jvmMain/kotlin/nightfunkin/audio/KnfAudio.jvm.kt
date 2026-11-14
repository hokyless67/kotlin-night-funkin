package nightfunkin.audio

import korlibs.audio.sound.AudioPlatformOutput
import korlibs.audio.sound.AudioPlatformOutputGen
import korlibs.audio.sound.NativeSoundProvider
import korlibs.audio.sound.backend.FFIJVMWaveOutNativeSoundProvider
import nightfunkin.platform.KnfEnv
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * Device blocks queued per output.
 *
 * `SourceDataLine.write` blocks until the mixer's queue has room, so the queue
 * depth is the jitter the playback path survives. korlibs' Windows backend
 * keeps eight 512-frame WinMM headers (~93 ms) topped up from a shared coroutine
 * pool; four full blocks here give the same order of headroom from a dedicated
 * audio thread, so a frame hitch or a GC pause cannot cut the stems.
 */
private const val MUSIC_BLOCKS = 4
private const val SFX_BLOCKS = 1

/** A `SourceDataLine` with a deep queue and one blocking write per block. */
private class DataLineSink(channels: Int, rate: Int, blocks: Int) : KnfAudioSink {
    private val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
    private val line: SourceDataLine = AudioSystem.getSourceDataLine(format).also {
        it.open(format, KNF_AUDIO_BLOCK_FRAMES * channels * Short.SIZE_BYTES * blocks)
    }
    private val bytes = ByteArray(KNF_AUDIO_BLOCK_FRAMES * channels * Short.SIZE_BYTES)

    override fun write(samples: ShortArray, count: Int) {
        var source = 0
        var target = 0
        while (source < count) {
            val sample = samples[source++].toInt()
            bytes[target++] = sample.toByte()
            bytes[target++] = (sample shr 8).toByte()
        }
        // `SourceDataLine.write` only promises to write *some* bytes; anything
        // it does not take would be lost audio (JavaSound returns short counts
        // when the mixer's queue is busy or the line was just started).
        var offset = 0
        var stalls = 0
        while (offset < target) {
            val written = line.write(bytes, offset, target - offset)
            if (written <= 0) {
                if (++stalls > 8) error("SourceDataLine.write returned $written")
                Thread.yield()
                continue
            }
            stalls = 0
            offset += written
        }
    }

    override fun start() {
        line.start()
    }

    override fun pause(paused: Boolean) {
        if (paused) line.stop() else line.start()
    }

    override fun close() {
        runCatching {
            line.flush()
            line.stop()
            line.close()
        }
    }
}

private class KnfJvmSoundProvider(private val blocks: Int, private val threadName: String) : NativeSoundProvider() {
    @OptIn(ExperimentalStdlibApi::class)
    override fun createNewPlatformAudioOutput(
        channels: Int,
        frequency: Int,
        gen: AudioPlatformOutputGen,
    ): AudioPlatformOutput {
        // Machines without a usable JavaSound mixer keep the old WinMM path.
        // `isLineSupported` answers that without opening the device; the sink
        // itself is opened on the pump thread (`createNewPlatformAudioOutput`
        // runs on the GL thread when a stem starts).
        val format = AudioFormat(frequency.toFloat(), 16, channels, true, false)
        val supported = runCatching {
            AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, format))
        }.getOrDefault(false)
        if (!supported) {
            return FFIJVMWaveOutNativeSoundProvider.createNewPlatformAudioOutput(channels, frequency, gen)
        }
        return knfBlockPumpOutput(
            this,
            channels,
            frequency,
            gen,
            { DataLineSink(channels, frequency, blocks) },
            threadName,
        )
    }
}

/** `KNF_AUDIO_LEGACY=1` falls back to korlibs' own outputs for comparison. */
private val useKnfOutput = KnfEnv["KNF_AUDIO_LEGACY"] != "1"

internal actual val knfMusicSoundProvider: NativeSoundProvider? =
    if (useKnfOutput) KnfJvmSoundProvider(MUSIC_BLOCKS, "KNF-Music") else null

/** Desktop plays streamed tracks through korlibs; see [knfStreamSoundProvider]. */
internal actual val knfStreamSoundProvider: NativeSoundProvider? = null

internal actual val knfSfxSoundProvider: NativeSoundProvider? =
    if (useKnfOutput) KnfJvmSoundProvider(SFX_BLOCKS, "KNF-Sfx") else null

internal actual val knfMusicLeadBlocks: Int = MUSIC_BLOCKS

/**
 * JavaSound has no equivalent warm-device pool: a `SourceDataLine` is bound to
 * its mixer and a fresh line is cheap enough that reusing one is not worth the
 * state it would keep alive.
 */
internal actual fun knfPrewarmMusicOutput(sampleRate: Int) = Unit
