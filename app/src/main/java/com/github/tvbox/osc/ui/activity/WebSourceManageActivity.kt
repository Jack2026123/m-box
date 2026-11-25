package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.components.SheetHostScaffold
import com.github.tvbox.osc.ui.components.WallpaperBackdropPage
import com.github.tvbox.osc.ui.page.WebSourceManageScreen
import com.github.tvbox.osc.ui.theme.AVBoxTheme

/** 3.38 网页源管理页：设置 → 网页源行进来；照 ThemeSettingsActivity 的壳。 */
class WebSourceManageActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, WebSourceManageActivity::class.java))
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
                        WebSourceManageScreen(onNavigateBack = { finish() })
                    }
                }
            }
        }
    }
}
