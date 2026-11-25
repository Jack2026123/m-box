package com.github.tvbox.osc.reader

import android.content.Context
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge

class WebNovelReaderActivity : BaseActivity() {

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
                    val colors = MaterialTheme.colorScheme
                    // Settings are held as state, not a one-shot remember: the
                    // settings panel writes NovelSettings back through
                    // ReaderStore, and the holder below re-derives every
                    // visual input from it, so a font / line-height / theme
                    // change reaches the WebView without leaving the chapter.
                    var settings by remember { mutableStateOf(ReaderStore.novelSettings()) }
                    // Page-turn mode lives outside NovelSettings: it is a
                    // WebView-rendering concern (columns vs. scroll), not a
                    // typography one, and the native reader keeps its own
                    // pageTurnMode. 1.93：Web 里小说只有上下滚动，左右偏好
                    // （paged_ 旧值）不再读，paged 恒为 false。
                    var paged by remember { mutableStateOf(false) }
                    // Defer to the chosen NovelTheme when the reader has one
                    // (it always does — themeIndex defaults to 0), otherwise
                    // fall back to the app surface pair. The native reader
                    // paints body and paper from NovelThemes.at(themeIndex),
                    // so taking the same pair here keeps both readers on the
                    // same page for the same theme selection.
                    val theme = NovelThemes.at(settings.themeIndex)
                    val textHex = remember(settings.themeIndex) {
                        "#%06X".format(0xFFFFFF and theme.text.toInt())
                    }
                    val bgHex = remember(settings.themeIndex) {
                        "#%06X".format(0xFFFFFF and theme.background.toInt())
                    }
                    val accentHex = remember(colors) {
                        "#%06X".format(0xFFFFFF and colors.primary.toArgb())
                    }

                    WebReaderScreen(
                        session = session,
                        startIndex = session.startIndex,
                        // `settings` is a snapshot-state read inside this lambda,
                        // so changing it re-runs the chapter build and the WebView
                        // reloads with the new metrics in place.
                        loadChapter = { index ->
                            val content = ReaderLoader.loadChapter(session, index)
                            if (content !is ReaderContent.Novel) throw ReaderChapterException("本章不是小说")
                            val text = content.inlineText?.takeIf { it.isNotBlank() }
                                ?: ReaderLoader.fetchNovelText(content)
                            val title = session.episodes.getOrNull(index)?.name?.takeIf { it.isNotBlank() }
                                ?: session.title
                            // 1.87：模板 viewport 是 device-width，CSS px 本就是 dp，
                            // 字号与边距直接用 dp 值，不再乘 density（旧写法把字
                            // 放大了 density 倍，13sp 渲出来约 32dp）。
                            val fontSizePx = settings.fontSizeSp.toInt().coerceIn(8, 200)
                            val paddingPx = 20
                            WebChapter(
                                title,
                                WebReaderHtml.buildNovelHtml(
                                    title,
                                    text,
                                    textHex,
                                    bgHex,
                                    fontSizePx,
                                    settings.lineHeightMult,
                                    paddingPx,
                                    accentHex,
                                    paged,
                                ),
                                content.headers,
                            )
                        },
                        readerSettings = settings,
                        onSettingsChange = { updated ->
                            settings = updated
                            ReaderStore.saveNovelSettings(updated)
                        },
                        novelStyle = true,
                        initialPaged = paged,
                        onPagedChange = { on -> paged = on },
                        onBack = { finish() },
                        // 1.93：面板里把 Web 阅读器拨关 → 存偏好后用当前章
                        // 重进自带阅读器。自带先读存过的进度再定章，所以先把
                        // 当前章写进去，落点才是当前章（章内页码回到章首）。
                        onSwitchReader = { index, toWeb ->
                            if (!toWeb) {
                                ReaderStore.setUseWebReader(false)
                                ReaderStore.saveProgress(session.bookKey, ReaderProgress(index, 0, 0))
                                val newToken = ReaderSessions.put(session.copy(startIndex = index))
                                NovelReaderActivity.start(this@WebNovelReaderActivity, newToken)
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
            val intent = Intent(context, WebNovelReaderActivity::class.java)
            intent.putExtra(EXTRA_TOKEN, token)
            context.startActivity(intent)
        }
    }
}
