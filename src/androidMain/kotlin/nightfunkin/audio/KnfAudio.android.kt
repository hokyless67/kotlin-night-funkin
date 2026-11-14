package nightfunkin.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
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

/**
 * Streamed tracks keep korlibs' one-block pacing: their generator only emits a
 * block while its queue holds a whole `bufferTime`, so a deeper device queue
 * turns into silence blocks. See [knfStreamSoundProvider].
 */
private const val STREAM_BLOCKS = 1

/** Everything that has to match for one `AudioTrack` to serve another playback. */
private data class TrackKey(val channels: Int, val rate: Int, val blocks: Int)

private fun createTrack(key: TrackKey): AudioTrack {
    val channelMask =
        if (key.channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
    val bufferBytes = KNF_AUDIO_BLOCK_FRAMES * key.channels * Short.SIZE_BYTES * key.blocks
    @Suppress("DEPRECATION")
    val created = AudioTrack(
        AudioManager.STREAM_MUSIC,
        key.rate,
        channelMask,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferBytes,
        AudioTrack.MODE_STREAM,
    )
    check(created.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack is not initialised" }
    return created
}

/**
 * Devices kept paused and empty between playbacks.
 *
 * `new AudioTrack(...)` is a binder round trip, and MIUI routinely stretches it
 * to several hundred milliseconds. Opening one per playback meant that the
 * Freeplay preview was silent for that long before its first sample - the
 * device was also opened on the GL thread, before the decoder had even started,
 * so both delays added up. A stopped sink now returns its device here and the
 * next playback borrows it, which makes starting a track an ordinary write.
 *
 * `FreeplayState` warms one up while the menu is still being laid out, so even
 * the first preview of a session does not pay for the round trip.
 */
private object TrackPool {
    private const val CAPACITY = 4

    private val lock = Any()
    private val free = ArrayList<Pair<TrackKey, AudioTrack>>()

    fun take(key: TrackKey): AudioTrack? {
        synchronized(lock) {
            val index = free.indexOfFirst { it.first == key }
            if (index >= 0) return free.removeAt(index).second
        }
        return null
    }

    fun give(key: TrackKey, track: AudioTrack): Boolean {
        synchronized(lock) {
            if (free.size >= CAPACITY) return false
            free += key to track
            return true
        }
    }

    fun prewarm(key: TrackKey) {
        synchronized(lock) {
            if (free.any { it.first == key }) return
        }
        // Opening the device is the slow part; never hold the lock for it, and
        // drop the device when another thread warmed the same key meanwhile.
        val track = runCatching { createTrack(key) }.getOrNull() ?: return
        val keep = synchronized(lock) {
            if (free.size >= CAPACITY || free.any { it.first == key }) {
                false
            } else {
                free += key to track
                true
            }
        }
        if (!keep) runCatching { track.release() }
    }
}

/** `AndroidNativeSoundProvider`'s device, with a bigger queue and a blocking write. */
private class TrackSink(
    channels: Int,
    rate: Int,
    blocks: Int,
) : KnfAudioSink {
    private val key = TrackKey(channels, rate, blocks)
    private var track: AudioTrack = TrackPool.take(key) ?: createTrack(key)
    private var started = false

    /** Cleared when the device itself failed, so it is not handed back warm. */
    private var healthy = true

    override fun write(samples: ShortArray, count: Int) {
        // `AudioTrack.flush()` (used when a pooled device is handed back)
        // explicitly documents that a following write may transfer fewer
        // samples than requested, and a device that is being rerouted can do
        // the same. Ignoring the short count silently dropped up to a block of
        // audio - which is exactly what a skipped intro sounds like.
        var offset = 0
        var stalls = 0
        while (offset < count) {
            val written = runCatching { track.write(samples, offset, count - offset) }
                .getOrDefault(Int.MIN_VALUE)
            if (written < 0) {
                // The audio server can invalidate a streaming track at any time
                // (route/device change, focus loss, memory reclaim) and then every
                // write fails with ERROR_DEAD_OBJECT. Letting that kill the pump
                // stopped the song clock; rebuild the device and keep going.
                println("KNF: AudioTrack.write failed ($written), recreating the track")
                recreateTrack()
                if (++stalls > 8) error("AudioTrack.write returned $written")
                continue
            }
            if (written == 0) {
                // A blocking write only returns 0 when the track is paused or
                // was invalidated mid-call. Yield instead of spinning, and give
                // up (rather than hang the pump) if it never makes progress.
                if (++stalls > 8) error("AudioTrack.write made no progress")
                Thread.yield()
                continue
            }
            stalls = 0
            offset += written
        }
    }

    private fun recreateTrack() {
        healthy = false
        runCatching {
            track.pause()
            track.flush()
            track.stop()
        }
        runCatching { track.release() }
        track = TrackPool.take(key) ?: createTrack(key)
        if (started) track.play()
    }

    override fun start() {
        // The pump owns this thread for the lifetime of the output, so the
        // priority sticks; normal-priority threads lose the wake-up race with
        // rendering exactly when the queue is about to run dry.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        started = true
        track.play()
    }

    override fun pause(paused: Boolean) {
        if (paused) track.pause() else track.play()
    }

    override fun close() {
        started = false
        // Pause and drop whatever is still queued before the device goes back
        // to the pool: the next playback must start at its own first sample.
        runCatching { track.pause() }
        runCatching { track.flush() }
        if (!healthy || !TrackPool.give(key, track)) {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }
}

private class KnfAndroidSoundProvider(private val blocks: Int) : NativeSoundProvider() {
    @OptIn(ExperimentalStdlibApi::class)
    override fun createNewPlatformAudioOutput(
        channels: Int,
        frequency: Int,
        gen: AudioPlatformOutputGen,
    ): AudioPlatformOutput {
        // The device is opened on the pump thread, not here: `new AudioTrack`
        // blocks on a binder round trip (100-200 ms on MIUI) and this method is
        // called from the GL thread whenever a stem starts.
        return knfBlockPumpOutput(
            this,
            channels,
            frequency,
            gen,
            { TrackSink(channels, frequency, blocks) },
            "KNF-Audio",
        )
    }
}

/** `KNF_AUDIO_LEGACY=1` falls back to korlibs' own outputs for comparison. */
private val useKnfOutput = KnfEnv["KNF_AUDIO_LEGACY"] != "1"

internal actual val knfMusicSoundProvider: NativeSoundProvider? =
    if (useKnfOutput) KnfAndroidSoundProvider(MUSIC_BLOCKS) else null

internal actual val knfStreamSoundProvider: NativeSoundProvider? =
    if (useKnfOutput) KnfAndroidSoundProvider(STREAM_BLOCKS) else null

internal actual val knfSfxSoundProvider: NativeSoundProvider? =
    if (useKnfOutput) KnfAndroidSoundProvider(SFX_BLOCKS) else null

internal actual val knfMusicLeadBlocks: Int = MUSIC_BLOCKS

internal actual fun knfPrewarmMusicOutput(sampleRate: Int) {
    if (!useKnfOutput) return
    TrackPool.prewarm(TrackKey(channels = 2, rate = sampleRate, blocks = MUSIC_BLOCKS))
    // Freeplay previews (and the rest of the menus) stream, so warm that shape
    // too: it is the first playback of a session that would otherwise pay for
    // the `AudioTrack` construction.
    TrackPool.prewarm(TrackKey(channels = 2, rate = sampleRate, blocks = STREAM_BLOCKS))
}
