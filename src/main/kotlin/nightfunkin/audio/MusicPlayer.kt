package nightfunkin.audio

import korlibs.audio.sound.AudioChannel
import korlibs.audio.sound.PlaybackTimes
import korlibs.audio.sound.Sound
import korlibs.audio.sound.readSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import nightfunkin.core.KnfFiles
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/** Converts a frame delta into plain seconds. */
fun Duration.dtSeconds(): Double = inWholeNanoseconds / 1_000_000_000.0

fun Duration.toMsDouble(): Double = toDouble(DurationUnit.MILLISECONDS)

/**
 * One playing music track.
 *
 * KorGE 6's `AudioChannel` reports playback position as a `Duration` with
 * sub-millisecond precision, which is exactly what the Conductor needs. Pause
 * is implemented as "remember position, stop the channel" so it works on every
 * backend, and seeking always restarts the channel at the new offset.
 */
class MusicPlayer {

    private var sound: Sound? = null
    private var channel: AudioChannel? = null
    private var stoppedAtMs: Double = 0.0
    private var storedLengthMs: Double = 0.0
    private var paused = false

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

    val playing: Boolean get() = channel?.playing == true

    /** Loads [path] (an asset key relative to the FNF asset root). */
    suspend fun load(path: String, volume: Double = 1.0): Boolean {
        if (loadedPath == path && sound != null) return true
        stop()

        // Reading and decoding a full instrumental is CPU/file-bound and can
        // easily take a few hundred milliseconds. KorGE's main dispatcher
        // drains scene callbacks per frame, so doing this there stalls the game
        // and logs "Too many callbacks to handle in this frame".
        val loaded = withContext(Dispatchers.Default) {
            val bytes = KnfFiles.bytesOrNull(path) ?: return@withContext null
            val loadedSound = bytes.readSound()
            // Sound.length is zero for KorGE's non-streaming SoundAudioData, but
            // its decoded AudioData still knows the real duration. Freeplay
            // needs this before playback starts to compute the preview window.
            val lengthMs = runCatching { loadedSound.decode().totalTime.toMsDouble() }.getOrDefault(0.0)
            loadedSound to lengthMs
        }
        if (loaded == null) {
            println("KNF: audio missing: $path")
            return false
        }

        val (s, lengthMs) = loaded
        s.volume = volume
        sound = s
        this.volume = volume
        loadedPath = path
        stoppedAtMs = 0.0
        storedLengthMs = lengthMs
        return true
    }

    fun play(startMs: Double = 0.0) {
        val s = sound ?: return
        channel?.stop()
        stoppedAtMs = startMs
        paused = false
        val ac = AudioChannel()
        ac.volume = volume
        ac.pitch = pitch
        val offset: Duration = startMs.milliseconds
        val times = if (loop) PlaybackTimes.INFINITE else PlaybackTimes.ONE
        ac.play(s, times, offset, playbackContext)
        channel = ac
    }

    fun pause() {
        if (channel == null) return
        stoppedAtMs = positionMs
        channel?.stop()
        channel = null
        paused = true
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

    fun play(path: String, volume: Double = this.volume, pitch: Double = 1.0) {
        val s = cache[path] ?: return
        prune()
        if (active.size >= maxVoices) return
        runCatching {
            val ac = AudioChannel()
            ac.volume = volume
            ac.pitch = pitch
            ac.play(s)
            active += ac
        }
    }

    private fun prune() {
        active.removeAll { it.playing.not() }
    }

    fun stopAll() {
        active.forEach { it.stop() }
        active.clear()
    }
}
