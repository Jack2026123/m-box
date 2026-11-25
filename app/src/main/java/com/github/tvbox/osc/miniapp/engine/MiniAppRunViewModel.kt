package com.github.tvbox.osc.miniapp.engine

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class MiniAppRunPage(
    val rule: MiniAppRule,
    val items: List<MiniAppListItem> = emptyList(),
    val pageTitle: String = "",
    val page: Int = 1,
    val loading: Boolean = true,
    val error: String? = null,
    val isSearch: Boolean = false,
    val keyword: String = "",
    val level: Int = 1,
)

data class MiniAppRunState(
    val stack: List<MiniAppRunPage> = emptyList(),
    val searchText: String = "",
)

data class MiniAppPlayEvent(
    val url: String,
    val headers: Map<String, String>,
    val title: String,
)

data class MiniAppSniffEvent(
    val pageUrl: String,
    val headers: Map<String, String>,
    val title: String,
)

class MiniAppRunViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = MiniAppEngine(application.applicationContext)

    private val _state = MutableStateFlow(MiniAppRunState())
    val state: StateFlow<MiniAppRunState> = _state

    private val _playEvents = MutableSharedFlow<MiniAppPlayEvent>()
    val playEvents: SharedFlow<MiniAppPlayEvent> = _playEvents
    private val _sniffEvents = MutableSharedFlow<MiniAppSniffEvent>()
    val sniffEvents: SharedFlow<MiniAppSniffEvent> = _sniffEvents

    init {
        engine.host().onBackRequested = { back() }
        engine.host().onRefreshRequested = { reloadCurrent() }
    }

    fun reloadCurrent() {
        val top = current() ?: return
        if (top.isSearch) {
            search()
        } else {
            loadHomePage(top.rule, top.page, top.level, true)
        }
    }

    override fun onCleared() {
        engine.destroyTasks()
        super.onCleared()
    }

    private fun current(): MiniAppRunPage? = _state.value.stack.lastOrNull()

    private fun updateTop(transform: (MiniAppRunPage) -> MiniAppRunPage) {
        val stack = _state.value.stack
        if (stack.isEmpty()) return
        _state.value = _state.value.copy(stack = stack.dropLast(1) + transform(stack.last()))
    }

    fun open(ruleJson: String, fallbackName: String) {
        val rule = MiniAppRule.fromJson(ruleJson)
        if (rule == null) {
            _state.value = MiniAppRunState(
                stack = listOf(
                    MiniAppRunPage(
                        MiniAppRule(),
                        loading = false,
                        error = "规则解析失败",
                    ),
                ),
            )
            return
        }
        if (rule.title.isEmpty()) rule.title = fallbackName
        val page = MiniAppRunPage(rule = rule, pageTitle = rule.title, loading = true)
        _state.value = MiniAppRunState(stack = listOf(page))
        loadHomePage(rule, 1, 1, true)
    }

    private fun loadHomePage(rule: MiniAppRule, page: Int, level: Int, replaceTop: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val newPage = try {
                val result = engine.loadHome(rule, page)
                when (result) {
                    is MiniAppEngine.PageResult.ListPage -> MiniAppRunPage(
                        rule = rule,
                        items = result.items,
                        pageTitle = result.pageTitle.ifEmpty { rule.title },
                        page = page,
                        loading = false,
                        level = level,
                    )
                    is MiniAppEngine.PageResult.Error -> MiniAppRunPage(
                        rule = rule,
                        pageTitle = rule.title,
                        page = page,
                        loading = false,
                        error = result.message,
                        level = level,
                    )
                }
            } catch (e: Throwable) {
                MiniAppRunPage(
                    rule = rule,
                    pageTitle = rule.title,
                    page = page,
                    loading = false,
                    error = e.message ?: "加载失败",
                    level = level,
                )
            }
            if (replaceTop) {
                updateTop { newPage }
            } else {
                val stack = _state.value.stack
                _state.value = _state.value.copy(stack = stack + newPage)
            }
        }
    }

    fun onSearchTextChange(text: String) {
        _state.value = _state.value.copy(searchText = text)
    }

    fun search() {
        val page = current() ?: return
        val keyword = _state.value.searchText.trim()
        if (keyword.isEmpty()) return
        if (page.rule.searchUrl.isEmpty()) return
        val loadingPage = MiniAppRunPage(
            rule = page.rule,
            pageTitle = page.rule.title,
            page = 1,
            loading = true,
            isSearch = true,
            keyword = keyword,
            level = page.level,
        )
        _state.value = _state.value.copy(stack = _state.value.stack + loadingPage)
        viewModelScope.launch(Dispatchers.IO) {
            val done = try {
                val result = engine.search(page.rule, keyword, 1)
                when (result) {
                    is MiniAppEngine.PageResult.ListPage -> loadingPage.copy(
                        items = result.items,
                        pageTitle = result.pageTitle.ifEmpty { page.rule.title },
                        loading = false,
                    )
                    is MiniAppEngine.PageResult.Error -> loadingPage.copy(
                        loading = false,
                        error = result.message,
                    )
                }
            } catch (e: Throwable) {
                loadingPage.copy(loading = false, error = e.message ?: "搜索失败")
            }
            updateTop { done }
        }
    }

    fun openItem(item: MiniAppListItem) {
        val page = current() ?: return
        if (engine.isHeaderFilter(item)) {
            val newRule = engine.applyHeaderFilter(page.rule, item, -1)
            if (newRule != null) {
                updateTop { it.copy(loading = true, error = null) }
                loadHomePage(newRule, 1, page.level, true)
                return
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val action = engine.actionForItem(page.rule, item, page.level)) {
                    is MiniAppEngine.ItemAction.OpenList -> {
                        val loadingPage = MiniAppRunPage(
                            rule = action.rule,
                            pageTitle = action.rule.title,
                            loading = true,
                            level = page.level + 1,
                        )
                        _state.value = _state.value.copy(stack = _state.value.stack + loadingPage)
                        val result = engine.loadHome(action.rule, 1)
                        val done = when (result) {
                            is MiniAppEngine.PageResult.ListPage -> loadingPage.copy(
                                items = result.items,
                                pageTitle = result.pageTitle.ifEmpty { action.rule.title },
                                loading = false,
                            )
                            is MiniAppEngine.PageResult.Error -> loadingPage.copy(
                                loading = false,
                                error = result.message,
                            )
                        }
                        updateTop { done }
                    }
                    is MiniAppEngine.ItemAction.OpenPage -> {
                        val routed = engine.routePage(page.rule, action.pageUrl, action.item)
                        if (routed == null) {
                            updateTop { it.copy(error = "找不到页面：" + action.pageUrl) }
                        } else {
                            val loadingPage = MiniAppRunPage(
                                rule = routed,
                                pageTitle = routed.title,
                                loading = true,
                                level = page.level,
                            )
                            _state.value = _state.value.copy(stack = _state.value.stack + loadingPage)
                            val result = engine.loadHome(routed, 1)
                            val done = when (result) {
                                is MiniAppEngine.PageResult.ListPage -> loadingPage.copy(
                                    items = result.items,
                                    pageTitle = result.pageTitle.ifEmpty { routed.title },
                                    loading = false,
                                )
                                is MiniAppEngine.PageResult.Error -> loadingPage.copy(
                                    loading = false,
                                    error = result.message,
                                )
                            }
                            updateTop { done }
                        }
                    }
                    is MiniAppEngine.ItemAction.OpenItems -> {
                        val newPage = MiniAppRunPage(
                            rule = action.rule,
                            items = action.items,
                            pageTitle = action.pageTitle.ifEmpty { action.rule.title },
                            loading = false,
                            level = page.level + 1,
                        )
                        _state.value = _state.value.copy(stack = _state.value.stack + newPage)
                    }
                    is MiniAppEngine.ItemAction.Play -> {
                        val playUrl = action.url
                        // 防御：播放地址仍是虚拟页则刷新，避免黑屏
                        if (playUrl.startsWith("hiker://empty") && !playUrl.contains("@rule=")) {
                            reloadCurrent()
                        } else if (playUrl.startsWith("toast://")) {
                            updateTop { it.copy(error = playUrl.removePrefix("toast://")) }
                        } else {
                            _playEvents.emit(MiniAppPlayEvent(playUrl, action.headers, item.title))
                        }
                    }
                    is MiniAppEngine.ItemAction.Refresh -> {
                        reloadCurrent()
                    }
                    is MiniAppEngine.ItemAction.SniffPlay -> {
                        updateTop { it.copy(error = null, loading = false) }
                        _sniffEvents.emit(
                            MiniAppSniffEvent(action.pageUrl, action.headers, item.title),
                        )
                    }
                    is MiniAppEngine.ItemAction.Toast -> {
                        updateTop { it.copy(error = action.message) }
                    }
                    is MiniAppEngine.ItemAction.Nothing -> {
                        updateTop { it.copy(error = action.reason) }
                    }
                }
            } catch (e: Throwable) {
                updateTop { it.copy(loading = false, error = e.message ?: "打开失败") }
            }
        }
    }

    fun onFilterChipClick(item: MiniAppListItem, tabIndex: Int) {
        val page = current() ?: return
        if (engine.isHeaderFilter(item)) {
            val newRule = engine.applyHeaderFilter(page.rule, item, tabIndex) ?: return
            updateTop { it.copy(loading = true, error = null) }
            loadHomePage(newRule, 1, page.level, true)
        } else {
            openItem(item)
        }
    }

    fun nextPage() {
        val page = current() ?: return
        if (page.loading) return
        val next = page.page + 1
        updateTop { it.copy(loading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val done = try {
                val result = if (page.isSearch) {
                    engine.search(page.rule, page.keyword, next)
                } else {
                    engine.loadHome(page.rule, next)
                }
                when (result) {
                    is MiniAppEngine.PageResult.ListPage -> page.copy(
                        items = result.items,
                        pageTitle = result.pageTitle.ifEmpty { page.rule.title },
                        page = next,
                        loading = false,
                        error = null,
                    )
                    is MiniAppEngine.PageResult.Error -> page.copy(
                        loading = false,
                        error = result.message,
                    )
                }
            } catch (e: Throwable) {
                page.copy(loading = false, error = e.message ?: "加载失败")
            }
            updateTop { done }
        }
    }

    fun prevPage() {
        val page = current() ?: return
        if (page.loading || page.page <= 1) return
        val prev = page.page - 1
        updateTop { it.copy(loading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val done = try {
                val result = if (page.isSearch) {
                    engine.search(page.rule, page.keyword, prev)
                } else {
                    engine.loadHome(page.rule, prev)
                }
                when (result) {
                    is MiniAppEngine.PageResult.ListPage -> page.copy(
                        items = result.items,
                        pageTitle = result.pageTitle.ifEmpty { page.rule.title },
                        page = prev,
                        loading = false,
                        error = null,
                    )
                    is MiniAppEngine.PageResult.Error -> page.copy(
                        loading = false,
                        error = result.message,
                    )
                }
            } catch (e: Throwable) {
                page.copy(loading = false, error = e.message ?: "加载失败")
            }
            updateTop { done }
        }
    }

    fun retry() {
        val page = current() ?: return
        if (page.loading) return
        updateTop { it.copy(loading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val done = try {
                val result = if (page.isSearch) {
                    engine.search(page.rule, page.keyword, page.page)
                } else {
                    engine.loadHome(page.rule, page.page)
                }
                when (result) {
                    is MiniAppEngine.PageResult.ListPage -> page.copy(
                        items = result.items,
                        pageTitle = result.pageTitle.ifEmpty { page.rule.title },
                        page = page.page,
                        loading = false,
                        error = null,
                    )
                    is MiniAppEngine.PageResult.Error -> page.copy(
                        loading = false,
                        error = result.message,
                    )
                }
            } catch (e: Throwable) {
                page.copy(loading = false, error = e.message ?: "加载失败")
            }
            updateTop { done }
        }
    }

    fun back(): Boolean {
        val stack = _state.value.stack
        if (stack.size <= 1) return false
        _state.value = _state.value.copy(stack = stack.dropLast(1))
        return true
    }
}
