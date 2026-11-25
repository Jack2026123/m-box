package com.github.tvbox.osc.ui.components

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import com.github.tvbox.osc.R
import com.github.tvbox.osc.ui.isLandscapeOrientation
import com.github.tvbox.osc.ui.theme.ControlThemeStore
import com.github.tvbox.osc.ui.theme.EffectsState
import com.github.tvbox.osc.ui.theme.cardContainer
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

private val RowLeadingIconSize = 24.dp

private val RowLeadingIconGap = 16.dp

private val PasswordTransformation = PasswordVisualTransformation()

private val TestButtonShape = RoundedCornerShape(50)

private val TestButtonContentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)

private val TextFieldShape = RoundedCornerShape(22.dp)

private val TextFieldHeight = 40.dp

private val TextFieldIconSize = 20.dp

enum class SettingsCardPosition {
    SINGLE,
    FIRST,
    MIDDLE,
    LAST,
}

@Composable
private fun shapeFor(position: SettingsCardPosition): Shape {
    val outer = appCardRadius(32.dp)
    val inner = appCardRadius(4.dp)
    return when (position) {
        SettingsCardPosition.SINGLE -> RoundedCornerShape(outer)
        SettingsCardPosition.FIRST -> RoundedCornerShape(topStart = outer, topEnd = outer, bottomEnd = inner, bottomStart = inner)
        SettingsCardPosition.MIDDLE -> RoundedCornerShape(inner)
        SettingsCardPosition.LAST -> RoundedCornerShape(topStart = inner, topEnd = inner, bottomEnd = outer, bottomStart = outer)
    }
}

@Composable
fun SettingsCard(
    position: SettingsCardPosition,
    modifier: Modifier = Modifier,
    color: Color? = null,
    shape: Shape? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val resolvedShape = shape ?: shapeFor(position)
    // 3.17：设置卡跟胶囊主题走（用户 2026-11-15 14:32 定，推翻 2026-10-24
    // “设置各项一律扁平”老口径）：总开关开时容器底 = 胶囊组底色 0.15 透明度
    // + 1dp 描边 + 顶部亮面（与 controlButtonModifier 同款）；关时回原版扁平。
    // 显式传了 color 的调用点（内容侧源选择器等）一律不动。logtest43:
    // the default card color follows the 透明背景 slider while a
    // wallpaper is linked (translucent per value, still flat);
    // an explicitly passed color is left untouched. logtest49
    // frosted unification: with a wallpaper linked and the 毛玻璃栏
    // level above 0, cards in a WallpaperBackdropPage blur the
    // recorded wallpaper behind them exactly like the home frosted
    // bars (same 24dp * t radius, same surfaceContainer tint),
    // instead of the flat bright surfaceBright veil. Without a
    // backdrop (main tabs), below API 31, or at frosted level 0,
    // the flat color stands unchanged.
    val shadowed = modifier.fillMaxWidth()
    val defaultCardColor = wallpaperAwareContainerColor(MaterialTheme.colorScheme.cardContainer)
    // 3.17 胶囊主题：见上面注释；显式 color 的调用点不动。
    val capsuleOn = color == null && !ControlThemeStore.shouldDrawOriginalStyle()
    val capBase = capsuleGroupBaseColor()
    val capBorder = capsuleGroupAccentColor().copy(alpha = 0.35f)
    val capGloss = capsuleGroupWaterGloss()
    val capSheen = if (capsuleOn && capGloss > 0) {
        Modifier.waterGlossSheen(resolvedShape, capGloss / 200f)
    } else {
        Modifier
    }
    val wallpaperBackdrop = LocalWallpaperBackdrop.current
    val hasWallpaper = rememberHasWallpaper()
    val frostedLevel = EffectsState.config.frostedLevel
    val frosted = color == null && hasWallpaper && wallpaperBackdrop != null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && frostedLevel > 0
    // logtest50 全局透明 master on: every card fully transparent
    // (wallpaper original out), overriding the frosted veil below.
    val allTransparent = color == null && hasWallpaper && EffectsState.config.globalTransparent
    if (allTransparent) {
        Surface(
            modifier = shadowed,
            shape = resolvedShape,
            color = Color.Transparent,
        ) {
            Column(content = content)
        }
    } else if (frosted) {
        val t = frostedLevel / 100f
        val tint = if (capsuleOn) capBase.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.28f + 0.34f * t)
        val density = LocalDensity.current
        Surface(
            modifier = shadowed.drawBackdrop(
                backdrop = wallpaperBackdrop,
                shape = { resolvedShape },
                effects = { blur(with(density) { (24.dp * t).toPx() }) },
                highlight = { Highlight.Default.copy(alpha = 0f) },
                shadow = { Shadow.Default.copy(color = Color.Transparent) },
                onDrawSurface = { drawRect(tint) },
            ).then(capSheen),
            shape = resolvedShape,
            color = Color.Transparent,
            border = if (capsuleOn) BorderStroke(1.dp, capBorder) else null,
        ) {
            Column(content = content)
        }
    } else {
        Surface(
            modifier = shadowed.then(capSheen),
            shape = resolvedShape,
            color = if (capsuleOn) capBase.copy(alpha = 0.15f) else color ?: defaultCardColor,
            border = if (capsuleOn) BorderStroke(1.dp, capBorder) else null,
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun SettingsGroup(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    valueText: String? = null,
    valueSingleLine: Boolean = false,
    valueWeight: Float? = null,
    valueProgress: Float? = null,
    enabled: Boolean = true,
    iconRes: Int? = null,
    leadingIconRes: Int? = null,
    emojiIcon: String? = null,
    onClick: (() -> Unit)? = null,
    onIconDoubleClick: (() -> Unit)? = null,
) {
    var rowFocused by remember { mutableStateOf(false) }
    val landscape = isLandscapeOrientation()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .onFocusChanged { rowFocused = it.isFocused }
            .then(
                if (landscape && rowFocused) {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                } else {
                    Modifier
                }
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIconRes?.let { RowLeadingIcon(it, enabled) }
        if (emojiIcon != null) {
            SettingsEmojiBadge(emojiIcon, title)
            Spacer(Modifier.width(16.dp))
        } else if (iconRes != null) {
            if (onIconDoubleClick != null) {
                Box(modifier = Modifier.combinedClickable(onClick = { onClick?.invoke() }, onDoubleClick = onIconDoubleClick)) {
                    SettingsIconBadge(iconRes, title)
                }
            } else {
                SettingsIconBadge(iconRes, title)
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            RowTitle(title = title, enabled = enabled)
            if (subtitle != null) {
                RowSubtitle(text = subtitle, enabled = enabled)
            }
        }
        if (valueText != null) {
            RowValue(
                text = valueText,
                enabled = enabled,
                modifier = if (valueWeight != null) Modifier.weight(valueWeight) else Modifier,
                maxLines = if (valueSingleLine) 1 else Int.MAX_VALUE,
                overflow = if (valueSingleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
                textAlign = if (valueWeight != null) TextAlign.End else null,
            )
        }
        if (valueProgress != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { valueProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.5.dp,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDownward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (onClick != null) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsIconBadge(@DrawableRes iconRes: Int, contentDescription: String? = null) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(MaterialShapes.Cookie7Sided.toShape())
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(22.dp),
        )
    }
}

// Emoji 版图标徽：跟 SettingsIconBadge 同款圆角底，只把 drawable 换成
// 一个 emoji 字符（自带颜色，不上 tint）。心情便签用 ✍️（2.69）。
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsEmojiBadge(emoji: String, contentDescription: String? = null) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(MaterialShapes.Cookie7Sided.toShape())
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emoji,
            fontSize = 22.sp,
        )
    }
}

@Composable
fun SettingsSliderRow(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    valueText: String? = null,
    leadingIconRes: Int? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIconRes?.let { RowLeadingIcon(it, enabled = true) }
            RowTitle(title = title, enabled = true, modifier = Modifier.weight(1f))
            if (valueText != null) {
                Spacer(Modifier.width(16.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        text = valueText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        )
    }
}

@Composable
fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        thumbContent = if (checked) {
            {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            }
        } else null,
    )
}

@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    valueText: String? = null,
    enabled: Boolean = true,
    leadingIconRes: Int? = null,
    compact: Boolean = false,
) {
    var rowFocused by remember { mutableStateOf(false) }
    val landscape = isLandscapeOrientation()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 48.dp else 64.dp)
            .onFocusChanged { rowFocused = it.isFocused }
            .then(
                if (landscape && rowFocused) {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                } else {
                    Modifier
                }
            )
            .then(
                if (onCheckedChange != null) {
                    Modifier.clickable(enabled = enabled, onClick = { onCheckedChange(!checked) })
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIconRes?.let { RowLeadingIcon(it, enabled) }
        Column(modifier = Modifier.weight(1f)) {
            RowTitle(title = title, enabled = enabled)
            if (subtitle != null) {
                RowSubtitle(text = subtitle, enabled = enabled)
            }
        }
        if (valueText != null) {
            RowValue(text = valueText, enabled = enabled, modifier = Modifier.padding(end = 8.dp))
        }
        SettingsSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsOptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    var rowFocused by remember { mutableStateOf(false) }
    val landscape = isLandscapeOrientation()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .onFocusChanged { rowFocused = it.isFocused }
            .then(
                if (landscape && rowFocused) {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
internal fun RowLeadingIcon(@DrawableRes iconRes: Int, enabled: Boolean) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
        modifier = Modifier.size(RowLeadingIconSize),
    )
    Spacer(Modifier.width(RowLeadingIconGap))
}

@Composable
private fun RowTitle(title: String, enabled: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        },
        modifier = modifier,
    )
}

@Composable
private fun RowSubtitle(text: String, enabled: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = text,
        // 悬浮立体: subtitles are engraved into the plate (see
        // fxEngravedTextStyle); a no-op at strength 0.
        style = fxEngravedTextStyle(MaterialTheme.typography.bodySmall),
        color = if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        },
        modifier = modifier.padding(top = 4.dp),
    )
}

@Composable
private fun RowValue(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        },
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
    )
}

@Composable
fun SettingsTextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    errorText: String? = null,
    enabled: Boolean = true,
    masked: Boolean = false,
    clearable: Boolean = false,
) {
    var revealed by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )
        // 3.17：输入框随卡片跟胶囊主题走（22dp 圆角、文字/光标色不动）；全局透明时仍全透。
        val capsuleInput = !ControlThemeStore.shouldDrawOriginalStyle() &&
            !(rememberHasWallpaper() && EffectsState.config.globalTransparent)
        Surface(
            shape = TextFieldShape,
            // logtest43: input box follows the 透明背景 slider like
            // other row-level surfaces on settings pages.
            color = if (capsuleInput) capsuleGroupBaseColor().copy(alpha = 0.15f) else wallpaperAwareContainerColor(MaterialTheme.colorScheme.surfaceContainer),
            border = if (capsuleInput) BorderStroke(1.dp, capsuleGroupAccentColor().copy(alpha = 0.35f)) else null,
            modifier = Modifier.fillMaxWidth().then(
                if (capsuleInput && capsuleGroupWaterGloss() > 0) {
                    Modifier.waterGlossSheen(TextFieldShape, capsuleGroupWaterGloss() / 200f)
                } else {
                    Modifier
                },
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TextFieldHeight)
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = if (masked && !revealed) PasswordTransformation else VisualTransformation.None,
                    decorationBox = { inner ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (value.isEmpty() && placeholder != null) {
                                Text(
                                    text = placeholder,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
                if (masked) {
                    TextFieldIcon(
                        imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null,
                        enabled = enabled,
                        onClick = { revealed = !revealed },
                    )
                } else if (clearable && value.isNotEmpty()) {
                    TextFieldIcon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.common_clear),
                        enabled = enabled,
                        onClick = { onValueChange("") },
                    )
                }
            }
        }
        if (errorText != null) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun TextFieldIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(6.dp)
            .size(TextFieldIconSize),
    )
}

@Composable
fun SettingsTestButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && !loading,
        shape = TestButtonShape,
        contentPadding = TestButtonContentPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}
