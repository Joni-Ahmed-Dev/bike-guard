package com.bikeguard.app.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.Prefs
import java.util.Locale

/**
 * In-app language (v1.2.20). The app ships en + bn strings but used to follow
 * the system only. The choice is stored in Prefs and applied by wrapping the
 * base context — no appcompat dependency, works back to the minSdk (26).
 * Changing it recreates the activity, so the whole UI switches at once.
 */
object LocaleHelper {
    /** Wraps the context with the stored language ("" = system: no wrapping). */
    fun wrap(context: Context): Context {
        val code = runCatching { Prefs(context).language }.getOrNull().orEmpty()
        if (code.isBlank()) return context
        return wrapWith(context, code)
    }

    private fun wrapWith(context: Context, code: String): Context {
        val locale = Locale.forLanguageTag(code)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    /** Saves the choice and recreates the activity so the new language applies everywhere. */
    fun setLanguage(activity: Activity, code: String) {
        activity.bikeApp.prefs.language = code
        if (code.isBlank()) {
            // Back to the system language: restore the default locale too, so
            // date/time formatting follows the phone again.
            val sys = Resources.getSystem().configuration.locales.get(0)
            if (sys != null) Locale.setDefault(sys)
        } else {
            Locale.setDefault(Locale.forLanguageTag(code))
        }
        activity.recreate()
    }

    /** Saves the theme choice and recreates the activity, so the status-bar style and window background follow too. */
    fun setThemeMode(activity: Activity, mode: String) {
        activity.bikeApp.prefs.themeMode = mode
        activity.recreate()
    }
}
