package com.fongmi.android.tv.setting;

import android.graphics.Color;

import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.SubtitleView;

import com.fongmi.android.tv.player.subtitle.ExternalFont;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.KV;

public class SubtitleSetting {

    public static final int STYLE_ORIGINAL = 0;
    public static final int STYLE_SYSTEM = 1;
    public static final int STYLE_CUSTOM = 2;
    public static final int SECONDARY_MODE_DEFAULT = -3;
    public static final int SECONDARY_MODE_OFF = -2;
    public static final int SECONDARY_MODE_AUTO = -1;

    public static int getStyleMode() {
        return STYLE_ORIGINAL;
    }

    public static boolean isSystemStyle() {
        return false;
    }

    public static boolean isCustomStyle() {
        return false;
    }

    public static boolean isStyleForced() {
        return false;
    }

    public static float getAppliedScale() {
        return KV.get(HawkConfig.SUBTITLE_EXO_SCALE, 100) / 100f;
    }

    public static boolean isScaleApplied() {
        return KV.get(HawkConfig.SUBTITLE_EXO_SCALE, 100) != 100;
    }

    public static float getPosition() {
        return KV.get(HawkConfig.SUBTITLE_EXO_POSITION, 0.0f);
    }

    public static boolean isPositionSet() {
        return KV.get(HawkConfig.SUBTITLE_EXO_POSITION, 0.0f) != 0.0f;
    }

    public static int getTextColor() {
        return Color.WHITE;
    }

    public static int getBackgroundColor() {
        return Color.TRANSPARENT;
    }

    public static int getEdgeType() {
        return CaptionStyleCompat.EDGE_TYPE_OUTLINE;
    }

    public static int getEdgeColor() {
        return Color.BLACK;
    }

    public static float getEdgeWidth() {
        return CaptionStyleCompat.DEFAULT_EDGE_WIDTH;
    }

    public static float getShadow() {
        return CaptionStyleCompat.DEFAULT_SHADOW_OFFSET;
    }

    public static int getSecondaryMode() {
        return SECONDARY_MODE_DEFAULT;
    }

    public static float getSecondaryPosition() {
        return 10.0f;
    }

    public static boolean isSecondaryPositionSet() {
        return false;
    }

    public static ExternalFont.Item getFont() {
        return null;
    }

    public static CaptionStyleCompat getStyle() {
        return new CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, 0, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null);
    }

    public static void applyStyle(SubtitleView subtitleView) {
        if (subtitleView == null) return;
        subtitleView.reset();
        subtitleView.setStyle(getStyle());
        subtitleView.setApplyEmbeddedStyles(!isStyleForced());
        subtitleView.setApplyEmbeddedFontSizes(true);
        if (isScaleApplied()) subtitleView.setTextSizeScale(getAppliedScale());
        if (isPositionSet()) subtitleView.setBottomPosition(getPosition() / 100.0f);
    }

    public static String getFontFamily() {
        return null;
    }
}
