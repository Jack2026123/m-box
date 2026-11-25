package com.github.tvbox.osc.util

/**
 * The app-wide "screen orientation" preference (Preference settings ->
 * Language & layout). Three modes:
 *
 * - [FOLLOW_SYSTEM]: the app does not lock anything; the device's own
 *   auto-rotate decides, and layouts follow the actual orientation
 *   (portrait = phone form, landscape = TV form).
 * - [PORTRAIT]: every base screen is locked to the portrait family.
 * - [LANDSCAPE]: every base screen is locked to the landscape family.
 *
 * Playback keeps its own word inside its pages: Detail/Live read this
 * policy back through BaseActivity.orientationPolicyValue() when
 * entering/leaving fullscreen, and their in-fullscreen video-driven
 * orientation overrides are unaffected.
 */
object OrientationSetting {

    const val FOLLOW_SYSTEM = 0
    const val PORTRAIT = 1
    const val LANDSCAPE = 2

    fun mode(): Int = KV.get(HawkConfig.SCREEN_ORIENTATION, FOLLOW_SYSTEM)
}
