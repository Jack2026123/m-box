package com.github.tvbox.osc.reading

import com.github.tvbox.osc.util.LOG
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.helper.W3CDom
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * One rule-evaluation context: a content value (raw body text, parsed
 * JSON, a JSoup element, or a list item) plus the LegadoJs runtime and
 * the base URL results are absolutized against.
 *
 * Semantics follow Legado's AnalyzeRule: a rule is split into `||`
 * alternatives (first non-empty wins), each alternative into `&&` parts
 * (concatenated); `{{...}}` templates, `<js>` blocks, `@js:` transforms,
 * `@get:{k}` / `@put:{...}` variables and the trailing `##regex##repl`
 * purification are handled per part. Rules may also be newline pipelines
 * (a `<js>` stage transforms the content, the next stage re-reads it).
 */
class AnalyzeRule(
    val source: BookSource,
    val js: LegadoJs,
    content: Any?,
    baseUrl: String,
) {
    var content: Any? = content
        private set
    var baseUrl: String = baseUrl
        private set

    private var jsonRoot: Any? = UNSET
    private var soupRoot: Document? = null
    private var soupElement: Element? = null

    fun setContent(content: Any?, baseUrl: String) {
        this.content = content
        this.baseUrl = baseUrl
        jsonRoot = UNSET
        soupRoot = null
        soupElement = null
    }

    fun child(content: Any?): AnalyzeRule = AnalyzeRule(source, js, content, baseUrl)

    // ------------------------------------------------------------ public API

    fun getString(ruleStr: String?, isUrl: Boolean = false): String {
        if (ruleStr.isNullOrBlank()) return ""
        // Field-level isolation: one field's rule throwing degrades that
        // field to empty, never the whole chain (Legado semantics).
        return try {
            getStringInner(ruleStr, isUrl)
        } catch (e: Throwable) {
            LegadoLog.fieldError(source, "getString", ruleStr, e)
            ""
        }
    }

    private fun getStringInner(ruleStr: String, isUrl: Boolean): String {
        val stages = splitStages(ruleStr)
        if (stages.size > 1) {
            var current: Any? = content
            var value = ""
            for ((index, stage) in stages.withIndex()) {
                val sub = child(current)
                // Only the final stage's value is a URL: absolutizing an
                // intermediate value (e.g. an encrypted path segment a
                // later <js> stage must decrypt) would corrupt it.
                value = sub.getStringSingle(stage, isUrl && index == stages.lastIndex)
                if (index < stages.lastIndex) current = value
            }
            return value
        }
        return getStringSingle(ruleStr, isUrl)
    }

    fun getStringList(ruleStr: String?, isUrl: Boolean = false): List<String> {
        if (ruleStr.isNullOrBlank()) return emptyList()
        return try {
            getStringListInner(ruleStr, isUrl)
        } catch (e: Throwable) {
            LegadoLog.fieldError(source, "getStringList", ruleStr, e)
            emptyList()
        }
    }

    private fun getStringListInner(ruleStr: String, isUrl: Boolean): List<String> {
        val stages = splitStages(ruleStr)
        if (stages.size > 1) {
            var current: Any? = content
            var value: List<String> = emptyList()
            for ((index, stage) in stages.withIndex()) {
                val sub = child(current)
                value = sub.getStringListSingle(stage, isUrl && index == stages.lastIndex)
                if (index < stages.lastIndex) current = value.joinToString("\n")
            }
            return value
        }
        return getStringListSingle(ruleStr, isUrl)
    }

    fun getElements(ruleStr: String?): List<Any> {
        if (ruleStr.isNullOrBlank()) return emptyList()
        return try {
            getElementsInner(ruleStr)
        } catch (e: Throwable) {
            LegadoLog.fieldError(source, "getElements", ruleStr, e)
            emptyList()
        }
    }

    private fun getElementsInner(ruleStr: String): List<Any> {
        val stages = splitStages(ruleStr)
        if (stages.size > 1) {
            var current: Any? = content
            var value: List<Any> = emptyList()
            for ((index, stage) in stages.withIndex()) {
                val sub = child(current)
                value = if (index == stages.lastIndex) {
                    sub.getElementsSingle(stage)
                } else {
                    // Intermediate stages keep their element list as the
                    // next stage's content (a list-position pipeline);
                    // only when a stage selects nothing do we fall back
                    // to its string form.
                    val elements = sub.getElementsSingle(stage)
                    current = if (elements.isNotEmpty()) elements
                    else sub.getStringSingle(stage, false)
                    continue
                }
                if (index < stages.lastIndex) current = value
            }
            return value
        }
        return getElementsSingle(ruleStr)
    }

    fun getElement(ruleStr: String?): Any? = getElements(ruleStr).firstOrNull()

    /** Compose a URL-ish string: templates + js segments, no selector eval. */
    fun makeUpUrl(text: String): String = makeUp(text)

    // ------------------------------------------------------------ core eval

    private fun getStringSingle(ruleStr: String, isUrl: Boolean): String {
        val orGroups = splitTopLevel(ruleStr, "||")
        for (group in orGroups) {
            val parts = splitTopLevel(group, "&&")
            val sb = StringBuilder()
            for (part in parts) {
                sb.append(evalPart(part, isUrl))
            }
            val value = sb.toString()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun getStringListSingle(ruleStr: String, isUrl: Boolean): List<String> {
        // %% interleaves the lists of its parts; take it at the top level.
        val interleave = splitTopLevel(ruleStr, "%%")
        if (interleave.size > 1) {
            val lists = interleave.map { getStringListSingle(it, isUrl) }
            val out = ArrayList<String>()
            val max = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until max) for (l in lists) if (i < l.size) out.add(l[i])
            return out
        }
        val orGroups = splitTopLevel(ruleStr, "||")
        for (group in orGroups) {
            val parts = splitTopLevel(group, "&&")
            val out = ArrayList<String>()
            for (part in parts) out.addAll(evalPartList(part, isUrl))
            if (out.any { it.isNotBlank() }) return out.filter { it.isNotBlank() }
        }
        return emptyList()
    }

    private fun getElementsSingle(ruleStr: String): List<Any> {
        val interleave = splitTopLevel(ruleStr, "%%")
        if (interleave.size > 1) {
            val lists = interleave.map { getElementsSingle(it) }
            val out = ArrayList<Any>()
            val max = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until max) for (l in lists) if (i < l.size) out.add(l[i])
            return out
        }
        val orGroups = splitTopLevel(ruleStr, "||")
        for (group in orGroups) {
            val parts = splitTopLevel(group, "&&")
            val out = ArrayList<Any>()
            for (part in parts) out.addAll(evalPartElements(part))
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    // ------------------------------------------------------------ one part

    /** Evaluate one || / && part to a string. */
    private fun evalPart(rawPart: String, isUrl: Boolean): String {
        return try {
            val prepared = preparePart(rawPart) ?: return ""
            val (core, replace) = prepared
            var value = evalCore(core, isUrl, wantList = false) as? String ?: ""
            value = applyReplace(value, replace)
            if (isUrl && value.isNotBlank()) value = absolutize(value)
            value
        } catch (e: Throwable) {
            LegadoLog.fieldError(source, "part", rawPart, e)
            ""
        }
    }

    private fun evalPartList(rawPart: String, isUrl: Boolean): List<String> {
        return try {
            evalPartListInner(rawPart, isUrl)
        } catch (e: Throwable) {
            LegadoLog.fieldError(source, "partList", rawPart, e)
            emptyList()
        }
    }

    private fun evalPartListInner(rawPart: String, isUrl: Boolean): List<String> {
        // A whole-part <js> block at list position returns a JS array:
        // read it back as a list (nextTocUrl and friends rely on this).
        splitWholeJs(rawPart)?.let { (code, replace) ->
            return js.evalStringList(code, content).map {
                var v = applyReplace(it, replace)
                if (isUrl && v.isNotBlank()) v = absolutize(v)
                v
            }
        }
        val prepared = preparePart(rawPart) ?: return emptyList()
        val (core, replace) = prepared
        val items: List<String> = when {
            core.mode == Mode.JS -> js.evalStringList(core.text.removePrefix("@js:"), content)
            else -> {
                val raw = evalCore(core, isUrl, wantList = true)
                when (raw) {
                    is List<*> -> raw.map { LenientJson.valueToString(it) }
                    is String -> if (raw.isBlank()) emptyList() else listOf(raw)
                    null -> emptyList()
                    else -> listOf(LenientJson.valueToString(raw))
                }
            }
        }
        return items.map {
            var v = applyReplace(it, replace)
            if (isUrl && v.isNotBlank()) v = absolutize(v)
            v
        }
    }

    private fun evalPartElements(rawPart: String): List<Any> {
        return try {
            evalPartElementsInner(rawPart)
        } catch (e: Throwable) {
            LegadoLog.fieldError(source, "partElements", rawPart, e)
            emptyList()
        }
    }

    private fun evalPartElementsInner(rawPart: String): List<Any> {
        // Whole-part <js> at list position: its array result IS the
        // element list (chapterList scripts return JSON arrays).
        splitWholeJs(rawPart)?.let { (code, _) ->
            return js.evalStringList(code, content).mapNotNull { item ->
                LenientJson.parseAny(item) ?: item.takeIf { it.isNotBlank() }
            }
        }
        val prepared = preparePart(rawPart) ?: return emptyList()
        val (core, _) = prepared
        return when {
            core.mode == Mode.JS -> {
                val text = js.evalString(core.text.removePrefix("@js:"), content)
                val parsed = LenientJson.parseAny(text)
                when (parsed) {
                    is JSONArray -> (0 until parsed.length()).map { parsed.get(it) }
                    null -> if (text.isBlank()) emptyList() else listOf(text)
                    else -> listOf(parsed)
                }
            }
            core.mode == Mode.JSON -> {
                val result = evalJsonPath(core.text)
                when (result) {
                    null -> emptyList()
                    is List<*> -> result.filterNotNull()
                    else -> listOf(result)
                }
            }
            core.mode == Mode.XPATH -> xpathElements(core.text)
            core.mode == Mode.LITERAL -> {
                // Makeup ({{...}} / <js>) may have produced a JSON array
                // or object: that is the element list then.
                val parsed = LenientJson.parseAny(core.text)
                when (parsed) {
                    is JSONArray -> (0 until parsed.length()).map { parsed.get(it) }
                    is JSONObject -> listOf(parsed)
                    else -> emptyList()
                }
            }
            else -> soupElements(core.text)
        }
    }

    /**
     * Split a part that is exactly one `<js>...</js>` block (plus an
     * optional trailing ## purification) into code + replace spec.
     */
    private fun splitWholeJs(rawPart: String): Pair<String, ReplaceSpec>? {
        val text = rawPart.trim()
        if (!text.startsWith("<js>")) return null
        val end = text.indexOf("</js>", 4)
        if (end < 0) return null
        val tail = text.substring(end + 5).trim()
        if (tail.isNotEmpty() && !tail.startsWith("##")) return null
        val code = text.substring(4, end)
        val replace = if (tail.isEmpty()) {
            ReplaceSpec()
        } else {
            val segments = tail.split("##")
            ReplaceSpec(
                regex = segments.getOrNull(1) ?: "",
                replacement = segments.getOrNull(2) ?: "",
                firstOnly = segments.size > 3,
            )
        }
        return code to replace
    }

    private data class ReplaceSpec(
        val regex: String = "",
        val replacement: String = "",
        val firstOnly: Boolean = false,
    )

    private data class Core(val text: String, val mode: Mode)

    private enum class Mode { JSON, JSOUP, XPATH, JS, LITERAL }

    /**
     * Run @put, template makeup and ## split for one part; returns the
     * core rule text (mode-tagged) plus its purification spec.
     */
    private fun preparePart(rawPart: String): Pair<Core, ReplaceSpec>? {
        var text = rawPart.trim()
        if (text.isEmpty()) return null
        text = extractPuts(text)
        val composed = try {
            makeUp(text)
        } catch (e: Throwable) {
            LegadoLog.fieldError(source, "makeUp", rawPart, e)
            return null
        }
        val segments = composed.split("##")
        var coreText = segments.getOrNull(0)?.trim().orEmpty()
        val replace = ReplaceSpec(
            regex = segments.getOrNull(1) ?: "",
            replacement = segments.getOrNull(2) ?: "",
            firstOnly = segments.size > 3,
        )
        if (coreText.isEmpty()) {
            // Pure purification of the content itself (e.g. rule "##x##y").
            return Core("", Mode.LITERAL) to replace
        }
        var mode = detectMode(coreText)
        if (coreText.startsWith("@css:", ignoreCase = true)) {
            // "@css:" only tags the rule as a CSS chain; the prefix is
            // not part of the selector itself.
            coreText = coreText.substring(5).trim()
            mode = Mode.JSOUP
            if (coreText.isEmpty()) return null
        }
        return Core(coreText, mode) to replace
    }

    private fun detectMode(core: String): Mode = when {
        core.startsWith("http://") || core.startsWith("https://") -> Mode.LITERAL
        core.startsWith("@js:") -> Mode.JS
        core.startsWith("@XPath:", ignoreCase = true) -> Mode.XPATH
        core.startsWith("@Json:", ignoreCase = true) -> Mode.JSON
        core.startsWith("@CSS:", ignoreCase = true) -> Mode.JSOUP
        core.startsWith("@Regex:", ignoreCase = true) -> Mode.LITERAL
        core.startsWith("//") -> Mode.XPATH
        core.startsWith("$") -> Mode.JSON
        // On JSON content a dot-leading path is a JsonPath shorthand
        // (`.rows[*]`), not a CSS class chain.
        isJsonContent() && core.startsWith(".") && !core.contains(" ") -> Mode.JSON
        isJsonContent() && !core.contains("@") && !core.contains(" ") &&
            !core.startsWith(".") && !core.startsWith("#") -> Mode.JSON
        // On a JSON element, `key@text` / `key@href` resolve the key
        // first and apply the @ operator to its value.
        isJsonContent() && isBareKeyWithAt(core) -> Mode.JSON
        core.contains("@") -> Mode.JSOUP
        core.startsWith(".") || core.startsWith("#") ||
            core.matches(Regex("^(class|id|tag|text)\\..*")) ||
            core.matches(Regex("^[a-zA-Z][\\w-]*(\\.|>|\\s).*")) -> Mode.JSOUP
        isJsonContent() -> Mode.JSON
        else -> Mode.LITERAL
    }

    /** `key@op` where key is a bare JSON field name (no selector syntax). */
    private fun isBareKeyWithAt(core: String): Boolean {
        val at = core.indexOf('@')
        if (at <= 0) return false
        val head = core.substring(0, at)
        return head.matches(Regex("[\\w\\-]+")) &&
            !head.startsWith("class") && !head.startsWith("id") &&
            !head.startsWith("tag") && !head.startsWith("text")
    }

    private fun evalCore(core: Core, isUrl: Boolean, wantList: Boolean): Any? {
        val text = core.text
        return when (core.mode) {
            Mode.JS -> js.evalString(text.removePrefix("@js:"), content)
            Mode.LITERAL -> text
            Mode.JSON -> {
                val result = evalJsonPath(text)
                if (wantList) result else valueToString(result)
            }
            Mode.XPATH -> {
                val expr = text.removePrefix("@XPath:")
                val (path, jsSuffix) = stripJsSuffix(expr)
                var value = xpathString(path)
                if (jsSuffix != null) value = js.evalString(jsSuffix, value)
                value
            }
            Mode.JSOUP -> {
                val (chain, jsSuffix) = stripAtJsSuffix(text)
                var value = soupString(chain, wantList)
                if (jsSuffix != null) {
                    value = when (value) {
                        is List<*> -> value.joinToString("\n") {
                            runCatching { js.evalString(jsSuffix, it) }.getOrDefault("")
                        }
                        else -> js.evalString(jsSuffix, value as? String ?: "")
                    }
                }
                value
            }
        }
    }

    /**
     * Evaluate a JSON-mode core: normalize the path (`$.a`, `.a`, bare
     * `key`), read it, then apply a trailing `@text` / `@attr` operator
     * or an `@js:` transform. The @ split happens at bracket depth 0 so
     * filter expressions keep their `@.field` references.
     */
    private fun evalJsonPath(text: String): Any? {
        val (pathText, jsSuffix) = stripJsSuffix(text)
        val (pathPart, atOp) = splitJsonAtOp(pathText)
        val path = when {
            pathPart.startsWith("$") -> pathPart
            pathPart.startsWith(".") || pathPart.startsWith("[") -> "$" + pathPart
            pathPart.isEmpty() -> "$"
            else -> "$." + pathPart
        }
        var value = JsonPath.read(jsonContent(), path)
        if (atOp != null) value = applyJsonAtOp(value, atOp)
        if (jsSuffix != null) value = js.evalString(jsSuffix, value)
        return value
    }

    /** Split `path@op` at the first depth-0 `@` whose tail is an operator. */
    private fun splitJsonAtOp(text: String): Pair<String, String?> {
        var depth = 0
        var quote: Char? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '[' || c == '(' || c == '{' -> depth++
                c == ']' || c == ')' || c == '}' -> depth--
                c == '@' && depth == 0 && i > 0 -> {
                    val tail = text.substring(i + 1)
                    if (tail == "text" || tail == "html" || tail == "ownText" ||
                        tail == "textNodes" || tail == "allText" ||
                        tail.matches(Regex("[a-zA-Z][\\w-]*"))
                    ) {
                        return text.substring(0, i) to tail
                    }
                }
            }
            i++
        }
        return text to null
    }

    private fun applyJsonAtOp(value: Any?, op: String): Any? = when (value) {
        is JSONObject -> when (op) {
            "text", "html", "ownText", "textNodes", "allText" -> value
            else -> value.opt(op)
        }
        is Map<*, *> -> when (op) {
            "text", "html", "ownText", "textNodes", "allText" -> value
            else -> value[op]
        }
        is List<*> -> value.map { applyJsonAtOp(it, op) }
        else -> value
    }

    private fun valueToString(value: Any?): String = when (value) {
        null -> ""
        is List<*> -> value.joinToString("\n") { LenientJson.valueToString(it) }
        else -> LenientJson.valueToString(value)
    }

    private fun applyReplace(value: String, spec: ReplaceSpec): String {
        if (spec.regex.isEmpty() || value.isEmpty()) return value
        return try {
            val regex = Regex(spec.regex)
            if (spec.firstOnly) {
                val match = regex.find(value) ?: return value
                value.replaceRange(match.range, expandReplacement(spec.replacement, match))
            } else {
                regex.replace(value) { m -> expandReplacement(spec.replacement, m) }
            }
        } catch (e: Exception) {
            // Bad regex in a source: literal fallback, like Legado.
            value.replace(spec.regex, spec.replacement)
        }
    }

    /** $1 / $2 group references in a ## replacement, Legado-style. */
    private fun expandReplacement(replacement: String, match: MatchResult): String {
        var out = replacement
        for (g in match.groups.size - 1 downTo 1) {
            out = out.replace("$" + g, match.groups[g]?.value ?: "")
        }
        return out.replace("\\n", "\n").replace("\\t", "\t")
    }

    fun absolutize(value: String): String {
        val v = value.trim()
        if (v.isEmpty() || v.contains("://") || v.startsWith("data:") ||
            v.startsWith("javascript:") || v.startsWith("magnet:")
        ) return v
        return try {
            URL(URL(baseUrl), v).toString()
        } catch (e: Exception) {
            v
        }
    }

    // ------------------------------------------------------------ makeup

    internal fun makeUp(text: String): String {
        val sb = StringBuilder()
        var i = 0
        val n = text.length
        while (i < n) {
            when {
                text.startsWith("{{", i) -> {
                    // The opener is two braces, so the scan starts at
                    // depth 2 and the closer is the matching `}}`.
                    val end = findBalanced(text, i + 2, '{', '}', initialDepth = 2)
                    if (end < 0) {
                        sb.append(text.substring(i)); i = n
                    } else {
                        val expr = text.substring(i + 2, end - 1)
                        sb.append(evalTemplate(expr))
                        i = end + 1
                    }
                }
                text.startsWith("<js>", i) -> {
                    val end = text.indexOf("</js>", i + 4)
                    if (end < 0) {
                        sb.append(text.substring(i)); i = n
                    } else {
                        val code = text.substring(i + 4, end)
                        sb.append(js.evalString(code, content))
                        i = end + 5
                    }
                }
                text[i] == '{' && i + 1 < n && text[i + 1] == '$' -> {
                    // Single-brace JsonPath template: {$.path}
                    val end = findBalanced(text, i + 1, '{', '}')
                    if (end < 0) {
                        sb.append(text[i]); i++
                    } else {
                        val expr = text.substring(i + 1, end)
                        sb.append(evalTemplate(expr))
                        i = end + 1
                    }
                }
                text.startsWith("@get:{", i) -> {
                    val end = findBalanced(text, i + 6, '{', '}')
                    if (end < 0) {
                        sb.append(text.substring(i)); i = n
                    } else {
                        val keyName = text.substring(i + 6, end)
                        sb.append(js.vars[keyName] ?: "")
                        i = end + 1
                    }
                }
                text.startsWith("\\n", i) -> {
                    // Sources write literal \n in display text (intros).
                    sb.append('\n'); i += 2
                }
                text.startsWith("\\t", i) -> {
                    sb.append('\t'); i += 2
                }
                else -> {
                    sb.append(text[i]); i++
                }
            }
        }
        return sb.toString()
    }

    private fun evalTemplate(expr: String): String {
        val e = expr.trim()
        if (e.isEmpty()) return ""
        return if (isRuleExpr(e)) {
            getStringSingle(e, false)
        } else {
            js.evalString(e, content)
        }
    }

    private fun isRuleExpr(e: String): Boolean =
        e.startsWith("$") || e.startsWith("@") || e.startsWith("//") ||
            e.matches(Regex("^(class|id|tag|text)\\..*")) ||
            (e.startsWith(".") && !e.contains("("))

    /** Remove every @put:{...} from [text], evaluating entries into vars. */
    private fun extractPuts(text: String): String {
        var out = text
        while (true) {
            val idx = out.indexOf("@put:")
            if (idx < 0) break
            val brace = out.indexOf('{', idx)
            if (brace < 0) break
            val end = findBalanced(out, brace + 1, '{', '}')
            if (end < 0) break
            val mapText = out.substring(brace + 1, end)
            for (entry in splitTopLevel(mapText, ",")) {
                val colon = entry.indexOf(':')
                if (colon <= 0) continue
                val k = entry.substring(0, colon).trim().trim('"', '\'')
                val v = entry.substring(colon + 1).trim()
                if (k.isNotEmpty()) js.vars[k] = getStringSingle(v, false)
            }
            out = out.removeRange(idx, end + 1)
        }
        return out
    }

    // ------------------------------------------------------------ content views

    private fun contentString(): String = when (val c = content) {
        is String -> c
        null -> ""
        // JS `result` on an element is the Java map/list form in Legado.
        is JSONObject, is JSONArray -> LenientJson.javaStyleString(c)
        is Element -> c.outerHtml()
        is Document -> c.outerHtml()
        is List<*> -> c.joinToString("\n") { LenientJson.valueToString(it) }
        else -> c.toString()
    }

    private fun isJsonContent(): Boolean = jsonContent() != null

    private fun jsonContent(): Any? {
        if (jsonRoot !== UNSET) return jsonRoot
        jsonRoot = when (val c = content) {
            is JSONObject, is JSONArray, is Map<*, *>, is List<*> -> c
            is String -> {
                val t = c.trim()
                if (t.startsWith("{") || t.startsWith("[")) LenientJson.parseAny(t) else null
            }
            else -> null
        }
        return jsonRoot
    }

    private fun soupDocument(): Document {
        soupRoot?.let { return it }
        val doc = when (val c = content) {
            is Document -> c
            is Element -> {
                val d = Document(baseUrl)
                d.appendChild(c.clone())
                d
            }
            is String -> Jsoup.parse(c, baseUrl)
            else -> Jsoup.parse(contentString(), baseUrl)
        }
        soupRoot = doc
        return doc
    }

    private fun soupScope(): Element = when (val c = content) {
        is Element -> c
        is Document -> c
        else -> soupDocument()
    }

    // ------------------------------------------------------------ JSoup chain

    private fun soupElements(chain: String): List<Any> {
        val steps = splitChain(chain)
        if (steps.isEmpty()) return listOf(soupScope())
        var elements: List<Element> = applySelector(soupScope(), steps[0])
        for (step in steps.drop(1)) {
            if (isTerminalStep(step, last = false)) break
            val next = ArrayList<Element>()
            for (el in elements) next.addAll(applySelector(el, step))
            elements = next
        }
        return elements
    }

    private fun soupString(chain: String, wantList: Boolean): Any {
        val steps = splitChain(chain)
        if (steps.isEmpty()) return ""
        var elements: List<Element> = applySelector(soupScope(), steps[0])
        var terminal: String? = null
        for ((index, step) in steps.drop(1).withIndex()) {
            val isLast = index == steps.size - 2
            if (isTerminalStep(step, last = isLast)) {
                terminal = step
                break
            }
            val next = ArrayList<Element>()
            for (el in elements) next.addAll(applySelector(el, step))
            elements = next
        }
        val values = elements.map { el -> extractValue(el, terminal) }
        return if (wantList) values else values.firstOrNull() ?: ""
    }

    private fun extractValue(el: Element, terminal: String?): String = when (terminal) {
        null, "text" -> el.text()
        "html" -> el.html()
        "ownText" -> el.ownText()
        "textNodes" -> el.textNodes().joinToString("") { it.text() }.trim()
        "allText" -> el.wholeText().trim()
        "href" -> el.absUrl("href").ifBlank { el.attr("href") }
        "src" -> el.absUrl("src").ifBlank { el.attr("src") }
        else -> el.attr(terminal)
    }

    private fun isTerminalStep(step: String, last: Boolean): Boolean {
        if (step == "text" || step == "html" || step == "ownText" ||
            step == "textNodes" || step == "allText"
        ) return true
        if (!last) return false
        // A trailing bare word that is not a tag/selector is an attribute.
        return step.matches(Regex("[a-zA-Z][\\w-]*")) && step !in KNOWN_TAGS
    }

    private fun applySelector(scope: Element, rawStep: String): List<Element> {
        var step = rawStep.trim()
        if (step.isEmpty()) return listOf(scope)
        // trailing index spec on the whole step: [n], [n:m], [!a:b:c]
        // (exclusion), [a:b:c] (explicit picks). Only digit/colon/bang/
        // minus specs qualify, so CSS attribute selectors never match.
        var range: String? = null
        val specMatch = Regex("\\[([!0-9:\\-]+)\\]$").find(step)
        if (specMatch != null) {
            range = specMatch.value
            step = step.removeSuffix(specMatch.value)
        }
        // index suffix: selector.-1 / selector.2 (Legado positional form)
        var position: Int? = null
        val posMatch = Regex("^(.+)\\.(-?\\d+)$").find(step)
        if (posMatch != null && range == null) {
            val head = posMatch.groupValues[1]
            // Only when the head is itself a selector (has . # or is a tag)
            if (head.contains('.') || head.contains('#') || head in KNOWN_TAGS ||
                head.startsWith("class.") || head.startsWith("tag.")
            ) {
                position = posMatch.groupValues[2].toInt()
                step = head
            }
        }
        val css = normalizeSelector(step)
        var found: List<Element> = try {
            if (scope is Document) scope.select(css) else scope.select(css)
        } catch (e: Exception) {
            emptyList()
        }
        // scope.select includes the scope element itself only via parents;
        // Legado evaluates against children/descendants: JSoup select on an
        // element already searches descendants, which matches.
        if (position != null) {
            val idx = if (position < 0) found.size + position else position
            found = if (idx in found.indices) listOf(found[idx]) else emptyList()
        }
        if (range != null) found = applyRange(found, range)
        return found
    }

    private fun applyRange(elements: List<Element>, range: String): List<Element> {
        val inner = range.trim('[', ']').trim()
        fun wrap(idx: Int): Int = if (idx < 0) elements.size + idx else idx
        if (inner.startsWith("!")) {
            // Legado exclusion form: [!a:b:c] drops those indices.
            val excluded = inner.substring(1).split(":")
                .mapNotNull { it.trim().toIntOrNull() }
                .map { wrap(it) }
                .toSet()
            return elements.filterIndexed { idx, _ -> idx !in excluded }
        }
        val parts = inner.split(":")
        return when {
            parts.size == 1 -> {
                val idx = parts[0].trim().toIntOrNull() ?: return elements
                val i = wrap(idx)
                if (i in elements.indices) listOf(elements[i]) else emptyList()
            }
            parts.size == 2 -> {
                val start = parts[0].trim().toIntOrNull() ?: 0
                val end = parts[1].trim().toIntOrNull() ?: elements.size
                val s = wrap(start).coerceIn(0, elements.size)
                val e = wrap(end).coerceIn(0, elements.size)
                if (s >= e) emptyList() else elements.subList(s, e)
            }
            else -> {
                // Explicit picks: [a:b:c] keeps exactly those, in order.
                parts.mapNotNull { p ->
                    val i = p.trim().toIntOrNull()?.let { wrap(it) }
                    if (i != null && i in elements.indices) elements[i] else null
                }
            }
        }
    }

    private fun normalizeSelector(step: String): String {
        var s = step.trim()
        when {
            s.startsWith("class.") -> s = "." + s.substring(6)
            s.startsWith("id.") -> s = "#" + s.substring(3)
            s.startsWith("tag.") -> s = s.substring(4)
            s.startsWith("text.") -> s = ":contains(" + s.substring(5) + ")"
        }
        // compound leading class./tag. after a combinator (rare) — leave CSS as-is.
        return s
    }

    private fun splitChain(chain: String): List<String> = splitTopLevel(chain, "@")

    // ------------------------------------------------------------ XPath

    private fun xpathDocument(): org.w3c.dom.Document {
        val doc = soupDocument()
        return W3CDom().fromJsoup(doc)
    }

    private fun xpathString(expr: String): String {
        return try {
            val xPath = XPathFactory.newInstance().newXPath()
            val result = xPath.evaluate(expr, xpathDocument(), XPathConstants.NODESET)
                as? org.w3c.dom.NodeList ?: return ""
            if (result.length == 0) return ""
            val node = result.item(0)
            when (node.nodeType) {
                org.w3c.dom.Node.ATTRIBUTE_NODE -> node.nodeValue ?: ""
                else -> node.textContent?.trim() ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun xpathElements(expr: String): List<Any> {
        return try {
            val xPath = XPathFactory.newInstance().newXPath()
            val result = xPath.evaluate(expr, xpathDocument(), XPathConstants.NODESET)
                as? org.w3c.dom.NodeList ?: return emptyList()
            (0 until result.length).map { result.item(it).textContent?.trim() ?: "" }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------ splitting

    /** Split a rule into newline pipeline stages (top level only). */
    private fun splitStages(rule: String): List<String> {
        val lines = splitTopLevel(rule, "\n").map { it.trim() }.filter { it.isNotEmpty() }
        return if (lines.size <= 1) listOf(rule.trim()) else lines
    }

    companion object {
        private val UNSET = Any()

        private val KNOWN_TAGS = setOf(
            "a", "div", "span", "li", "ul", "ol", "img", "p", "h1", "h2", "h3",
            "h4", "h5", "h6", "section", "article", "table", "tr", "td", "input",
            "button", "i", "b", "em", "strong", "small", "dl", "dt", "dd",
        )

        /**
         * Split on a delimiter that sits at the top level — outside
         * {{...}}, <js>...</js>, quotes and ()/[]/{} nesting.
         */
        fun splitTopLevel(text: String, delimiter: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var i = 0
            val n = text.length
            var depth = 0
            var inSingle = false
            var inDouble = false
            while (i < n) {
                if (text.startsWith("<js>", i)) {
                    val end = text.indexOf("</js>", i + 4)
                    if (end >= 0) {
                        sb.append(text, i, end + 5)
                        i = end + 5
                        continue
                    }
                }
                val c = text[i]
                if (inSingle) {
                    sb.append(c)
                    if (c == '\'') inSingle = false
                    i++
                    continue
                }
                if (inDouble) {
                    sb.append(c)
                    if (c == '\\' && i + 1 < n) {
                        sb.append(text[i + 1]); i += 2; continue
                    }
                    if (c == '"') inDouble = false
                    i++
                    continue
                }
                when (c) {
                    '\'' -> { inSingle = true; sb.append(c) }
                    '"' -> { inDouble = true; sb.append(c) }
                    '(', '[', '{' -> { depth++; sb.append(c) }
                    ')', ']', '}' -> { depth--; sb.append(c) }
                    else -> {
                        if (depth == 0 && text.startsWith(delimiter, i)) {
                            out.add(sb.toString())
                            sb.setLength(0)
                            i += delimiter.length
                            continue
                        }
                        sb.append(c)
                    }
                }
                i++
            }
            out.add(sb.toString())
            return out
        }

        /** Index of the closing brace matching the one just before [from]. */
        fun findBalanced(
            text: String,
            from: Int,
            open: Char,
            close: Char,
            initialDepth: Int = 1,
        ): Int {
            var depth = initialDepth
            var i = from
            var inSingle = false
            var inDouble = false
            while (i < text.length) {
                val c = text[i]
                if (inSingle) {
                    if (c == '\'') inSingle = false
                } else if (inDouble) {
                    if (c == '\\') i++
                    else if (c == '"') inDouble = false
                } else {
                    when (c) {
                        '\'' -> inSingle = true
                        '"' -> inDouble = true
                        open -> depth++
                        close -> {
                            depth--
                            if (depth == 0) return i
                        }
                    }
                }
                i++
            }
            return -1
        }

        /** Split "@js:" transform suffix off a JSONPath string. */
        fun stripJsSuffix(path: String): Pair<String, String?> {
            val idx = path.indexOf("@js:")
            return if (idx < 0) path to null
            else path.substring(0, idx) to path.substring(idx + 4)
        }

        /** Split "@js:" transform suffix off a JSoup chain. */
        fun stripAtJsSuffix(chain: String): Pair<String, String?> = stripJsSuffix(chain)
    }
}

/** Shared one-line failure logging for field-level rule isolation. */
internal object LegadoLog {
    fun fieldError(source: BookSource, where: String, rule: String, e: Throwable) {
        val short = if (rule.length > 80) rule.substring(0, 80) + "..." else rule
        LOG.d("AnalyzeRule", "[$where] ${source.bookSourceName} rule failed: $short -> ${e.message}")
    }
}
