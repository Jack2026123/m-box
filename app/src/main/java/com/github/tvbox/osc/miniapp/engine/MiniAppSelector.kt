package com.github.tvbox.osc.miniapp.engine

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

object MiniAppSelector {

    typealias JsEvalFn = (String, String) -> String

    private val normalAttrs = arrayOf("href", "src", "class", "title", "alt")

    fun getTrueElement(rule: String, element: Element?): Element? {
        if (element == null) return null
        if (rule.startsWith("Text") || rule.startsWith("Attr")) return element
        for (a in normalAttrs) {
            if (a == rule) return element
        }
        val excl = rule.split("--")
        if (excl.size > 1) {
            setPrettyPrint(element, false)
            var current: Element? = getTrueElement(excl[0], element)
            var outer = current?.outerHtml() ?: ""
            for (i in 1 until excl.size) {
                val exEl = getTrueElement(excl[i], current)
                if (exEl != null) {
                    outer = outer.replace(exEl.outerHtml(), "")
                }
                current = Jsoup.parse(outer)
                setPrettyPrint(current, false)
            }
            setPrettyPrint(element, true)
            if (current != null) setPrettyPrint(current, true)
            return current
        }
        val ors = rule.split("||")
        if (ors.size > 1) {
            for (part in ors) {
                val el = try {
                    getTrueElement(part, element)
                } catch (e: Throwable) {
                    null
                }
                if (el != null) return el
            }
        }
        val comma = rule.split(",")
        if (comma.size > 1) {
            val index = comma[1].toInt()
            val selected = element.select(MiniAppText.decodeConflictStr(comma[0]) ?: comma[0])
            return if (index < 0) {
                selected[selected.size + index]
            } else {
                element.select(MiniAppText.decodeConflictStr(comma[0]) ?: comma[0])[index]
            }
        }
        return element.select(MiniAppText.decodeConflictStr(rule) ?: rule).first()
    }

    private fun setPrettyPrint(element: Element?, pretty: Boolean) {
        try {
            element?.ownerDocument()?.outputSettings()?.prettyPrint(pretty)
        } catch (e: Throwable) {
        }
    }

    fun selectElements(element: Element?, rule: String): Elements {
        val out = Elements()
        if (element == null) return out
        for (part in rule.split("||")) {
            try {
                out.addAll(selectElementsWithoutOr(element, part))
            } catch (e: Throwable) {
            }
        }
        return out
    }

    fun selectElementsWithoutOr(element: Element, rule: String): Elements {
        val comma = rule.split(",")
        if (comma.size > 1) {
            val rangeParts = comma[1].split(":", ignoreCase = false)
            var start = 0
            val first = rangeParts[0]
            if (first.isNotEmpty()) {
                start = try {
                    first.toInt()
                } catch (e: NumberFormatException) {
                    0
                }
            }
            var end = 0
            val second = rangeParts[1]
            if (second.isNotEmpty()) {
                end = try {
                    second.toInt()
                } catch (e: NumberFormatException) {
                    0
                }
            }
            val selected = element.select(MiniAppText.decodeConflictStr(comma[0]) ?: comma[0])
            if (end > selected.size) end = selected.size
            if (end <= 0) end += selected.size
            val out = Elements()
            var i = start
            while (i < end) {
                out.add(selected[i])
                i++
            }
            return out
        }
        return element.select(MiniAppText.decodeConflictStr(rule) ?: rule)
    }

    fun getUrlByRule(base: String, movieRule: MiniMovieRule, element: Element?, rule: String, jsEval: JsEvalFn? = null): String {
        if (rule == "*") return base
        val chain = rule.split("&&")
        var el = element
        if (chain.size != 1) {
            el = getTrueElement(chain[0], el)
        }
        for (i in 1 until chain.size - 1) {
            el = getTrueElement(chain[i], el)
        }
        return getUrl(el, chain[chain.size - 1], movieRule, movieRule.chapterUrl, jsEval)
    }

    fun getTextByRule(element: Element?, rule: String, jsEval: JsEvalFn? = null): String {
        if (MiniAppText.isEmpty(rule) || rule == "*") return ""
        if (rule.contains(".js:")) return getAndTextByRule(element, rule, "＋", jsEval)
        if (rule.contains("＋")) return getAndTextByRule(element, rule, "＋", jsEval)
        return getAndTextByRule(element, rule, "\\+", jsEval)
    }

    private fun getAndTextByRule(element: Element?, rule: String, sepRegex: String, jsEval: JsEvalFn?): String {
        val parts = rule.split(Regex(sepRegex))
        val out = ArrayList<String>()
        for (part in parts) {
            val trim = part.trim()
            if ((trim.startsWith("'") && trim.endsWith("'")) ||
                (trim.startsWith("\"") && trim.endsWith("\""))
            ) {
                out.add(trim.substring(1, trim.length - 1).replace("\\n", "\n"))
            } else {
                out.add(getContentWithoutAnd(element, trim, jsEval))
            }
        }
        return MiniAppText.listToString(out, "")
    }

    private fun getContentWithoutAnd(element: Element?, rule: String, jsEval: JsEvalFn?): String {
        val chain = rule.split("&&")
        var el = element
        if (chain.size != 1) {
            el = getTrueElement(chain[0], el)
        }
        for (i in 1 until chain.size - 1) {
            el = getTrueElement(chain[i], el)
        }
        return getText(el, chain[chain.size - 1], jsEval)
    }

    fun getText(element: Element?, rule: String, jsEval: JsEvalFn? = null): String {
        if (rule == "*") return "null"
        val ors = rule.split("||")
        if (ors.size > 1) {
            for (part in ors) {
                val text = try {
                    getTextWithoutOr(element, part, jsEval)
                } catch (e: Throwable) {
                    null
                }
                if (!text.isNullOrEmpty()) return text
            }
        }
        return getTextWithoutOr(element, rule, jsEval)
    }

    private fun getTextWithoutOr(element: Element?, rule: String, jsEval: JsEvalFn?): String {
        var r = rule
        var jsCode = ""
        val jsSplit = r.split(".js:")
        if (jsSplit.size > 1) {
            r = jsSplit[0]
            jsCode = MiniAppText.arrayToString(jsSplit.toTypedArray(), 1, ".js:")
        }
        var attr: String
        val bang = r.split("!")
        if (bang.size > 1) {
            attr = when {
                bang[0] == "Text" -> element?.text() ?: ""
                bang[0] == "Html" -> element?.html() ?: ""
                bang[0].contains("Attr") -> element?.attr(bang[0].replace("Attr", "")) ?: ""
                else -> element?.attr(MiniAppText.decodeConflictStr(bang[0]) ?: bang[0]) ?: ""
            }
            if (r != "Html") attr = attr.replace("\n", " ")
            for (i in 1 until bang.size) {
                attr = attr.replace(bang[i], "")
            }
        } else {
            attr = when {
                r == "Text" -> element?.text() ?: ""
                r == "Html" -> element?.html() ?: ""
                r.contains("Attr") -> element?.attr(r.replace("Attr", "")) ?: ""
                else -> element?.attr(MiniAppText.decodeConflictStr(r) ?: r) ?: ""
            }
            if (r != "Html") attr = attr.replace("\n", " ")
        }
        if (MiniAppText.isNotEmpty(jsCode)) {
            return try {
                jsEval?.invoke(jsCode, attr) ?: attr
            } catch (e: Throwable) {
                attr
            }
        }
        return attr
    }

    fun getUrl(element: Element?, rule: String, movieRule: MiniMovieRule, chapterUrl: String, jsEval: JsEvalFn? = null): String {
        if (rule == "*") return "null"
        val ors = rule.split("||")
        if (ors.size > 1) {
            for (part in ors) {
                val url = try {
                    getUrlWithoutOr(element, part, movieRule, chapterUrl, jsEval)
                } catch (e: Throwable) {
                    null
                }
                if (!url.isNullOrEmpty()) return url
            }
        }
        return getUrlWithoutOr(element, rule, movieRule, chapterUrl, jsEval)
    }

    private fun getUrlWithoutOr(
        element: Element?,
        rule: String,
        movieRule: MiniMovieRule,
        chapterUrl: String,
        jsEval: JsEvalFn?,
    ): String {
        var r = rule
        var jsCode = ""
        val jsSplit = r.split(".js:")
        if (jsSplit.size > 1) {
            r = jsSplit[0]
            jsCode = MiniAppText.arrayToString(jsSplit.toTypedArray(), 1, ".js:")
        }
        if (element == null) return ""
        var attr: String
        when {
            r.startsWith("Text") -> attr = element.text()
            r == "Html" -> attr = element.html()
            r.startsWith("AttrNo") -> return movieRule.baseUrl + element.attr(r.replaceFirst("AttrNo", ""))
            r.startsWith("AttrYes") -> attr = element.attr(r.replaceFirst("AttrYes", ""))
            r.startsWith("Attr") -> attr = element.attr(r.replaceFirst("Attr", ""))
            else -> attr = element.attr(MiniAppText.decodeConflictStr(r) ?: r)
        }
        if (jsCode.isEmpty()) {
            if (r != "Html") attr = MiniAppText.trimBlanks(attr) ?: ""
        } else {
            try {
                attr = jsEval?.invoke(jsCode, attr) ?: attr
                val lower = attr.lowercase().trim()
                if (lower.startsWith("http") || lower.startsWith("x5://")) {
                    return attr.trim()
                }
            } catch (e: Throwable) {
                if (r != "Html") attr = MiniAppText.trimBlanks(attr) ?: ""
            }
        }
        if (MiniAppText.isEmpty(attr)) return ""
        if (r == "Html" || attr.startsWith("http") || attr.startsWith("hiker")) return attr
        if (attr.startsWith("//")) return MiniAppText.joinUrl(chapterUrl, attr)
        if (attr.startsWith("magnet") || attr.startsWith("thunder") ||
            attr.startsWith("ftp") || attr.startsWith("ed2k")
        ) {
            return attr
        }
        if (attr.startsWith("/") || attr.startsWith("./") ||
            attr.startsWith("../") || attr.startsWith("?")
        ) {
            return MiniAppText.joinUrl(chapterUrl, attr)
        }
        val dollar = attr.split("$")
        if (dollar.size > 1 && dollar[1].startsWith("http")) return dollar[1]
        if (attr.contains("url(")) {
            val urlParts = attr.split("url(")
            if (urlParts.size > 1 && urlParts[1].startsWith("http")) {
                return urlParts[1].split(")")[0]
            }
        }
        return MiniAppText.joinUrl(chapterUrl, attr)
    }

    fun joinUrl(base: String, rel: String): String = MiniAppText.joinUrl(base, rel)

    fun parseDomForUrl(html: String, rule: String, urlKey: String, jsEval: JsEvalFn? = null): String {
        var parse: Element? = Jsoup.parse(html)
        val chain = rule.split("&&")
        if (chain.size != 1) {
            parse = getTrueElement(chain[0], parse)
        }
        for (i in 1 until chain.size - 1) {
            parse = getTrueElement(chain[i], parse)
        }
        val movieRule = MiniMovieRule()
        val firstPageUrl = getFirstPageUrl(urlKey)
        movieRule.baseUrl = MiniAppText.getBaseUrl(firstPageUrl) ?: ""
        return getUrl(parse, chain[chain.size - 1], movieRule, firstPageUrl, jsEval)
    }

    fun parseDomForList(html: String, rule: String, jsEval: JsEvalFn? = null): List<String> {
        val parse: Document = Jsoup.parse(html)
        val chain = rule.split("&&")
        var trueElement: Element? = getTrueElement(chain[0], parse)
        for (i in 1 until chain.size - 1) {
            trueElement = getTrueElement(chain[i], trueElement)
        }
        val elements = selectElements(trueElement, chain[chain.size - 1])
        val out = ArrayList<String>()
        for (el in elements) out.add(el.outerHtml())
        return out
    }

    fun parseDomForHtmlList(html: String, listRule: String, urlRule: String, jsEval: JsEvalFn? = null): List<String> {
        val parse: Document = Jsoup.parse(html)
        val chain = listRule.split("&&")
        var trueElement: Element? = getTrueElement(chain[0], parse)
        for (i in 1 until chain.size - 1) {
            trueElement = getTrueElement(chain[i], trueElement)
        }
        val elements = selectElements(trueElement, chain[chain.size - 1])
        val urlChain = urlRule.split("&&")
        val out = ArrayList<String>()
        for (element in elements) {
            var el: Element? = element
            val movieRule = MiniMovieRule()
            movieRule.baseUrl = ""
            if (urlChain.size != 1) {
                el = getTrueElement(urlChain[0], el)
            }
            for (i in 1 until urlChain.size - 1) {
                el = getTrueElement(urlChain[i], el)
            }
            out.add(getUrl(el, urlChain[urlChain.size - 1], movieRule, "", jsEval))
        }
        return out
    }

    fun parsePageClassUrl(url: String, page: Int, rule: MiniAppRule?, inject: MutableMap<String, Any?>?): String {
        if (MiniAppText.isEmpty(url)) return url
        val r = rule ?: MiniAppRule()
        var p = page
        if (inject != null) {
            if (MiniAppText.isNotEmpty(r.classUrl)) {
                inject["MY_CLASS_NAME"] = r.className
                inject["MY_CLASS_URL"] = r.classUrl
            }
            if (MiniAppText.isNotEmpty(r.yearUrl)) {
                inject["MY_YEAR_NAME"] = r.yearName
                inject["MY_YEAR_URL"] = r.yearUrl
            }
            if (MiniAppText.isNotEmpty(r.areaUrl)) {
                inject["MY_AREA_NAME"] = r.areaName
                inject["MY_AREA_URL"] = r.areaUrl
            }
            if (MiniAppText.isNotEmpty(r.sortUrl)) {
                inject["MY_SORT_NAME"] = r.sortName
                inject["MY_SORT_URL"] = r.sortUrl
            }
        }
        val split = url.split(";")
        val firstSplit = split[0].split("[firstPage=")
        val replaced: String
        if (p == 1 && firstSplit.size > 1) {
            replaced = firstSplit[1].split("]")[0]
        } else if (firstSplit[0].contains("fypage@")) {
            val atSplit = firstSplit[0].split("fypage@")
            val ops = atSplit[1].split("@")
            for (i in 0 until ops.size - 1) {
                val op = ops[i]
                when {
                    op.startsWith("-") -> p -= op.replace("-", "").toInt()
                    op.startsWith("+") -> p += op.replace("+", "").toInt()
                    op.startsWith("*") -> p *= op.replace("*", "").toInt()
                    op.startsWith("/") -> p /= op.replace("/", "").toInt()
                }
            }
            replaced = atSplit[0] + p + ops[ops.size - 1]
        } else {
            replaced = firstSplit[0].replace("fypage", p.toString())
        }
        val afterFyAll = if (replaced.contains("fyAll")) {
            when (r.firstHeader) {
                "class" -> replaced.replace("fyAll", r.classUrl)
                "area" -> replaced.replace("fyAll", r.areaUrl)
                "year" -> replaced.replace("fyAll", r.yearUrl)
                "sort" -> replaced.replace("fyAll", r.sortUrl)
                else -> replaced.replace("fyAll", r.classUrl)
            }
        } else {
            replaced
        }
        val replaced2 = afterFyAll.replace("fyclass", r.classUrl)
            .replace("fyyear", r.yearUrl)
            .replace("fyarea", r.areaUrl)
            .replace("fysort", r.sortUrl)
        val sb = StringBuilder(replaced2)
        if (split.size > 1) {
            for (i in 1 until split.size) {
                sb.append(";").append(split[i])
            }
        }
        return sb.toString()
    }

    fun getFirstPageUrl(url: String?): String {
        if (url.isNullOrEmpty()) return url ?: ""
        var s = url
        s = s.replace("x5://", "").replace("webview://", "")
        if (s.startsWith("x5Rule://") || s.startsWith("webRule://")) {
            val atParts = s.split("@")
            if (atParts.size > 1) {
                s = atParts[1]
            }
        }
        if (s.startsWith("hiker://empty##")) {
            s = s.replaceFirst("hiker://empty##", "")
            try {
                s = java.net.URLDecoder.decode(s, "UTF-8")
            } catch (e: Throwable) {
            }
        } else if (s.startsWith("hiker://empty")) {
            s = s.replaceFirst("hiker://empty", "")
        }
        s = s.replace("download://", "").replace("video://", "")
        val first = s.split(";")[0]
        val firstPageSplit = first.split("[firstPage=")
        if (firstPageSplit.size > 1) {
            return firstPageSplit[1].split("]")[0]
        }
        if (first.contains("fypage@")) {
            val atSplit = first.split("fypage@")
            val ops = atSplit[1].split("@")
            var page = 1
            for (i in 0 until ops.size - 1) {
                val op = ops[i]
                when {
                    op.startsWith("-") -> page -= op.replace("-", "").toInt()
                    op.startsWith("+") -> page += op.replace("+", "").toInt()
                    op.startsWith("*") -> page *= op.replace("*", "").toInt()
                    op.startsWith("/") -> page /= op.replace("/", "").toInt()
                }
            }
            return atSplit[0] + page + ops[ops.size - 1]
        }
        return first.replace("fypage", "1")
    }

    private val noAddIndex = Regex(":eq|:lt|:gt|:first|:last|:not|:even|:odd|:has|:contains|:matches|:empty|^body$|^#")

    fun parseHikerToJq(rule: String, keepLast: Boolean): String {
        if (rule.contains("&&")) {
            val split = rule.split("&&")
            val out = ArrayList<String>()
            for (i in split.indices) {
                val lastToken = split[i].split(" ").last()
                if (!noAddIndex.containsMatchIn(lastToken)) {
                    if (!keepLast && i >= split.size - 1) {
                        out.add(split[i])
                    } else {
                        out.add(split[i] + ":eq(0)")
                    }
                } else {
                    out.add(split[i])
                }
            }
            return out.joinToString(" ")
        }
        val split2 = rule.split(" ")
        return if (noAddIndex.containsMatchIn(split2[split2.size - 1]) || !keepLast) rule else "$rule:eq(0)"
    }

    private class Painfo {
        var excludes: MutableList<String>? = null
        var index: Int = 0
        var rule: String = ""
    }

    private fun getParseInfo(rule: String): Painfo {
        val info = Painfo()
        info.rule = rule
        if (rule.contains(":eq")) {
            info.rule = rule.split(":")[0]
            var idxPart = rule.split(":")[1]
            if (info.rule.contains("--")) {
                val split = info.rule.split("--")
                info.excludes = ArrayList(split.subList(1, split.size))
                info.rule = split[0]
            } else if (idxPart.contains("--")) {
                val split2 = idxPart.split("--")
                info.excludes = ArrayList(split2.subList(1, split2.size))
                idxPart = split2[0]
            }
            info.index = try {
                idxPart.replace("eq(", "").replace(")", "").toInt()
            } catch (e: Throwable) {
                0
            }
        } else if (rule.contains("--")) {
            val split3 = info.rule.split("--")
            info.excludes = ArrayList(split3.subList(1, split3.size))
            info.rule = split3[0]
        }
        return info
    }

    private fun parseOneRule(doc: Document, rule: String, current: Elements): Elements {
        val info = getParseInfo(rule)
        var selected = if (current.isEmpty()) {
            doc.select(info.rule)
        } else {
            current.select(info.rule)
        }
        if (rule.contains(":eq")) {
            selected = if (info.index < 0) {
                selected.eq(selected.size + info.index)
            } else {
                selected.eq(info.index)
            }
        }
        if (info.excludes != null && !selected.isEmpty()) {
            selected = selected.clone()
            for (ex in info.excludes!!) {
                selected.select(ex).remove()
            }
        }
        return selected
    }

    fun parseDomForArray(html: String, rule: String): List<String> {
        val doc = Jsoup.parse(html)
        val split = parseHikerToJq(rule, false).split(" ")
        var elements = Elements()
        for (part in split) {
            elements = parseOneRule(doc, part, elements)
            if (elements.isEmpty()) return emptyList()
        }
        val out = ArrayList<String>()
        for (el in elements) out.add(el.outerHtml())
        return out
    }

    fun parseDomForList5(html: String, listRule: String, urlRule: String, extraRule: String, extraUrlKey: String, jsEval: JsEvalFn? = null): List<String> {
        val doc = Jsoup.parse(html)
        val split = parseHikerToJq(listRule, false).split(" ")
        var elements = Elements()
        for (part in split) {
            elements = parseOneRule(doc, part, elements)
            if (elements.isEmpty()) return emptyList()
        }
        val out = ArrayList<String>()
        for (el in elements) {
            val outer = el.outerHtml()
            out.add(parseDomForUrl(outer, urlRule, "", jsEval).trim() + '$' + parseDomForUrl(outer, extraRule, extraUrlKey, jsEval))
        }
        return out
    }

    private fun xpathDocument(html: String): org.w3c.dom.Document? {
        return try {
            val doc = Jsoup.parse(html)
            doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
            val xml = doc.html()
            val dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            dbf.isNamespaceAware = false
            dbf.newDocumentBuilder().parse(java.io.ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (e: Throwable) {
            null
        }
    }

    fun xpath(html: String, expr: String): String {
        return try {
            val w3c = xpathDocument(html) ?: return ""
            val xp = javax.xml.xpath.XPathFactory.newInstance().newXPath()
            xp.evaluate(expr, w3c, javax.xml.xpath.XPathConstants.STRING) as? String ?: ""
        } catch (e: Throwable) {
            ""
        }
    }

    fun xpathArray(html: String, expr: String): List<String> {
        return try {
            val w3c = xpathDocument(html) ?: return emptyList()
            val xp = javax.xml.xpath.XPathFactory.newInstance().newXPath()
            val nodes = xp.evaluate(expr, w3c, javax.xml.xpath.XPathConstants.NODESET) as? org.w3c.dom.NodeList
                ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until nodes.length) {
                out.add(nodes.item(i)?.textContent ?: "")
            }
            out
        } catch (e: Throwable) {
            emptyList()
        }
    }
}
