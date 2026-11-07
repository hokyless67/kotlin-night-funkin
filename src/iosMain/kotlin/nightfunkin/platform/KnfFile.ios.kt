package nightfunkin.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.S_IRWXG
import platform.posix.S_IRWXO
import platform.posix.S_IRWXU
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.mkdir
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.rewind
import platform.posix.stat

/**
 * POSIX implementation of [KnfFile] for Kotlin/Native (iOS).
 *
 * Blocking reads, like everywhere else in KNF - the asset pipeline already
 * does all of its decoding off the main dispatcher, and the game's file
 * access is small synchronous chunks by design.
 */
@OptIn(ExperimentalForeignApi::class)
actual class KnfFile actual constructor(private val path: String) {

    actual val name: String
        get() = path.replace('\\', '/').substringAfterLast('/')

    private fun modeFlags(): Int? = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) return null
        return st.st_mode.toInt()
    }

    actual fun isFile(): Boolean = (modeFlags() ?: return false) and S_IFMT == S_IFREG

    actual fun isDirectory(): Boolean = (modeFlags() ?: return false) and S_IFMT == S_IFDIR

    actual fun listNames(): List<String>? {
        if (!isDirectory()) return null
        val names = ArrayList<String>()
        val dir = opendir(path) ?: return emptyList()
        try {
            while (true) {
                val entry = readdir(dir) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name != "." && name != "..") names.add(name)
            }
        } finally {
            closedir(dir)
        }
        return names.sorted()
    }

    actual fun readBytes(): ByteArray {
        val file = fopen(path, "rb") ?: error("KNF: cannot open '$path'")
        try {
            fseek(file, 0, platform.posix.SEEK_END)
            val size = ftell(file).toInt()
            rewind(file)
            val data = ByteArray(size)
            if (size > 0) {
                val read = data.usePinned { pinned ->
                    fread(pinned.addressOf(0), 1.convert(), size.convert(), file)
                }.toInt()
                if (read != size) error("KNF: short read on '$path' ($read of $size bytes)")
            }
            return data
        } finally {
            fclose(file)
        }
    }

    actual fun readText(): String = readBytes().decodeToString()

    actual fun writeText(text: String) {
        mkdirs()
        val file = fopen(path, "wb") ?: return
        try {
            val bytes = text.encodeToByteArray()
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                    fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
                }
            }
        } finally {
            fclose(file)
        }
    }

    actual fun child(name: String): KnfFile =
        KnfFile(if (path.endsWith('/')) path + name else "$path/$name")

    actual fun absolutePath(): String = path

    /** Creates every missing parent directory of [path], ignoring existing ones. */
    private fun mkdirs() {
        val normalized = path.replace('\\', '/')
        var index = normalized.indexOf('/')
        while (index > 0) {
            val dir = normalized.substring(0, index)
            if (dir.isNotEmpty()) {
                mkdir(dir, (S_IRWXU or S_IRWXG or S_IRWXO).convert())
            }
            index = normalized.indexOf('/', index + 1)
        }
    }
}
