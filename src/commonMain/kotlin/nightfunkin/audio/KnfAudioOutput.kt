package nightfunkin.audio

import korlibs.audio.sound.AudioPlatformOutput
import korlibs.audio.sound.AudioPlatformOutputGen
import korlibs.audio.sound.AudioSamplesInterleaved
import korlibs.audio.sound.NativeSoundProvider
import korlibs.audio.sound.SoundListenerProps
import korlibs.concurrent.thread.FixedPoolNativeThreadDispatcher
import korlibs.concurrent.thread.NativeThreadPriority
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Frames korlibs hands to a platform output per block (2048 = 46.4 ms at 44.1 kHz). */
internal const val KNF_AUDIO_BLOCK_FRAMES = 2048

/**
 * A blocking 16-bit PCM device.
 *
 * The pump writes one [KNF_AUDIO_BLOCK_FRAMES] block at a time; `write` only
 * returns once the samples fit into the device queue, so the device itself
 * paces the producer. A deeper queue therefore buys scheduling slack without
 * spinning, and every write is one contiguous block (no per-sample calls).
 */
internal interface KnfAudioSink {
    /** Writes [count] interleaved 16-bit samples, blocking while the queue is full. */
    fun write(samples: ShortArray, count: Int)

    /** Starts playback. Called on the pump thread, before the first [write]. */
    fun start()

    fun pause(paused: Boolean)

    fun close()
}

/**
 * A korlibs platform output whose producer runs on its own audio-priority
 * thread and whose device queue is deeper than the backend's default.
 *
 * KorGE 6 renders one 2048-frame block per loop iteration on a shared 16-thread
 * coroutine pool with `delay(1L)` between blocks, and on Android the
 * `AudioTrack` it fills is exactly one block deep. Whenever rendering keeps the
 * CPU busy - low FPS, ragged frame times - the pool resumes the producer a few
 * milliseconds late, the track runs dry, and the music audibly "tears": short
 * silences at every block seam. Here the block is written to a device with
 * several blocks of headroom from a dedicated thread, so a late wake-up is
 * absorbed by the queue instead of cutting the sound.
 *
 * The block still ends with a check for cancellation: [AudioPlatformOutput.stop]
 * only cancels the job, and this loop never suspends while it is playing.
 */
@OptIn(ExperimentalStdlibApi::class)
internal fun knfBlockPumpOutput(
    listener: SoundListenerProps,
    channels: Int,
    frequency: Int,
    gen: AudioPlatformOutputGen,
    sink: KnfAudioSink,
    threadName: String,
): AudioPlatformOutput {
    // A one-thread pool, not `NativeThreadDispatcher`: that class's
    // `isDispatchNeeded` is inverted (`current == thread`), so `launch` on it
    // runs the block *inline on the caller* - which is the render thread. The
    // pump never returns, so the game froze the moment an output was opened
    // (title screen: black screen, no music). `FixedPoolNativeThreadDispatcher`
    // has the correct check and forwards `delay()` scheduling as well.
    val dispatcher = FixedPoolNativeThreadDispatcher(
        1,
        threadName,
        NativeThreadPriority.HIGHEST,
        isDaemon = true,
    )
    return AudioPlatformOutput(listener, channels, frequency, gen, dispatcher) {
        val block = AudioSamplesInterleaved(channels, KNF_AUDIO_BLOCK_FRAMES)
        var started = false
        var lastPaused: Boolean? = null
        try {
            while (running) {
                if (!currentCoroutineContext().isActive) break
                if (lastPaused != paused) {
                    lastPaused = paused
                    sink.pause(paused)
                }
                if (paused) {
                    delay(10L)
                } else {
                    genSafe(block)
                    if (!started) {
                        started = true
                        sink.start()
                    }
                    sink.write(block.data.asShortArray(), block.data.size)
                }
            }
        } catch (e: Throwable) {
            if (running) println("KNF: audio '$threadName' failed: ${e.message}")
        } finally {
            runCatching { sink.close() }
            // A fresh dispatcher is created per output (`play()` rebuilds one
            // for every pause/resync), so its thread has to go with it.
            dispatcher.close()
        }
    }
}

/**
 * Fully decoded tracks (gameplay stems, cutscene audio) get this output on the
 * platforms that provide one; `null` keeps korlibs' backend.
 */
internal expect val knfMusicSoundProvider: NativeSoundProvider?

/** The one shared SFX mixer output. Latency matters more than headroom here. */
internal expect val knfSfxSoundProvider: NativeSoundProvider?

/** Device blocks the music output queues ahead of the speaker; 0 without a custom backend. */
internal expect val knfMusicLeadBlocks: Int
