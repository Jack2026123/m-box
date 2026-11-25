package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.player.PlaybackService
import com.github.tvbox.osc.ui.dialog.LocalSourceSettingsDialog
import com.github.tvbox.osc.api.ai.AiSkillPack
import com.github.tvbox.osc.ui.page.MainScreen
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.AppThemeState
import com.github.tvbox.osc.util.PermissionHelper

private const val PREWARM_ON_RESUME_DELAY_MS = 2000L

class MainActivity : BaseActivity() {

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    // While the reading shelf is the visible home page, BaseActivity's
    // status-bar re-assertions keep the bar hidden (reader rule); the
    // flag is driven by MainScreen (reading mode + HOME tab visible).
    override fun keepStatusBarHidden(): Boolean =
        com.github.tvbox.osc.ui.page.ReadingShelfStatusBar.wantsHidden

    private fun applyStatusBarAppearance() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val light = !AppThemeState.isDark(night)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    override fun onResume() {
        super.onResume()
        applyStatusBarAppearance()
        PlaybackService.prewarm(this, PREWARM_ON_RESUME_DELAY_MS)
    }

    // 1.45：本地源设置对话框的 SAF 文件夹选择结果转发（View 对话框无法在
    // 显示时注册 ActivityResultLauncher，改走经典 startActivityForResult）。
    // 2.51：工坊改走自定义文件夹浏览器，不再用 SAF，AiAssistPanel 的转发已撤掉。
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        LocalSourceSettingsDialog.onActivityResult(requestCode, resultCode, data)
        AiSkillPack.onActivityResult(requestCode, resultCode, data, this)
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        applyStatusBarAppearance()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !PermissionHelper.isStorageGranted(this)) {
            PermissionHelper.requestStorage(this) { _, _ -> }
        }
        PermissionHelper.requestNotificationIfNeeded(this)
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                MainScreen()
            }
        }
    }
}
