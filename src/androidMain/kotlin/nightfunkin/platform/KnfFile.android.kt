package nightfunkin.platform

import java.io.File

/**
 * Android has `java.io.File` and full access to the app's own storage dirs
 * (`Android/data/dev.hokyless.kotlinfunkin/...`), so the actual is identical
 * to the desktop one. The only platform difference is *where* the roots are,
 * which [KnfPlatform] handles through the app context.
 */
actual class KnfFile actual constructor(private val path: String) {
    private val file: File = File(path)

    actual val name: String get() = file.name

    actual fun isFile(): Boolean = file.isFile

    actual fun isDirectory(): Boolean = file.isDirectory

    actual fun listNames(): List<String>? =
        file.list()?.sorted()

    actual fun readBytes(): ByteArray = file.readBytes()

    actual fun readText(): String = file.readText()

    actual fun writeText(text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    actual fun child(name: String): KnfFile = KnfFile(File(file, name).path)

    actual fun absolutePath(): String = file.absolutePath
}
