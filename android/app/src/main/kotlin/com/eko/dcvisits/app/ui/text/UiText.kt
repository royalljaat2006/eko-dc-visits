package com.eko.dcvisits.app.ui.text

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * A user-facing message produced outside the UI (ViewModels) and resolved in
 * the UI, so it always renders in the language currently active on screen —
 * including right after an in-app language switch, without the ViewModel
 * having to hold a Context.
 *
 * [Raw] is for text that is never translated client-side (server-sent problem
 * details, exception messages).
 */
sealed interface UiText {
    data class Raw(val value: String) : UiText

    /** [args] may themselves be [UiText]; they are resolved first. */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    fun resolve(context: Context): String = when (this) {
        is Raw -> value
        is Res -> if (args.isEmpty()) context.getString(id)
        else context.getString(id, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())

        /** The throwable's own (server/exception) message when present, else a localized fallback. */
        fun messageOr(e: Throwable, @StringRes fallback: Int): UiText =
            e.message?.let(::Raw) ?: Res(fallback)
    }
}

@Composable
@ReadOnlyComposable
fun UiText.asString(): String {
    LocalConfiguration.current // re-resolve when the locale/configuration changes
    return resolve(LocalContext.current)
}
