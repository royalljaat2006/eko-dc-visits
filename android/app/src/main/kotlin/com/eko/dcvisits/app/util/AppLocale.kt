package com.eko.dcvisits.app.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * Per-app language (System default / English / हिंदी).
 *
 * The switch itself is AppCompat's [AppCompatDelegate.setApplicationLocales]:
 *  - Android 13+: the OS stores it (android:localeConfig) and applies it to the
 *    whole process — activities, services and notifications alike.
 *  - Android 10–12: AppCompat stores it (AppLocalesMetadataHolderService with
 *    autoStoreLocales) and applies it to AppCompatActivity only. Non-activity
 *    contexts (the tracking service's notification) keep the system locale, so
 *    we mirror the tag into a tiny SharedPreferences file and [localized] wraps
 *    such contexts with it.
 */
object AppLocale {
    /** Language tags offered in the Profile screen. "" = follow the phone. */
    const val SYSTEM = ""
    const val ENGLISH = "en"
    const val HINDI = "hi"

    private const val PREFS = "app_locale"
    private const val KEY_TAG = "tag"

    @Volatile private var cached: Pair<String, Context>? = null

    /** The currently chosen tag ("" when following the system language). */
    fun current(context: Context): String {
        val fromAppCompat = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (fromAppCompat.isNotEmpty()) return primary(fromAppCompat)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) SYSTEM else stored(context)
    }

    /** Applies [tag] app-wide; AppCompat recreates the visible activity so the UI switches immediately. */
    fun set(context: Context, tag: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TAG, tag).apply()
        cached = null
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
        )
    }

    /**
     * A context whose resources use the chosen app language, for code that has no
     * AppCompatActivity (services, notifications). A no-op on Android 13+, where
     * the OS already applies the per-app locale to every context.
     */
    fun localized(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val tag = current(context)
        if (tag.isEmpty()) return context
        cached?.let { (t, c) -> if (t == tag) return c }
        val config = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(tag)))
        }
        return context.createConfigurationContext(config).also { cached = tag to it }
    }

    private fun stored(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TAG, SYSTEM) ?: SYSTEM

    private fun primary(tags: String): String = tags.substringBefore(',').substringBefore('-')
}
