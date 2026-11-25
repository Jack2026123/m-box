package com.github.tvbox.osc.reader

import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge

class WebComicReaderActivity : BaseActivity() {

    private var token: String? by mutableStateOf(null)
    private val statusBarGuard = ReaderStatusBarGuard(this)

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
                    // Comics share the novel settings blob: themeIndex is the
                    // only field the comic path consumes (it drives the page
                    // background and the letterbox colour), while font size and
                    // line height are ignored — the dialog hides those rows via
                    // novelStyle = false.
                    var settings by remember { mutableStateOf(ReaderStore.novelSettings()) }
                    WebReaderScreen(
                        session = session,
                        startIndex = session.startIndex,
                        loadChapter = { index ->
                            val content = ReaderLoader.loadChapter(session, index)
                            if (content !is ReaderContent.Comic) throw ReaderChapterException("本章不是漫画")
                            if (content.images.isEmpty()) throw ReaderChapterException("本章没有图片")
                            val title = session.episodes.getOrNull(index)?.name?.takeIf { it.isNotBlank() }
                                ?: session.title
                            WebChapter(
                                title,
                                WebReaderHtml.buildComicHtml(
                                    content.images,
                                    "#%06X".format(
                                        0xFFFFFF and NovelThemes.at(settings.themeIndex).background.toInt()
                                    ),
                                ),
                                content.headers,
                            )
                        },
                        readerSettings = settings,
                        onSettingsChange = { updated ->
                            settings = updated
                            ReaderStore.saveNovelSettings(updated)
                        },
                        novelStyle = false,
                        onBack = { finish() },
                        // 1.93：面板里把 Web 阅读器拨关 → 存偏好后用当前话
                        // 重进自带漫画阅读器。落点是该话开头（图号回到 0）。
                        onSwitchReader = { index, toWeb ->
                            if (!toWeb) {
                                ReaderStore.setUseWebReader(false)
                                ReaderStore.saveProgress(session.bookKey, ReaderProgress(index, 0, 0))
                                val newToken = ReaderSessions.put(session.copy(startIndex = index))
                                ComicReaderActivity.start(this@WebComicReaderActivity, newToken)
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
        private const val EXTRA_TOKEN = "web_reader_token"

        fun start(context: Context, token: String) {
            val intent = Intent(context, WebComicReaderActivity::class.java)
            intent.putExtra(EXTRA_TOKEN, token)
            context.startActivity(intent)
        }
    }
}