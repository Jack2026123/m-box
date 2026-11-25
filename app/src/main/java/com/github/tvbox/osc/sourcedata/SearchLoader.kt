package com.github.tvbox.osc.sourcedata

import android.text.TextUtils
import com.github.catvod.crawler.SpiderDebug
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.net.SearchSettings
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.SpiderReaper
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class SearchLoader(
    private val gson: Gson,
    private val extendCache: ConcurrentHashMap<String, String>,
    private val searchResult: SourceChannel<AbsXml?>,
    private val resultParser: SourceResultParser,
) {

    suspend fun getSearch(sourceKey: String?, wd: String?) {
        getSearch(sourceKey, wd, "")
    }

    suspend fun getSearch(sourceKey: String?, wd: String?, searchToken: String?) {
        getSearch(sourceKey, wd, searchToken, searchResult, 1)
    }

    suspend fun getSearch(sourceKey: String?, wd: String?, searchToken: String?, page: Int) {
        getSearch(sourceKey, wd, searchToken, searchResult, page)
    }

    private suspend fun getSearch(sourceKey: String?, wd: String?, searchToken: String?, result: SourceChannel<AbsXml?>, page: Int) {
        val sourceBean = ApiConfig.get().getSource(sourceKey)
        if (sourceBean == null) {
            resultParser.postEmptySearchResult(result, sourceKey, searchToken)
            return
        }
        val type = sourceBean.type
        if (type == 3) {
            searchFromSpider(sourceBean, wd, result, searchToken, page)
        } else if (type == 0 || type == 1) {
            searchFromApi(sourceBean, wd, result, searchToken, page)
        } else if (type == 4) {
            searchFromExtendedApi(sourceBean, wd, result, searchToken, page)
        } else {
            resultParser.postEmptySearchResult(result, sourceBean.key, searchToken)
        }
    }

    private suspend fun searchFromSpider(sourceBean: SourceBean, wd: String?, result: SourceChannel<AbsXml?>, searchToken: String?, page: Int) {
        try {
            val search = withContext(Dispatchers.IO) {
                val spider = ApiConfig.get().getCSP(sourceBean)
                val quick = SearchSettings.isQuickOnlyEnabled()
                if (page <= 1) {
                    SpiderDebug.logDispatch("search: site=" + (sourceBean.name ?: "") + "(" + (sourceBean.key ?: "") + ") keyword=" + wd + " quick=" + quick + " page=1")
                    val res = SpiderReaper.track(spider) { spider.searchContent(wd, quick) }
                    SpiderDebug.logDispatchResult(res)
                    res
                } else {
                    SpiderDebug.logDispatch("search: site=" + (sourceBean.name ?: "") + "(" + (sourceBean.key ?: "") + ") keyword=" + wd + " quick=" + quick + " page=" + page)
                    val res = SpiderReaper.track(spider) { spider.searchContent(wd, quick, page.toString()) }
                    SpiderDebug.logDispatchResult(res)
                    res
                }
            }
            withContext(Dispatchers.IO) {
                if (!TextUtils.isEmpty(search)) {
                    resultParser.json(result, search, sourceBean.key, searchToken)
                } else {
                    resultParser.json(result, "", sourceBean.key, searchToken)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (th: Throwable) {
            LOG.e("SourceViewModel", th)
            withContext(Dispatchers.IO) { resultParser.json(result, "", sourceBean.key, searchToken) }
        }
    }

    private suspend fun searchFromApi(sourceBean: SourceBean, wd: String?, result: SourceChannel<AbsXml?>, searchToken: String?, page: Int) {
        val type = sourceBean.type

        try {
            val body = SourceHelper.siteGet(sourceBean) {
                params("wd", wd)
                if (type == 1) {
                    params("ac", "detail")
                }
                if (page > 1) {
                    params("pg", page.toString())
                }
            }
            withContext(Dispatchers.IO) {
                if (type == 0) {
                    resultParser.xml(result, body, sourceBean.key, searchToken)
                } else {
                    resultParser.json(result, body, sourceBean.key, searchToken)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            resultParser.postEmptySearchResult(result, sourceBean.key, searchToken)
        }
    }

    private suspend fun searchFromExtendedApi(sourceBean: SourceBean, wd: String?, result: SourceChannel<AbsXml?>, searchToken: String?, page: Int) {

        val (extend, queryWd) = withContext(Dispatchers.IO) {
            var queryWd = wd
            try {
                queryWd = URLEncoder.encode(queryWd, "UTF-8")
            } catch (e: UnsupportedEncodingException) {
                LOG.e("SourceViewModel", e)
            }
            SourceHelper.getFixUrlDirect(extendCache, gson, sourceBean.ext) to queryWd
        }

        try {
            val body = SourceHelper.siteGet(sourceBean) {
                params("wd", queryWd)
                params("ac", "detail")
                params("quick", if (SearchSettings.isQuickOnlyEnabled()) "true" else "false")
                if (extend != null && extend.isNotEmpty()) {
                    params("extend", extend)
                }
                if (page > 1) {
                    params("pg", page.toString())
                }
            }
            withContext(Dispatchers.IO) { resultParser.json(result, body, sourceBean.key, searchToken) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.i("echo-t4 search-onError")
            resultParser.postEmptySearchResult(result, sourceBean.key, searchToken)
        }
    }
}
