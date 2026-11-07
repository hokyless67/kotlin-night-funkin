package nightfunkin.platform

import android.content.Context

/**
 * Grabbing the app [Context] without touching KorGE's Android bootstrap:
 * the korge Gradle plugin generates its own activity, so instead of wiring
 * context through it we ask the main looper's `ActivityThread` for the
 * running `Application` (the standard library-level trick). Both lookups are
 * reflection because `ActivityThread` is a hidden API.
 */
internal object AndroidContextHolder {
    @Volatile
    private var cached: Context? = null

    fun appContext(): Context? {
        cached?.let { return it }
        return try {
            val context = fromActivityThread() ?: fromAppGlobals()
            if (context != null) cached = context
            context
        } catch (_: Throwable) {
            null
        }
    }

    private fun fromActivityThread(): Context? {
        val thread = Class.forName("android.app.ActivityThread")
        val currentApplication = thread.getMethod("currentApplication")
        return currentApplication.invoke(null) as? Context
    }

    private fun fromAppGlobals(): Context? {
        val globals = Class.forName("android.app.AppGlobals")
        val getInitialApplication = globals.getMethod("getInitialApplication")
        return getInitialApplication.invoke(null) as? Context
    }
}
