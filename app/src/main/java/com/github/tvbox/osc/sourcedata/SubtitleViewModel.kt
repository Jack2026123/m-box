package com.github.tvbox.osc.sourcedata

import android.text.TextUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

import com.github.tvbox.osc.api.SubtitleSourceApi
import com.github.tvbox.osc.bean.Subtitle
import com.github.tvbox.osc.bean.SubtitleData
import com.github.tvbox.osc.net.OkGoHelper
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.net.Http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import org.jsoup.Jsoup

import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class SubtitleViewModel : ViewModel() {

    fun interface SubtitleLoader {
        fun loadSubtitle(subtitle: Subtitle)
    }

    val searchResult = SourceChannel<SubtitleData?>()

    fun searchResult(title: String?, page: Int) {
        viewModelScope.launch { searchResultFromApi(title, page) }
    }

    fun getSearchResultSubtitleUrls(subtitle: Subtitle) {
        viewModelScope.launch {
            val url = subtitle.url ?: ""
            if (url.lowercase().endsWith(".zip")) {
                try {
                    val local = SubtitleSourceApi.downloadToLocal(
                        SubtitleSourceApi.Item(0, subtitle.name ?: "", url, ""),
                    )
                    if (local == null) {
                        setSearchListData(null, true, false)
                        return@launch
                    }
                    val one = Subtitle()
                    one.name = subtitle.name
                    one.url = local
                    one.isZip = false
                    setSearchListData(listOf(one), true, false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.e("SubtitleViewModel", e)
                    setSearchListData(null, true, false)
                }
                return@launch
            }
            val (files, error) = fetchSubtitleFiles(subtitle)
            setSearchListData(files, true, error)
        }
    }

    fun getSubtitleUrl(subtitle: Subtitle, subtitleLoader: SubtitleLoader) {
        val url = subtitle.url ?: ""
        if (url.startsWith("http://") || url.startsWith("https://") || File(url).exists()) {
            subtitleLoader.loadSubtitle(subtitle)
            return
        }
        getSubtitleUrlFromAssrt(subtitle, subtitleLoader, null)
    }

    fun pickEpisodeSubtitle(releaseUrl: String?, episodeName: String?, fileNameHint: String?,
                            onPicked: SubtitleLoader?, onFailed: Runnable?) {
        if (TextUtils.isEmpty(releaseUrl) || onPicked == null) {
            if (onFailed != null) onFailed.run()
            return
        }
        viewModelScope.launch {
            val release = Subtitle()
            release.url = releaseUrl
            val (files, error) = fetchSubtitleFiles(release)
            if (error || files == null || files.isEmpty()) {
                if (onFailed != null) onFailed.run()
                return@launch
            }
            val names = ArrayList<String>()
            for (item in files) names.add(item.name ?: "")
            val index = SubtitleFilePicker.pick(names, episodeName, fileNameHint)
            if (index < 0) {
                if (onFailed != null) onFailed.run()
                return@launch
            }
            getSubtitleUrlFromAssrt(files[index], onPicked, onFailed)
        }
    }

    private fun setSearchListData(data: List<Subtitle>?, isNew: Boolean, isZip: Boolean) {
        try {
            val subtitleData = SubtitleData()
            subtitleData.subtitleList = data
            subtitleData.isNew = isNew
            subtitleData.isZip = isZip
            searchResult.postValue(subtitleData)
        } catch (e: Throwable) {
            LOG.e("SubtitleViewModel", e)
            searchResult.postValue(null)
        }
    }

    // 1.46 字幕搜索后端：Assrt 回归 1.45 之前的网页爬取（secure.assrt.net/sub/
    // searchword），搜索结果是详情页容器项（点开走 fetchSubtitleFiles 取
    // secure.assrt.net/download/… 直链）；迅雷搜直链一次返回全部；射手在线不可用。
    private suspend fun searchResultFromApi(title: String?, page: Int) {
        try {
            if (title.isNullOrEmpty()) {
                setSearchListData(ArrayList(), page <= 1, false)
                return
            }
            if (SubtitleSourceApi.isShooter()) {
                setSearchListData(ArrayList(), page <= 1, false)
                return
            }
            if (!SubtitleSourceApi.ready()) {
                setSearchListData(null, page <= 1, false)
                return
            }
            val items = SubtitleSourceApi.search(title, page)
            val data = ArrayList<Subtitle>()
            if (SubtitleSourceApi.isXunlei()) {
                for (item in items) {
                    val one = Subtitle()
                    one.name = item.name
                    one.url = item.url
                    one.isZip = item.isZip
                    data.add(one)
                }
                setSearchListData(data, page <= 1, false)
            } else {
                for (item in items) {
                    val one = Subtitle()
                    one.name = item.name
                    one.url = item.url
                    one.isZip = true
                    data.add(one)
                }
                setSearchListData(data, page <= 1, true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.e("SubtitleViewModel", e)
            setSearchListData(null, page <= 1, false)
        }
    }

    private val regexShooterFileOnclick = Pattern.compile("onthefly\\(\"(\\d+)\",\"(\\d+)\",\"([\\s\\S]*)\"\\)")

    private suspend fun fetchSubtitleFiles(subtitle: Subtitle): Pair<List<Subtitle>?, Boolean> {
        val url = subtitle.url ?: return null to true
        return try {
            val content = Http.get(url)
            withContext(Dispatchers.IO) {
                try {
                    val data = ArrayList<Subtitle>()
                    val doc = Jsoup.parse(content)
                    val items = doc.select("#detail-filelist .waves-effect")
                    if (items.size > 0) {
                        for (item in items) {
                            val onclick = item.attr("onclick")
                            if (TextUtils.isEmpty(onclick)) continue
                            val matcher = regexShooterFileOnclick.matcher(onclick)
                            if (matcher.find()) {
                                val fileName = matcher.group(3)
                                if (!isSupportedSubtitleFile(fileName)) continue
                                val downloadUrl = String.format("https://secure.assrt.net/download/%s/-/%s/%s", matcher.group(1), matcher.group(2), matcher.group(3))
                                val one = Subtitle()
                                val name = item.selectFirst("#filelist-name")
                                one.name = if (name == null) fileName else name.text()
                                one.url = downloadUrl
                                one.isZip = false
                                data.add(one)
                            }
                        }
                        data to false
                    } else {
                        val item = doc.selectFirst(".download a#btn_download")
                        if (item == null) {
                            null to false
                        } else {
                            val href = item.attr("href")
                            if (TextUtils.isEmpty(href) || !isSupportedSubtitleFile(href)) {
                                null to false
                            } else {
                                val downloadUrl = "https://assrt.net" + href
                                val one = Subtitle()
                                val title = href.substring(href.lastIndexOf("/") + 1)
                                one.name = URLDecoder.decode(title)
                                one.url = downloadUrl
                                one.isZip = false
                                data.add(one)
                                data to false
                            }
                        }
                    }
                } catch (th: Throwable) {
                    LOG.e("SubtitleViewModel", th)
                    null to true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.e("SubtitleViewModel", e)
            null to true
        }
    }

    private fun isSupportedSubtitleFile(fileName: String?): Boolean {
        if (fileName.isNullOrEmpty()) return false
        val lower = fileName.lowercase(Locale.ROOT)
        return lower.endsWith(".srt") ||
            lower.endsWith(".ass") ||
            lower.endsWith(".ssa") ||
            lower.endsWith(".vtt") ||
            lower.endsWith(".ttml") ||
            lower.endsWith(".xml") ||
            lower.endsWith(".dfxp") ||
            lower.endsWith(".stl")
    }

    private fun getSubtitleUrlFromAssrt(subtitle: Subtitle, subtitleLoader: SubtitleLoader, onFailed: Runnable?) {
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.54 Safari/537.36"
        val request = Request.Builder()
            .url(requireNotNull(subtitle.url) { "subtitle url missing" })
            .get()
            .addHeader("Referer", "https://secure.assrt.net")
            .addHeader("User-Agent", ua)
            .build()
        val base = OkGoHelper.getDefaultClient()
        val builder = if (base != null) base.newBuilder() else OkHttpClient.Builder().proxySelector(OkGoHelper.proxySelector()).proxyAuthenticator(OkGoHelper.proxyAuthenticator())
        builder.readTimeout(15, TimeUnit.SECONDS)
        builder.writeTimeout(15, TimeUnit.SECONDS)
        builder.connectTimeout(15, TimeUnit.SECONDS)
        builder.followRedirects(false)
        builder.followSslRedirects(false)
        builder.retryOnConnectionFailure(true)
        val client = builder.build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                LOG.e("SubtitleViewModel", e)
                if (onFailed != null) onFailed.run()
            }

            override fun onResponse(call: Call, response: Response) {
                val location = response.header("location")
                if (TextUtils.isEmpty(location)) {
                    if (onFailed != null) onFailed.run()
                    return
                }
                subtitle.url = location
                subtitleLoader.loadSubtitle(subtitle)
            }
        })
    }
}
