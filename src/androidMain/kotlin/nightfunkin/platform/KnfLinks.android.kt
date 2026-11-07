package nightfunkin.platform

import android.content.Intent
import android.net.Uri

actual object KnfLinks {
    actual fun open(url: String) {
        val context = AndroidContextHolder.appContext()
        if (context == null) {
            println("KNF: could not open link '$url': no app context")
            return
        }
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            println("KNF: could not open link '$url': ${it.message}")
        }
    }
}
