package nightfunkin.platform

import android.app.Activity
import android.content.Context

/**
 * Grabbing the app [Context] (and the hosting [Activity]) without touching
 * KorGE's Android bootstrap: the korge Gradle plugin generates its own
 * activity, so instead of wiring context through it we ask the main looper's
 * `ActivityThread` for the running `Application` (the standard library-level
 * trick). Both lookups are reflection because `ActivityThread` is a hidden API.
 *
 * The activity itself does not need reflection: `KnfPlatform.prepareWindow`
 * is handed the korlibs game window, which owns a public reference to it, and
 * remembers it here. The reflective fallback only covers a cutscene requested
 * before the window was prepared.
 */
internal object AndroidContextHolder {
    @Volatile
    private var cached: Context? = null

    @Volatile
    private var cachedActivity: Activity? = null

    /** Remembers the activity that hosts the game window. */
    fun rememberActivity(activity: Activity) {
        cachedActivity = activity
        cached = activity.applicationContext
    }

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

    /** The activity hosting the game, or `null` when it cannot be found. */
    fun activity(): Activity? {
        cachedActivity?.let { activity ->
            if (!activity.isFinishing && !activity.isDestroyed) return activity
            cachedActivity = null
        }
        val found = currentActivity() ?: return null
        cachedActivity = found
        cached = found.applicationContext
        return found
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

    private fun currentActivity(): Activity? = try {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("currentActivityThread").invoke(null)
        val field = threadClass.getDeclaredField("mActivities").apply { isAccessible = true }
        val records = field.get(thread) as? Map<*, *>
        var result: Activity? = null
        if (records != null) {
            for (record in records.values) {
                if (record == null) continue
                val activityField = record.javaClass
                    .getDeclaredField("activity")
                    .apply { isAccessible = true }
                val activity = activityField.get(record) as? Activity ?: continue
                if (activity.isFinishing || activity.isDestroyed) continue
                result = activity
                break
            }
        }
        result
    } catch (_: Throwable) {
        null
    }
}
