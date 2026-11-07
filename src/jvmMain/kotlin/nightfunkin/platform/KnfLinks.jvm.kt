package nightfunkin.platform

import java.awt.Desktop
import java.net.URI

actual object KnfLinks {
    actual fun open(url: String) {
        runCatching {
            Desktop.getDesktop().browse(URI(url))
        }.onFailure {
            println("KNF: could not open link '$url': ${it.message}")
        }
    }
}
