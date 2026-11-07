package nightfunkin.audio

import korlibs.audio.format.defaultAudioFormats
import korlibs.audio.sound.AudioData
import korlibs.audio.sound.AudioSample
import korlibs.audio.sound.PlaybackTimes
import korlibs.audio.sound.Sound
import korlibs.audio.sound.SoundChannel
import korlibs.audio.sound.nativeSoundProvider
import korlibs.audio.sound.readSound
import korlibs.concurrent.lock.Lock
import korlibs.io.stream.openAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nightfunkin.core.KnfFiles
import nightfunkin.core.KnfFmt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/** Converts a frame delta into plain seconds. */
fun Duration.dtSeconds(): Double = inWholeNanoseconds / 1_000_000_000.0

fun Duration.toMsDouble(): Double = toDouble(DurationUnit.MILLISECONDS)

/**
 * One playing music track.
 *
 * KorGE 6's `SoundChannel` reports playback position as a `Duration` with
 * sub-millisecond precision, which is exactly what the Conductor needs.
 *
 * Pause is "remember the position, drop the channel" rather than the audio
 * backend's `pause()`. On the JVM WaveOut backend every channel owns a separate
 * native device, whose queue keeps playing for ~100 ms after `pause()` and
 * whose resume timing differs per stem. That made every pause leave the
 * instrumental and both vocal tracks permanently offset from each other. The
 * caller resumes every stem from one shared position instead (see
 * `PlayScene.closePause`), which rebuilds all queues from the same sample.
 *
 * Seeks use the channel's `current` setter. `SoundAudioData` ignores
 * `PlaybackParameters.startTime` (KorGE 6.0.0), so [play] applies the offset
 * explicitly once the channel exists.
 */
class MusicPlayer {

    private var sound: Sound? = null
    private var channel: SoundChannel? = null
    private var stoppedAtMs: Double = 0.0
    private var storedLengthMs: Double = 0.0
    private var paused = false
    private var analyzer: SpectrumAnalyzer? = null

    /**
     * KorGE's `SoundAudioData` registers its `onCancel` stop hook on the
     * coroutine context it was decoded in. Loading a persistent track while a
     * scene is alive therefore ties it to that scene's lifetime: switching
     * scenes cancels the job and silences the song. Playback runs on this
     * player-owned context instead so `freakyMenu` survives the title screen.
     */
    private val playbackContext = SupervisorJob()

    var loadedPath: String? = null
        private set

    /** `FunkinSound.playMusic`'s `loop` flag (menu music metadata is looped). */
    var loop: Boolean = false

    var volume: Double = 1.0
        set(value) {
            field = value
            sound?.volume = value
            channel?.volume = value
        }

    var pitch: Double = 1.0
        set(value) {
            field = value
            channel?.pitch = value
        }

    val exists: Boolean get() = sound != null

    /** Set by [pause] and cleared by [play]/[stop]; unlike [playing] it survives a scene switch. */
    val isPaused: Boolean get() = paused

    val totalMs: Double
        get() = channel?.total?.toMsDouble() ?: storedLengthMs

    val positionMs: Double
        get() = channel?.current?.toMsDouble() ?: stoppedAtMs

    val playing: Boolean get() = channel?.state?.playing == true

    /** Loads [path] (an asset key relative to the FNF asset root). */
    suspend fun load(
        path: String,
        volume: Double = 1.0,
        streaming: Boolean = false,
        analyze: Boolean = true,
    ): Boolean {
        if (loadedPath == path && sound != null) return true
        return loadInternal(path, path, volume, streaming, analyze) { KnfFiles.bytesOrNull(path) }
    }

    /**
     * Loads audio from raw bytes.
     *
     * Video cutscenes use this for the WAV track the platform video backend
     * extracts from the movie container. KorGE decodes the whole sound into
     * memory here, so nothing needs to outlive the call.
     */
    suspend fun loadBytes(
        key: String,
        bytes: ByteArray?,
        volume: Double = 1.0,
        analyze: Boolean = false,
    ): Boolean {
        if (bytes == null) {
            println("KNF: audio missing: $key")
            return false
        }
        if (loadedPath == key && sound != null) return true
        return loadInternal(key, key, volume, streaming = false, analyze = analyze) { bytes }
    }

    private suspend fun loadInternal(
        key: String,
        missingName: String,
        volume: Double,
        streaming: Boolean,
        analyze: Boolean,
        readBytes: () -> ByteArray?,
    ): Boolean {
        stop()

        // Reading and decoding a full instrumental is CPU/file-bound and can
        // easily take a few hundred milliseconds. KorGE's main dispatcher
        // drains scene callbacks per frame, so doing this there stalls the game
        // and logs "Too many callbacks to handle in this frame".
        val loaded = withContext(Dispatchers.Default) {
            val bytes = readBytes() ?: return@withContext null

            // Gameplay stems MUST be fully decoded: the streaming decoder's
            // output position jumps in buffer-sized chunks and can underrun
            // under CPU load, which desyncs the stems from each other. Solo
            // tracks (menu music, previews) opt into streaming instead — a
            // momentary gap there is harmless. Decoded PCM costs ~10 MB per
            // minute of stereo audio; `largeHeap` covers a song's worth.
            val loadedSound = bytes.readSound(streaming = streaming)

            // Sound.length is zero for KorGE's non-streaming SoundAudioData,
            // but its decoded AudioData still knows the real duration.
            // Streaming sounds report it from the MP3 header; fall back to a
            // cheap metadata probe when the header did not carry one. Freeplay
            // needs this before playback starts to compute the preview window.
            val audio = if (analyze && !streaming) runCatching { loadedSound.decode() }.getOrNull() else null
            val lengthMs = audio?.totalTime?.toMsDouble()
                ?: loadedSound.length.toMsDouble().takeIf { it > 0.0 }
                ?: (runCatching { probeDurationMs(bytes) }.getOrNull() ?: 0.0)
            println(
                "KNF: audio '$key' streaming=$streaming rate=${audio?.rate} " +
                    "channels=${audio?.channels} totalSamples=${audio?.totalSamples} " +
                    "lengthMs=${KnfFmt.decimals(lengthMs, 0)}"
            )
            Triple(loadedSound, lengthMs, audio)
        }
        if (loaded == null) {
            println("KNF: audio missing: $missingName")
            return false
        }

        val (s, lengthMs, audio) = loaded
        s.volume = volume
        sound = s
        analyzer = audio?.let { runCatching { SpectrumAnalyzer(it) }.getOrNull() }
        this.volume = volume
        loadedPath = key
        stoppedAtMs = 0.0
        storedLengthMs = lengthMs
        return true
    }

    /**
     * Current seven-band levels for the A-Bot visualizer, or null when no
     * music is playing. The analyzer reads the decoded instrumental directly,
     * so it stays in sync with `SoundChannel.current` through seeks.
     */
    fun visualizerLevels(): FloatArray? {
        val ch = channel ?: return null
        if (ch.state?.playing != true || volume <= 0.0) return null
        return analyzer?.levels(ch.current.toMsDouble())
    }

    fun play(startMs: Double = 0.0) {
        val s = sound ?: return
        channel?.stop()
        stoppedAtMs = startMs
        paused = false
        val times = if (loop) PlaybackTimes.INFINITE else PlaybackTimes.ONE
        val ch = s.play(playbackContext, times, startMs.milliseconds)
        ch.volume = volume
        ch.pitch = pitch
        // `SoundAudioData` ignores the start time passed to `play`, so apply
        // the offset on the channel directly.
        if (startMs > 0.0) ch.current = startMs.milliseconds
        channel = ch
    }

    /** Position stored by the last [pause], for resuming every stem in sync. */
    val pausedAtMs: Double get() = stoppedAtMs

    fun pause() {
        val ch = channel ?: return
        stoppedAtMs = ch.current.toMsDouble()
        ch.stop()
        channel = null
        paused = true
    }

    /**
     * Pause only when a channel exists. The channel state can briefly report
     * not-playing right after [play], so callers that need to know whether the
     * track should resume use this instead of [playing].
     *
     * @return true when a channel was stopped and [resume] is needed.
     */
    fun pauseIfPlaying(): Boolean {
        if (channel == null) return false
        pause()
        return true
    }

    fun resume() = play(stoppedAtMs)

    fun stop() {
        channel?.stop()
        channel = null
        stoppedAtMs = 0.0
        paused = false
    }

    /** Hard seek. Resumes only if we were playing. */
    fun seek(ms: Double) {
        if (sound == null) return
        val wasPlaying = playing
        play(ms)
        if (!wasPlaying) pause()
    }

    fun dispose() {
        stop()
        playbackContext.cancel()
        sound = null
        analyzer = null
        loadedPath = null
        storedLengthMs = 0.0
    }

    /**
     * Reads the duration from the audio container's header (MP3 frame header /
     * Ogg granule) without decoding any samples — the fallback for streaming
     * sounds whose stream did not carry a length.
     */
    private suspend fun probeDurationMs(bytes: ByteArray): Double? =
        runCatching { defaultAudioFormats.tryReadInfo(bytes.openAsync())?.duration }
            .getOrNull()
            ?.takeIf { it > Duration.ZERO }
            ?.toMsDouble()
}

/**
 * Fire-and-forget sound effects (countdown blips, miss sounds, UI clicks).
 *
 * FNF 0.8.6 dropped per-song hitsounds, so KNF reuses the real countdown and
 * miss samples that ship in `shared/sounds/` and synthesises a click for taps.
 */
class SfxPlayer {

    private val cache = HashMap<String, Sound>()

    /** Decoded PCM per cached sound: the software mixer reads samples directly. */
    private val audioCache = HashMap<String, AudioData>()

    /**
     * `SoundAudioData` captures the coroutine context it was decoded in and
     * stops its playback when that job is cancelled. Decoding in a scene's
     * `sceneInit` would tie every effect to that scene, so the cache is filled
     * on this detached supervisor instead.
     */
    private val loadContext = SupervisorJob()

    /** Late loads for paths `play()` was asked for before `preload()` cached them. */
    private val loader = CoroutineScope(loadContext + Dispatchers.Default)
    private val loading = HashSet<String>()

    // ---- software mixer ---------------------------------------------------
    //
    // korlibs opens one platform audio output PER playing channel; desktop
    // WaveOut hard-caps them (~a dozen), so a sticker transition popping ~90
    // key clicks crashed the process. All SFX therefore mix into ONE shared
    // output through this tiny software mixer: unlimited voices, one device.

    private val mixLock = Lock()
    private val voices = ArrayList<Voice>()
    private var mixerStarted = false

    suspend fun preload(path: String): Sound? {
        cache[path]?.let { return it }
        val s = withContext(loadContext) {
            val bytes = KnfFiles.bytesOrNull(path) ?: return@withContext null
            bytes.readSound()
        } ?: return null
        // `withContext` spins up a short-lived child job; bind playback to the
        // long-lived supervisor instead so scene teardown cannot cancel it.
        s.defaultCoroutineContext = loadContext
        // Decode the PCM once for the software mixer (small files: clicks etc).
        runCatching { s.decode() }.getOrNull()?.also { audioCache[path] = it }
        cache[path] = s
        return s
    }

    /**
     * Plays [path] and returns a handle so callers that need to wait for the
     * sound to finish (the intro cutscenes) can poll it. Existing
     * fire-and-forget callers can keep ignoring the result.
     *
     * A missing cache entry - the scene can be replaced before `sceneInit`
     * finished its preloads, which is how the first song of a session used to
     * lose its death SFX - is decoded on demand, like `FunkinSound.playOnce`.
     * [onLateStart] fires once with the channel (or `null`) in that case.
     */
    fun play(
        path: String,
        volume: Double = 1.0,
        pitch: Double = 1.0,
        onLateStart: ((SfxChannel?) -> Unit)? = null,
    ): SfxChannel? {
        val s = cache[path]
        if (s == null) {
            if (loading.add(path)) {
                loader.launch {
                    val loaded = preload(path)
                    loading.remove(path)
                    val channel = loaded?.let { sound ->
                        println("KNF: sfx late load: $path")
                        start(sound, volume, pitch, path)
                    }
                    onLateStart?.invoke(channel)
                }
            }
            return null
        }
        return start(s, volume, pitch, path)
    }

    private fun start(s: Sound, volume: Double, pitch: Double, path: String): SfxChannel? {
        ensureMixer()
        // `preload` always fills `audioCache` before caching the sound.
        val audio = audioCache[path] ?: return null
        val voice = Voice(audio, volume = volume.toFloat(), pitch = pitch)
        val channel = SfxChannel(mixLock, voice)
        mixLock { voices += voice }
        return channel
    }

    fun stopAll() {
        mixLock { voices.forEach { it.done = true } }
    }

    @OptIn(kotlin.ExperimentalStdlibApi::class)
    private fun ensureMixer() {
        if (mixerStarted) return
        mixerStarted = true
        // The gen callback runs on the platform audio thread; `mixLock` guards
        // the voice list against plays/late loads on other threads.
        var mixerDebugCalls = 0
        nativeSoundProvider.createNewPlatformAudioOutput(2, 44100) { buffer ->
            mixLock {
                if (mixerDebugCalls % 20 == 0) {
                    val first = voices.firstOrNull()
                    println(
                        "KNF: mix#${mixerDebugCalls} voices=${voices.size} " +
                            "firstPos=${first?.pos?.toInt()} firstTotal=${first?.data?.totalSamples} " +
                            "firstDone=${first?.done} bufSamples=${buffer.totalSamples} bufCh=${buffer.channels}"
                    )
                }
                mixerDebugCalls++
                for (ch in 0 until buffer.channels) {
                    for (n in 0 until buffer.totalSamples) buffer[ch, n] = AudioSample.ZERO
                }
                val finished = ArrayList<Voice>()
                for (voice in voices) {
                    if (voice.done) continue
                    val total = voice.data.totalSamples
                    val dataChannels = voice.data.channels
                    var pos = voice.pos
                    for (n in 0 until buffer.totalSamples) {
                        if (pos >= total) {
                            voice.done = true
                            finished += voice
                            break
                        }
                        val sampleIndex = pos.toInt()
                        for (ch in 0 until buffer.channels) {
                            val sample = voice.data[if (ch < dataChannels) ch else 0, sampleIndex].toFloat() * voice.volume
                            buffer[ch, n] = AudioSample(buffer[ch, n].toFloat() + sample)
                        }
                        // Naive resampling: `pitch` scales the read speed.
                        pos += voice.pitch
                    }
                    // Persist the read position, or every mix pass restarts
                    // the voice from its beginning (the looping-buzz bug).
                    voice.pos = pos
                    if (pos >= total) {
                        voice.done = true
                        finished += voice
                    }
                }
                if (finished.isNotEmpty()) voices.removeAll(finished.toSet())
            }
        }.start()
    }
}

/** One mixed sfx voice: reads its decoded PCM at a `pitch`-scaled speed. */
internal class Voice(
    val data: AudioData,
    var pos: Double = 0.0,
    var volume: Float = 1f,
    val pitch: Double = 1.0,
    var done: Boolean = false,
)

/** A handle over a playing voice: volume can be nudged live, `stop` cuts it. */
class SfxChannel internal constructor(private val mixLock: Lock, private val voice: Voice) {
    var volume: Double
        get() = voice.volume.toDouble()
        set(value) {
            mixLock { voice.volume = value.toFloat() }
        }

    val playing: Boolean get() = !voice.done

    fun stop() {
        mixLock { voice.done = true }
    }
}
