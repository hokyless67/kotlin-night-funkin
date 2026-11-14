package nightfunkin.audio

import korlibs.audio.sound.NativeSoundProvider

/**
 * iOS keeps korlibs' CoreAudio output: it queues three buffers natively and the
 * pump is not the bottleneck there, so there is nothing to substitute yet.
 */
internal actual val knfMusicSoundProvider: NativeSoundProvider? = null

/** CoreAudio already queues several buffers for streams; see [knfStreamSoundProvider]. */
internal actual val knfStreamSoundProvider: NativeSoundProvider? = null

internal actual val knfSfxSoundProvider: NativeSoundProvider? = null

internal actual val knfMusicLeadBlocks: Int = 0

/** CoreAudio is opened by korlibs itself here; there is no device to prewarm. */
internal actual fun knfPrewarmMusicOutput(sampleRate: Int) = Unit
