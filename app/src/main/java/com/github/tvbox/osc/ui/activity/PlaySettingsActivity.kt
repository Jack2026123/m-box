package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.components.WallpaperBackdropPage
import com.github.tvbox.osc.ui.page.PlaySettingsScreen
import com.github.tvbox.osc.ui.theme.AVBoxTheme

class PlaySettingsActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, PlaySettingsActivity::class.java))
        }
    }

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    WallpaperBackdropPage {
                        PlaySettingsScreen(onNavigateBack = { finish() })
                    }
                }
            }
        }
    }
}
