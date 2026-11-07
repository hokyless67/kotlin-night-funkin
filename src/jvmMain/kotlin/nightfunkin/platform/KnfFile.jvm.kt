package nightfunkin.platform

import java.io.File

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
