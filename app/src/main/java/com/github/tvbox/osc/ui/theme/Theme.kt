package com.github.tvbox.osc.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.WindowCompat
import com.materialkolor.PaletteStyle

internal fun ComponentActivity.enableTransparentEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
        ),
        navigationBarStyle = SystemBarStyle.light(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
        ),
    )
    window.setBackgroundDrawable(ColorDrawable(AppThemeState.windowSurfaceArgb(this)))
}

@Composable
fun AVBoxTheme(
    config: ThemeConfig = AppThemeState.config,
    manageStatusBarIcons: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (config.mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }

    val context = LocalContext.current
    val baseScheme = when {
        config.source == ThemeSource.CUSTOM -> remember(config.seedArgb, darkTheme, config.style) {
            AppThemeState.customScheme(config.seedArgb, darkTheme, config.style)
        }

        else -> AppThemeState.resolveScheme(context, config, darkTheme)
    }
    val baseColorScheme = if (darkTheme && config.pureBlack) baseScheme.toPureBlack() else baseScheme
    val textColorArgb = config.textColorArgb
    val colorScheme = if (textColorArgb != 0) {
        val textColor = androidx.compose.ui.graphics.Color(textColorArgb)
        baseColorScheme.copy(
            onBackground = textColor,
            onSurface = textColor,
            onSurfaceVariant = textColor,
            onPrimary = textColor,
            onSecondary = textColor,
            onTertiary = textColor,
            onPrimaryContainer = textColor,
            onSecondaryContainer = textColor,
            onTertiaryContainer = textColor,
        )
    } else {
        baseColorScheme
    }

    if (manageStatusBarIcons) {
        ApplyAppThemeBars(isDark = darkTheme)
    }

    val effects = EffectsState.config
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val scaledDensity = remember(baseDensity, effects.fontScale) {
        androidx.compose.ui.unit.Density(baseDensity.density, baseDensity.fontScale * effects.fontScale)
    }
    CompositionLocalProvider(
        LocalRippleConfiguration provides rememberRippleConfiguration(),
        LocalAnimFactor provides effects.animFactor,
        androidx.compose.ui.platform.LocalDensity provides scaledDensity,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AVBoxTypography,
            shapes = scaledShapes(effects.cornerScale),
            content = content,
        )
    }
}

/** Material3 default corner radii scaled by the 全局圆角 effect factor. */
private fun scaledShapes(factor: Float): androidx.compose.material3.Shapes {
    if (factor == 1f) return androidx.compose.material3.Shapes()
    fun r(dp: Float) = androidx.compose.foundation.shape.RoundedCornerShape(
        (dp * factor).dp,
    )
    return androidx.compose.material3.Shapes(
        extraSmall = r(4f),
        small = r(8f),
        medium = r(12f),
        large = r(16f),
        extraLarge = r(28f),
    )
}

@Suppress("DEPRECATION")
@Composable
private fun rememberRippleConfiguration(): RippleConfiguration = remember {
    RippleConfiguration(
        rippleAlpha = RippleAlpha(
            hoveredAlpha = 2f * 0.08f,
            focusedAlpha = 2f * 0.10f,
            pressedAlpha = 2f * 0.10f,
            draggedAlpha = 2f * 0.16f,
        ),
    )
}

@Composable
private fun ApplyAppThemeBars(isDark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val activity = view.context.findActivity() ?: return@SideEffect
        @Suppress("DEPRECATION")
        activity.window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        activity.window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isNavigationBarContrastEnforced = false
            activity.window.isStatusBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Preview(name = "浅色", showBackground = true) // i18n: keep(IDE 预览名,不进应用)
@Composable
private fun AVBoxThemeLightPreview() {
    AVBoxTheme(
        config = ThemeConfig(ThemeSource.CUSTOM, ThemeMode.LIGHT, DefaultSeedArgb, PaletteStyle.TonalSpot, pureBlack = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Text("AVBox")
        }
    }
}

@Preview(name = "深色", showBackground = true) // i18n: keep(IDE 预览名,不进应用)
@Composable
private fun AVBoxThemeDarkPreview() {
    AVBoxTheme(
        config = ThemeConfig(ThemeSource.CUSTOM, ThemeMode.DARK, DefaultSeedArgb, PaletteStyle.TonalSpot, pureBlack = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Text("AVBox")
        }
    }
}
