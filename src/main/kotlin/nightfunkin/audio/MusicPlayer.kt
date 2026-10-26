package nightfunkin.audio

import korlibs.audio.sound.AudioChannel
import korlibs.audio.sound.PlaybackTimes
import korlibs.audio.sound.Sound
import korlibs.audio.sound.SoundChannel
import korlibs.audio.sound.readSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import nightfunkin.core.KnfFiles
import java.io.File
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
    suspend fun load(path: String, volume: Double = 1.0): Boolean {
        if (loadedPath == path && sound != null) return true
        return loadInternal(path, path, volume, analyze = true) { KnfFiles.bytesOrNull(path) }
    }

    /**
     * Loads an audio file from the real filesystem.
     *
     * Video cutscenes use this for the WAV that ffmpeg extracts from the movie
     * container. The extracted file is temporary and is deleted immediately
     * after [loadFile] returns, because KorGE decodes the whole sound into
     * memory here.
     */
    suspend fun loadFile(file: File, volume: Double = 1.0, analyze: Boolean = false): Boolean {
        val key = "file:" + file.absolutePath
        if (loadedPath == key && sound != null) return true
        return loadInternal(key, key, volume, analyze) {
            if (file.isFile) file.readBytes() else null
        }
    }

    private suspend fun loadInternal(
        key: String,
        missingName: String,
        volume: Double,
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
            val loadedSound = bytes.readSound()
            // Sound.length is zero for KorGE's non-streaming SoundAudioData, but
            // its decoded AudioData still knows the real duration. Freeplay
            // needs this before playback starts to compute the preview window.
            // The same samples also feed A-Bot's spectrum display.
            val audio = if (analyze) runCatching { loadedSound.decode() }.getOrNull() else null
            val lengthMs = audio?.totalTime?.toMsDouble() ?: 0.0
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
}

/**
 * Fire-and-forget sound effects (countdown blips, miss sounds, UI clicks).
 *
 * FNF 0.8.6 dropped per-song hitsounds, so KNF reuses the real countdown and
 * miss samples that ship in `shared/sounds/` and synthesises a click for taps.
 */
class SfxPlayer(private val maxVoices: Int = 12) {

    private val cache = HashMap<String, Sound>()
    private val active = ArrayList<AudioChannel>()

    var volume: Double = 1.0

    suspend fun preload(path: String): Sound? {
        cache[path]?.let { return it }
        val bytes = KnfFiles.bytesOrNull(path) ?: return null
        val s = bytes.readSound()
        cache[path] = s
        return s
    }

    /**
     * Plays [path] and returns its channel so callers that need to wait for the
     * sound to finish (the intro cutscenes) can poll it. Existing fire-and-forget
     * callers can keep ignoring the result.
     */
    fun play(path: String, volume: Double = this.volume, pitch: Double = 1.0): AudioChannel? {
        val s = cache[path] ?: return null
        prune()
        if (active.size >= maxVoices) return null
        return runCatching {
            val ac = AudioChannel()
            ac.volume = volume
            ac.pitch = pitch
            ac.play(s)
            active += ac
            ac
        }.getOrNull()
    }

    private fun prune() {
        active.removeAll { it.playing.not() }
    }

    fun stopAll() {
        active.forEach { it.stop() }
        active.clear()
    }
}
