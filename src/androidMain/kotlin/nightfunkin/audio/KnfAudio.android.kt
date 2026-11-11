package nightfunkin.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import korlibs.audio.sound.AndroidNativeSoundProvider
import korlibs.audio.sound.AudioPlatformOutput
import korlibs.audio.sound.AudioPlatformOutputGen
import korlibs.audio.sound.NativeSoundProvider
import nightfunkin.platform.KnfEnv

/**
 * Device blocks queued per output.
 *
 * `AudioTrack.MODE_STREAM` plays what has already been written, so the queue
 * depth is the amount of scheduling jitter the track survives. Four blocks is
 * ~186 ms - enough for the stalls that low FPS and GC pauses produce - while a
 * single block (the korlibs default) underruns on any late wake-up. The SFX
 * mixer stays at one block because a click must not trail the finger.
 */
private const val MUSIC_BLOCKS = 4
private const val SFX_BLOCKS = 1

/** `AndroidNativeSoundProvider`'s device, with a bigger queue and a blocking write. */
private class TrackSink(channels: Int, rate: Int, blocks: Int) : KnfAudioSink {
    private val track: AudioTrack

    init {
        val channelMask =
            if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val bufferBytes = KNF_AUDIO_BLOCK_FRAMES * channels * Short.SIZE_BYTES * blocks
        @Suppress("DEPRECATION")
        track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            rate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
            AudioTrack.MODE_STREAM,
        )
        check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack is not initialised" }
    }

    override fun write(samples: ShortArray, count: Int) {
        val written = track.write(samples, 0, count)
        if (written < 0) error("AudioTrack.write returned $written")
    }

    override fun start() {
        // The pump owns this thread for the lifetime of the output, so the
        // priority sticks; normal-priority threads lose the wake-up race with
        // rendering exactly when the queue is about to run dry.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        track.play()
    }

    override fun pause(paused: Boolean) {
        if (paused) track.pause() else track.play()
    }

    override fun close() {
        runCatching {
            track.pause()
            track.flush()
            track.stop()
        }
        runCatching { track.release() }
    }
}

private class KnfAndroidSoundProvider(private val blocks: Int) : NativeSoundProvider() {
    @OptIn(ExperimentalStdlibApi::class)
    override fun createNewPlatformAudioOutput(
        channels: Int,
        frequency: Int,
        gen: AudioPlatformOutputGen,
    ): AudioPlatformOutput {
        val sink = runCatching { TrackSink(channels, frequency, blocks) }.getOrNull()
            ?: return AndroidNativeSoundProvider.createNewPlatformAudioOutput(channels, frequency, gen)
        return knfBlockPumpOutput(this, channels, frequency, gen, sink, "KNF-Audio")
    }
}

/** `KNF_AUDIO_LEGACY=1` falls back to korlibs' own outputs for comparison. */
private val useKnfOutput = KnfEnv["KNF_AUDIO_LEGACY"] != "1"

internal actual val knfMusicSoundProvider: NativeSoundProvider? =
    if (useKnfOutput) KnfAndroidSoundProvider(MUSIC_BLOCKS) else null

internal actual val knfSfxSoundProvider: NativeSoundProvider? =
    if (useKnfOutput) KnfAndroidSoundProvider(SFX_BLOCKS) else null

internal actual val knfMusicLeadBlocks: Int = MUSIC_BLOCKS
