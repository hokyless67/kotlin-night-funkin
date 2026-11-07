package nightfunkin.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * Must run on the main thread, which is always the case here: the only caller
 * is a menu button handler on the UI thread.
 */
@OptIn(ExperimentalForeignApi::class)
actual object KnfLinks {
    actual fun open(url: String) {
        val nsUrl = NSURL(string = url)
        if (nsUrl == null) {
            println("KNF: could not open link '$url': bad URL")
            return
        }
        UIApplication.sharedApplication.openURL(nsUrl, options = emptyMap<Any?, Any>(), completionHandler = null)
    }
}
