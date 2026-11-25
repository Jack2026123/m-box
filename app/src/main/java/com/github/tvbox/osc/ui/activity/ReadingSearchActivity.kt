package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.ComposeView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.components.SheetHostScaffold
import com.github.tvbox.osc.ui.components.WallpaperBackdrop
import com.github.tvbox.osc.ui.page.ReadingSearchScreen
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge

class ReadingSearchActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ReadingSearchActivity::class.java))
        }
    }

    override fun shouldRefreshAutoSize(): Boolean = true

    // No hideSysBar() override: BaseActivity's default immersive
    // behaviour applies (system bars hidden, like the readers).
    override fun init() {
        enableTransparentEdgeToEdge()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
                    WallpaperBackdrop()
                    ReadingSearchScreen(onBack = { finish() })
                }
            }
        }
    }
}
