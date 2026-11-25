package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.ComposeView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.components.SheetHostScaffold
import com.github.tvbox.osc.ui.components.WallpaperBackdropPage
import com.github.tvbox.osc.ui.page.TmdbConfigScreen
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge

/**
 * Standalone TMDB config activity (logtest43): TMDB moved out of
 * the ConfigManage tabs; opened from the SettingsPage row directly
 * below 配置管理. Same scaffold + wallpaper backdrop as the other
 * settings sub-pages, so the wallpaper stays visible behind it.
 */
class TmdbConfigActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, TmdbConfigActivity::class.java))
        }
    }

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
                    WallpaperBackdropPage {
                        TmdbConfigScreen(onNavigateBack = { finish() })
                    }
                }
            }
        }
    }
}
