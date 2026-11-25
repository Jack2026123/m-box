package com.github.tvbox.osc.api.ai

object SiteStructureAudit {

    data class Item(
        val index: Int,
        val name: String,
        val ok: Boolean,
        val detail: String,
        val hint: String = "",
    ) {
        fun render(): String {
            val mark = if (ok) "[OK]" else "[缺]"
            val base = CJK[index - 1] + " " + pad(name) + " " + mark + " " + detail
            return if (ok || hint.isEmpty()) base else base + "\n      -> " + hint
        }
    }

    private val CJK = listOf("①", "②", "③", "④", "⑤", "⑥", "⑦")

    private fun pad(s: String): String {
        val width = s.sumOf { if (it.code > 0x2E80) 2 else 1 }
        val need = 10 - width
        return if (need <= 0) s else s + " ".repeat(need)
    }

    fun audit(
        homeHtml: String,
        listClass: String,
        listClassHits: Int,
        categoryRoute: String,
        pageRoute: String,
        filterGroups: List<Pair<String, List<String>>>,
        detailHtml: String,
        playRoute: String,
        playPageHtml: String,
    ): String {
        val items = ArrayList<Item>(7)

        items.add(
            if (listClass.isNotEmpty() && listClassHits > 0) {
                Item(1, "首页列表", true, "." + listClass + " 命中 " + listClassHits + " 个")
            } else {
                val g = guessListClass(homeHtml)
                if (g != null) {
                    Item(1, "首页列表", true, "推断 ." + g.first + "（" + g.second + " 个），需自验")
                } else {
                    Item(
                        1, "首页列表", false, "未找到影片卡片",
                        "用 fetch_url 抓首页，找出包住每个影片卡片的重复 class，" +
                            "形如 <div class=\"xxx\"><a href=\"/vod/123.html\">，写进 soup.select()",
                    )
                }
            },
        )

        items.add(
            if (categoryRoute.isNotEmpty()) {
                Item(2, "分类路由", true, categoryRoute)
            } else {
                Item(
                    2, "分类路由", false, "未找到分类链接",
                    "抓首页导航栏，找「电影/电视剧/动漫/综艺」的真实 href，" +
                        "数字换 {tid}，页码换 {page}",
                )
            },
        )

        items.add(
            if (filterGroups.isNotEmpty()) {
                val d = filterGroups.take(4).joinToString("|") { it.first + ":" + it.second.size + "项" }
                Item(3, "筛选选项", true, filterGroups.size.toString() + " 组（" + d + "）")
            } else {
                Item(
                    3, "筛选选项", false, "分类页无筛选栏",
                    "该站可能没筛选。class 里不要带 filter 字段，extend 参数忽略。" +
                        "不要编造筛选项",
                )
            },
        )

        val df = checkDetailFields(detailHtml)
        items.add(
            if (df.first) {
                Item(4, "详情字段", true, df.second)
            } else {
                Item(
                    4, "详情字段", false, df.second,
                    "抓一个真实详情页，确认 <h1> 标题与封面 img 的真实 class；" +
                        "vod_id 从详情 URL 正则取数字，不要从页面文字取",
                )
            },
        )

        val pl = parsePlayList(detailHtml)
        items.add(
            if (pl.first.isNotEmpty()) {
                Item(5, "播放线路", true, pl.first.joinToString(", "))
            } else {
                Item(
                    5, "播放线路", false, "详情页未找到线路",
                    "线路名一般在 .play-title / .nav-tabs / .module-tab-item 里。" +
                        "vod_play_from 用 ${'$'}${'$'}${'$'} 分隔",
                )
            },
        )
        items.add(
            if (pl.second > 0) {
                Item(6, "播放集数", true, pl.second.toString() + " 集，样例 " + pl.third)
            } else {
                Item(
                    6, "播放集数", false, "未找到剧集列表",
                    "剧集链接形如 <a href=\"/vod/play/123-1-1.html\">第1集</a>。" +
                        "拼成 第1集${'$'}地址#第2集${'$'}地址（# 分集，${'$'} 分名与地址）",
                )
            },
        )

        val direct = findDirectMedia(playPageHtml)
        items.add(
            if (direct.isNotEmpty()) {
                Item(7, "播放直链", true, direct.size.toString() + " 条 样例 " + direct.first().take(60))
            } else {
                val hasIframe = Regex("<iframe[^>]+src=[\"']", RegexOption.IGNORE_CASE)
                    .containsMatchIn(playPageHtml)
                Item(
                    7, "播放直链", false,
                    if (hasIframe) "播放页为 iframe 解析器页" else "播放页无直链",
                    if (hasIframe) {
                        "playerContent 里再请求 iframe 的 src，从结果正则抠 m3u8；" +
                            "抠不到就 return {\"parse\":1,\"url\":iframeUrl}"
                    } else {
                        "playerContent 返回 {\"parse\":1,\"url\":播放页地址}，交 App 内置解析"
                    },
                )
            },
        )

        val okCount = items.count { it.ok }
        val sb = StringBuilder()
        sb.append("【结构核对 ").append(okCount).append("/").append(items.size).append("】\n")
        for (it in items) sb.append(it.render()).append("\n")
        if (okCount < items.size) {
            sb.append("\n先补齐标[缺]的项再写源：")
            sb.append(items.filter { !it.ok }.map { it.name }.joinToString("、"))
            sb.append("。禁止对这些项写\"未知\"或凭猜测填选择器。\n")
        } else {
            sb.append("\n七项齐全，按上面真实值写源，一轮即可通过。\n")
        }
        return sb.toString()
    }

    private fun guessListClass(html: String): Pair<String, Int>? {
        val common = listOf(
            "module-poster-item", "module-card-item", "stui-vodlist__box",
            "vodlist__thumb", "public-list-box", "thumb-txt-cor",
            "poster-item", "card-item", "video-item", "list-item",
        )
        for (c in common) {
            val n = Regex("class=\"[^\"]*" + Regex.escape(c) + "[^\"]*\"").findAll(html).count()
            if (n >= 3) return c to n
        }
        val blockRe = Regex(
            "<(li|div)[^>]+class=\"([^\"]{2,60})\"[^>]*>[\\s\\S]{0,600}?href=[\"'][^\"']*(?:/vod/|/detail|/movie/|vodss-)[^\"']*[\"']",
            RegexOption.IGNORE_CASE,
        )
        val counter = HashMap<String, Int>()
        for (m in blockRe.findAll(html)) {
            val cls = m.groupValues[2].trim().split(" ").firstOrNull().orEmpty()
            if (cls.isEmpty() || cls.length > 40) continue
            counter[cls] = (counter[cls] ?: 0) + 1
        }
        val best = counter.entries.sortedByDescending { it.value }.firstOrNull()
        return if (best != null && best.value >= 3) best.key to best.value else null
    }

    private fun checkDetailFields(html: String): Pair<Boolean, String> {
        if (html.isBlank()) return false to "详情页未抓到"
        val hasTitle = Regex("<h1[^>]*>[^<]{1,60}</h1>", RegexOption.IGNORE_CASE).containsMatchIn(html) ||
            Regex("class=\"[^\"]*(?:title|name)[^\"]*\"[^>]*>[^<]{2,40}<", RegexOption.IGNORE_CASE)
                .containsMatchIn(html)
        val hasPic = Regex("<img[^>]+(?:data-original|src)=[\"'][^\"']+\\.(jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE)
            .containsMatchIn(html)
        val hasPlay = Regex("(play|线路|m3u8|第.{1,3}集)", RegexOption.IGNORE_CASE).containsMatchIn(html)
        val parts = ArrayList<String>()
        parts.add(if (hasTitle) "标题" else "缺标题")
        parts.add(if (hasPic) "封面" else "缺封面")
        if (hasPlay) parts.add("播放区")
        return (hasTitle && hasPic) to parts.joinToString("/")
    }

    private fun parsePlayList(html: String): Triple<List<String>, Int, String> {
        if (html.isBlank()) return Triple(emptyList(), 0, "")
        val routeNames = ArrayList<String>()
        val routeRe = Regex(
            "<(?:a|li|div|span)[^>]+class=\"[^\"]*(?:play-title|nav-tabs|module-tab-item|play-from|source|line)[^\"]*\"[^>]*>\\s*([^<>]{1,20})\\s*<",
            RegexOption.IGNORE_CASE,
        )
        for (m in routeRe.findAll(html)) {
            val n = m.groupValues[1].trim()
            if (n.isNotEmpty() && !n.contains("http") && routeNames.size < 8 && !routeNames.contains(n)) {
                routeNames.add(n)
            }
        }
        if (routeNames.isEmpty()) {
            Regex("线路\\s*[0-9一二三四五]|wjm3u8").findAll(html).forEach {
                if (!routeNames.contains(it.value) && routeNames.size < 8) routeNames.add(it.value)
            }
        }
        val eps = ArrayList<String>()
        val epRe = Regex(
            "<a[^>]+href=[\"']([^\"']*(?:play|/p/|vodplay)[^\"']*)[\"'][^>]*>\\s*([^<>]{1,20})\\s*</a>",
            RegexOption.IGNORE_CASE,
        )
        for (m in epRe.findAll(html)) {
            val ep = m.groupValues[2].trim()
            if (ep.isEmpty()) continue
            if (!eps.contains(ep)) eps.add(ep)
        }
        val sample = if (eps.isEmpty()) "" else eps.take(3).joinToString(",") + " ${'$'}地址"
        return Triple(routeNames, eps.size, sample)
    }

    private fun findDirectMedia(html: String): List<String> {
        if (html.isBlank()) return emptyList()
        val re = Regex("https?://[^\"'\\s<>]+\\.(?:m3u8|mp4|flv)(?:\\?[^\"'\\s<>]*)?", RegexOption.IGNORE_CASE)
        return re.findAll(html).map { it.value }.distinct().take(6).toList()
    }
}