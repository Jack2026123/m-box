package com.github.tvbox.osc.ui.theme

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb

/**
 * Bridge from the Compose theme (AppThemeState / AVBoxTypography) to the
 * View-based dialogs (local-source popups, float log). Everything resolves
 * from the very same ColorScheme the Compose UI renders with, so switching
 * theme seed/mode recolors these surfaces on next open — no copied hex
 * values live here.
 *
 * Type scale mirrors AVBoxTypography (ui/theme/Type.kt): dialog/sheet
 * titles are titleMedium 16sp Medium, body text bodyMedium 14sp, secondary
 * text bodySmall 12sp, button labels labelLarge 14sp Medium, small labels
 * labelMedium 13sp Medium.
 */
object ViewTheme {

    const val TITLE_SP = 16f
    const val BODY_SP = 14f
    const val SECONDARY_SP = 12f
    const val LABEL_SP = 13f

    private var cachedConfig: ThemeConfig? = null
    private var cachedDark = false
    private var cachedScheme: ColorScheme? = null

    fun scheme(context: Context): ColorScheme {
        val config = AppThemeState.config
        val dark = AppThemeState.isDark(isSystemDark(context))
        val hit = cachedScheme
        if (hit != null && cachedConfig == config && cachedDark == dark) return hit
        val base = AppThemeState.resolveScheme(context, config, dark)
        val resolved = if (dark && config.pureBlack) base.toPureBlack() else base
        cachedConfig = config
        cachedDark = dark
        cachedScheme = resolved
        return resolved
    }

    private fun isSystemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun surfaceArgb(context: Context): Int = scheme(context).surface.toArgb()

    fun surfaceContainerArgb(context: Context): Int = scheme(context).surfaceContainer.toArgb()

    fun surfaceContainerHighArgb(context: Context): Int =
        scheme(context).surfaceContainerHigh.toArgb()

    fun primaryArgb(context: Context): Int = scheme(context).primary.toArgb()

    fun onPrimaryArgb(context: Context): Int = scheme(context).onPrimary.toArgb()

    fun primaryContainerArgb(context: Context): Int = scheme(context).primaryContainer.toArgb()

    fun secondaryContainerArgb(context: Context): Int = scheme(context).secondaryContainer.toArgb()

    fun onSecondaryContainerArgb(context: Context): Int =
        scheme(context).onSecondaryContainer.toArgb()

    fun onSurfaceArgb(context: Context): Int = scheme(context).onSurface.toArgb()

    fun onSurfaceVariantArgb(context: Context): Int = scheme(context).onSurfaceVariant.toArgb()

    fun tertiaryArgb(context: Context): Int = scheme(context).tertiary.toArgb()

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun mediumTypeface(): Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun roundedRect(context: Context, argb: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(argb)
        }

    /** Native dialog container: surfaceContainer card, 28dp corners (DialogShape). */
    fun dialogBackground(context: Context): GradientDrawable =
        roundedRect(context, surfaceContainerArgb(context), 28)

    /** Make the dialog window transparent so the card drawable's corners show. */
    fun clearWindowBackground(dialog: Dialog) {
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun ripple(context: Context, content: GradientDrawable): RippleDrawable {
        val primary = primaryArgb(context)
        val rippleColor = Color.argb(
            51,
            Color.red(primary),
            Color.green(primary),
            Color.blue(primary),
        )
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)
    }

    /** Footer-style text button (native sheet/alert pattern): primary text, no fill. */
    fun styleTextButton(context: Context, button: Button) {
        val primary = primaryArgb(context)
        val rippleColor = Color.argb(
            51,
            Color.red(primary),
            Color.green(primary),
            Color.blue(primary),
        )
        button.background = RippleDrawable(ColorStateList.valueOf(rippleColor), null, null)
        button.setTextColor(primary)
        button.textSize = BODY_SP
        button.isAllCaps = false
        button.typeface = mediumTypeface()
    }

    /**
     * Bare text action: NO background at all (null background, no ripple,
     * no state-list animator). Used for the local-source dialog's two entry
     * rows, where any Button background/ripple renders as a giant pale disc
     * behind the label on device — the rows must read as plain themed text,
     * exactly like the dialog's ordinary text lines.
     */
    fun stylePlainTextButton(context: Context, button: Button) {
        button.background = null
        button.stateListAnimator = null
        button.setTextColor(primaryArgb(context))
        button.textSize = BODY_SP
        button.isAllCaps = false
        button.typeface = mediumTypeface()
    }

    /** Content button: filled-tonal pill in secondaryContainer (native tonal look). */
    fun styleTonalButton(context: Context, button: Button) {
        button.background = ripple(
            context,
            roundedRect(context, secondaryContainerArgb(context), 20),
        )
        button.setTextColor(onSecondaryContainerArgb(context))
        button.textSize = BODY_SP
        button.isAllCaps = false
        button.typeface = mediumTypeface()
    }

    /** Disabled placeholder button (e.g. "already the main jar"). */
    fun styleMutedButton(context: Context, button: Button) {
        button.background = roundedRect(context, surfaceContainerHighArgb(context), 20)
        button.setTextColor(onSurfaceVariantArgb(context))
        button.textSize = BODY_SP
        button.isAllCaps = false
        button.typeface = mediumTypeface()
    }

    /** Folder tab in the source list: selected = primary pill, else quiet pill. */
    fun styleTabButton(context: Context, button: Button, selected: Boolean) {
        if (selected) {
            button.background = ripple(context, roundedRect(context, primaryArgb(context), 20))
            button.setTextColor(onPrimaryArgb(context))
        } else {
            button.background = ripple(
                context,
                roundedRect(context, surfaceContainerHighArgb(context), 20),
            )
            button.setTextColor(onSurfaceVariantArgb(context))
        }
        button.textSize = BODY_SP
        button.isAllCaps = false
        button.typeface = mediumTypeface()
    }

    /** Filled text field look: surfaceContainerHigh card, theme text/hint colors. */
    fun styleInput(context: Context, input: EditText) {
        input.background = roundedRect(context, surfaceContainerHighArgb(context), 12)
        input.setTextColor(onSurfaceArgb(context))
        input.setHintTextColor(onSurfaceVariantArgb(context))
        input.textSize = BODY_SP
        val padH = dp(context, 10)
        input.setPadding(padH, input.paddingTop, padH, input.paddingBottom)
    }

    /** Checkbox/radio: primary when checked, onSurface text at body size. */
    fun tintCompound(context: Context, box: CompoundButton) {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        )
        val colors = intArrayOf(
            primaryArgb(context),
            onSurfaceVariantArgb(context),
        )
        box.buttonTintList = ColorStateList(states, colors)
        box.setTextColor(onSurfaceArgb(context))
        box.textSize = BODY_SP
    }
}
