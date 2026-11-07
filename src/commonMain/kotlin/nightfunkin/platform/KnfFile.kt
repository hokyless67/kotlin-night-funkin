package nightfunkin.platform

/**
 * Platform-independent, synchronous file handle - the common replacement for
 * `java.io.File` in game code.
 *
 * The game streams its 1.2 GB asset tree straight off the filesystem (nothing
 * is bundled), and almost every call site is a plain non-suspend function, so
 * the abstraction mirrors the JDK API instead of korlibs' suspend-based VFS:
 * blocking reads, `null`-returning directory listings, and per-platform roots
 * resolved by [KnfPlatform].
 *
 * Each target provides an actual:
 *  - JVM / Android: a thin wrapper over `java.io.File`.
 *  - iOS: a small POSIX implementation (fopen/opendir), since Kotlin/Native
 *    has no java.io.
 */
expect class KnfFile(path: String) {
    /** Final path segment, e.g. `NOTE_assets.png`. */
    val name: String

    /** True when the path names an existing regular file. */
    fun isFile(): Boolean

    /** True when the path names an existing directory. */
    fun isDirectory(): Boolean

    /**
     * Names of the entries of this directory, or `null` when this is not a
     * directory (mirrors the old `File.listFiles() == null` semantics that the
     * directory scans relied on).
     */
    fun listNames(): List<String>?

    /** Reads the whole file. Throws when the file does not exist. */
    fun readBytes(): ByteArray

    fun readText(): String

    /** Writes the whole file, creating missing parent directories. */
    fun writeText(text: String)

    /** Resolves [name] relative to this file/folder. */
    fun child(name: String): KnfFile

    /** Absolute form of [path] where the platform can provide one. */
    fun absolutePath(): String
}
