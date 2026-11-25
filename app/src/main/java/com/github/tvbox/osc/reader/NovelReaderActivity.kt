package com.github.tvbox.osc.reader

import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge

class NovelReaderActivity : BaseActivity() {

    private var token: String? by mutableStateOf(null)

    private val statusBarGuard = ReaderStatusBarGuard(this)

    // The reader is immersive: never let BaseActivity's bar policy
    // re-show the status bar on resume / focus return, and let the
    // guard take any transient appearance away again within 3 s.
    override fun keepStatusBarHidden(): Boolean = true

    override fun onResume() {
        super.onResume()
        statusBarGuard.start()
    }

    override fun onPause() {
        statusBarGuard.stop()
        super.onPause()
    }

    override fun init() {
        // Entering/leaving the reader fades instead of hard-cutting
        // from the detail page (paired with the finish() override).
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        enableTransparentEdgeToEdge()
        token = intent.getStringExtra(EXTRA_TOKEN)
        val currentToken = token
        findViewById<ComposeView>(com.github.tvbox.osc.R.id.compose_view).setContent {
            AVBoxTheme {
                val session = ReaderSessions.get(currentToken)
                if (session == null) {
                    finish()
                } else {
                    NovelReaderScreen(
                        session = session,
                        onBack = { finish() },
                        // 1.93：设置弹框里把 Web 阅读器拨开 → 存偏好后用
                        // 当前章重进 Web 阅读器（Web 用会话起章，不读进度，
                        // 所以起章就是当前章）。
                        onSwitchReader = { index, toWeb ->
                            if (toWeb) {
                                ReaderStore.setUseWebReader(true)
                                val newToken = ReaderSessions.put(session.copy(startIndex = index))
                                WebNovelReaderActivity.start(this@NovelReaderActivity, newToken)
                                finish()
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        token = intent.getStringExtra(EXTRA_TOKEN)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        statusBarGuard.restore()
        ReaderSessions.remove(token)
        super.onDestroy()
    }

    companion object {

        private const val EXTRA_TOKEN = "reader_token"

        fun start(context: Context, token: String) {
            val intent = Intent(context, NovelReaderActivity::class.java)
            intent.putExtra(EXTRA_TOKEN, token)
            context.startActivity(intent)
        }
    }
}
