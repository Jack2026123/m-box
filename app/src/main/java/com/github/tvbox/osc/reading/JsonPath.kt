package com.github.tvbox.osc.reading

import org.json.JSONArray
import org.json.JSONObject

/**
 * The JSONPath subset Legado sources actually use (Jayway semantics):
 * `$`, `$.a.b`, `$['a']`, `$.a[0]`, `$.a[-1]`, `$.a[*]`, `$.*`,
 * `$.a[1:3]` slices, `$..name` (recursive descent, document order),
 * `$..a.b`, `$..*`, and basic filters `$..[?(@.x == 'v')]`.
 *
 * A path is "definite" when it contains no `..`, `*` or filter; definite
 * paths yield a single value (or null), indefinite paths yield a list.
 */
object JsonPath {

    private sealed interface Step {
        data class Child(val name: String) : Step
        data class Index(val index: Int) : Step
        object Wildcard : Step
        data class Union(val names: List<String>) : Step
        data class Slice(val start: Int?, val end: Int?) : Step
        data class Filter(val expr: String) : Step
        data class Descent(val next: Step?) : Step // `..` followed by a step
    }

    fun isDefinite(path: String): Boolean =
        !path.contains("..") && !path.contains('*') && !path.contains("?(")

    fun read(root: Any?, path: String): Any? {
        val p = path.trim()
        if (p == "$" || p.isEmpty()) return root
        if (!p.startsWith("$")) return null
        val steps = parse(p) ?: return null
        var current: List<Any?> = listOf(root)
        var definite = true
        for (step in steps) {
            val out = ArrayList<Any?>()
            when (step) {
                is Step.Child -> for (node in current) child(node, step.name, out)
                is Step.Index -> for (node in current) index(node, step.index, out)
                Step.Wildcard -> {
                    definite = false
                    for (node in current) wildcard(node, out)
                }
                is Step.Union -> {
                    definite = false
                    for (node in current) for (name in step.names) child(node, name, out)
                }
                is Step.Slice -> {
                    definite = false
                    for (node in current) slice(node, step, out)
                }
                is Step.Filter -> {
                    definite = false
                    for (node in current) filter(node, step.expr, out)
                }
                is Step.Descent -> {
                    definite = false
                    val collected = ArrayList<Any?>()
                    for (node in current) descend(node, collected)
                    when (val next = step.next) {
                        null -> out.addAll(collected)
                        // Jayway scan semantics: the step after `..` is a
                        // strict member lookup on every subtree node (its
                        // elements are subtree nodes themselves, so array
                        // spreading here would double-count).
                        is Step.Child -> for (node in collected) childStrict(node, next.name, out)
                        is Step.Index -> for (node in collected) index(node, next.index, out)
                        Step.Wildcard -> for (node in collected) wildcard(node, out)
                        is Step.Union -> for (node in collected) for (name in next.names) childStrict(node, name, out)
                        is Step.Slice -> for (node in collected) slice(node, next, out)
                        is Step.Filter -> for (node in collected) filter(node, next.expr, out)
                        is Step.Descent -> out.addAll(collected)
                    }
                }
            }
            current = out
            if (current.isEmpty()) break
        }
        return if (definite) current.firstOrNull() else current
    }

    private fun parse(path: String): List<Step>? {
        val steps = ArrayList<Step>()
        var i = 1 // skip $
        val n = path.length
        while (i < n) {
            when {
                path.startsWith("..", i) -> {
                    i += 2
                    if (i >= n) {
                        steps.add(Step.Descent(null))
                        break
                    }
                    // descent applies to the following step
                    val next: Step? = when {
                        path[i] == '*' -> { i++; Step.Wildcard }
                        path[i] == '[' -> {
                            val end = findCloseBracket(path, i)
                            if (end < 0) return null
                            val inner = path.substring(i + 1, end).trim()
                            i = end + 1
                            parseBracket(inner)
                        }
                        else -> {
                            var j = i
                            while (j < n && path[j] != '.' && path[j] != '[') j++
                            val name = path.substring(i, j)
                            i = j
                            Step.Child(name)
                        }
                    }
                    steps.add(Step.Descent(next ?: return null))
                }
                path[i] == '.' -> {
                    i++
                    if (i < n && path[i] == '*') {
                        steps.add(Step.Wildcard); i++
                    } else {
                        var j = i
                        while (j < n && path[j] != '.' && path[j] != '[') j++
                        if (j > i) steps.add(Step.Child(path.substring(i, j)))
                        i = j
                    }
                }
                path[i] == '[' -> {
                    val end = findCloseBracket(path, i)
                    if (end < 0) return null
                    val inner = path.substring(i + 1, end).trim()
                    steps.add(parseBracket(inner) ?: return null)
                    i = end + 1
                    // Tolerate an implicit child right after a bracket
                    // (`$.categoryNames[*]className`): Legado book rules
                    // rely on this Jayway shorthand.
                    if (i < n && (path[i].isLetter() || path[i] == '_')) {
                        var j = i
                        while (j < n && path[j] != '.' && path[j] != '[') j++
                        steps.add(Step.Child(path.substring(i, j)))
                        i = j
                    }
                }
                else -> return null
            }
        }
        return steps
    }

    /** Index of the `]` closing the bracket opened at [open]; quote/paren aware. */
    private fun findCloseBracket(path: String, open: Int): Int {
        var depth = 0
        var quote: Char? = null
        var i = open
        while (i < path.length) {
            val c = path[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '[' || c == '(' -> depth++
                c == ']' -> {
                    depth--
                    if (depth == 0) return i
                }
                c == ')' -> depth--
            }
            i++
        }
        return -1
    }

    private fun parseBracket(inner: String): Step? {
        if (inner == "*") return Step.Wildcard
        if (inner.startsWith("?(") && inner.endsWith(")")) {
            return Step.Filter(inner.substring(2, inner.length - 1))
        }
        if (inner.matches(Regex("-?\\d+"))) return Step.Index(inner.toInt())
        if (inner.contains(',')) {
            val names = inner.split(",").map { it.trim().trim('\'', '"') }
            return Step.Union(names)
        }
        if (inner.contains(':')) {
            val parts = inner.split(":")
            if (parts.size == 2 && parts.all { it.isEmpty() || it.toIntOrNull() != null }) {
                return Step.Slice(parts[0].toIntOrNull(), parts[1].toIntOrNull())
            }
        }
        if (inner.isEmpty()) return null
        return Step.Child(inner.trim('\'', '"'))
    }

    private fun child(node: Any?, name: String, out: MutableList<Any?>) {
        when (node) {
            is JSONObject -> if (node.has(name)) out.add(node.get(name))
            is Map<*, *> -> if (node.containsKey(name)) out.add(node[name])
            is JSONArray -> for (k in 0 until node.length()) child(node.opt(k), name, out)
            is List<*> -> for (item in node) child(item, name, out)
        }
    }

    /** Member lookup on one node only — no array spreading (descent steps). */
    private fun childStrict(node: Any?, name: String, out: MutableList<Any?>) {
        when (node) {
            is JSONObject -> if (node.has(name)) out.add(node.get(name))
            is Map<*, *> -> if (node.containsKey(name)) out.add(node[name])
        }
    }

    private fun index(node: Any?, idx: Int, out: MutableList<Any?>) {
        when (node) {
            is JSONArray -> {
                val i = if (idx < 0) node.length() + idx else idx
                if (i in 0 until node.length()) out.add(node.get(i))
            }
            is List<*> -> {
                val i = if (idx < 0) node.size + idx else idx
                if (i in node.indices) out.add(node[i])
            }
        }
    }

    private fun wildcard(node: Any?, out: MutableList<Any?>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) out.add(node.get(keys.next()))
            }
            is JSONArray -> for (k in 0 until node.length()) out.add(node.get(k))
            is List<*> -> out.addAll(node)
            is Map<*, *> -> out.addAll(node.values)
        }
    }

    private fun slice(node: Any?, step: Step.Slice, out: MutableList<Any?>) {
        val size = when (node) {
            is JSONArray -> node.length()
            is List<*> -> node.size
            else -> return
        }
        var start = step.start ?: 0
        var end = step.end ?: size
        if (start < 0) start += size
        if (end < 0) end += size
        start = start.coerceIn(0, size)
        end = end.coerceIn(0, size)
        if (start >= end) return
        for (k in start until end) {
            val v = if (node is JSONArray) node.opt(k) else (node as List<*>)[k]
            if (v != null && v !== JSONObject.NULL) out.add(v)
        }
    }

    private fun filter(node: Any?, expr: String, out: MutableList<Any?>) {
        when (node) {
            is JSONArray -> for (k in 0 until node.length()) {
                val item = node.opt(k)
                if (matchesFilter(item, expr)) out.add(item)
            }
            is List<*> -> for (item in node) if (matchesFilter(item, expr)) out.add(item)
            else -> if (matchesFilter(node, expr)) out.add(node)
        }
    }

    private fun matchesFilter(node: Any?, expr: String): Boolean {
        for (orPart in splitTopLevel(expr, "||")) {
            var ok = true
            for (atom in splitTopLevel(orPart, "&&")) {
                if (!evalFilterAtom(node, atom.trim())) {
                    ok = false
                    break
                }
            }
            if (ok) return true
        }
        return false
    }

    private fun evalFilterAtom(node: Any?, atom: String): Boolean {
        if (atom.isEmpty()) return false
        for (op in listOf("==", "!=", ">=", "<=", ">", "<")) {
            val idx = indexOfTopLevel(atom, op)
            if (idx > 0) {
                val actual = resolveAt(node, atom.substring(0, idx).trim())
                val expected = parseLiteral(atom.substring(idx + op.length).trim())
                return compareValues(actual, expected, op)
            }
        }
        // Bare `@.path` = existence check.
        return resolveAt(node, atom) != null
    }

    private fun resolveAt(node: Any?, lhs: String): Any? {
        if (lhs == "@") return node
        if (!lhs.startsWith("@")) return null
        val rest = lhs.substring(1)
        if (rest.isEmpty()) return node
        val v = read(node, "$" + rest)
        return if (v === JSONObject.NULL) null else v
    }

    private fun parseLiteral(text: String): Any? {
        val t = text.trim()
        if (t.length >= 2 &&
            (t.first() == '\'' && t.last() == '\'' || t.first() == '"' && t.last() == '"')
        ) {
            return t.substring(1, t.length - 1)
        }
        return when (t) {
            "true" -> true
            "false" -> false
            "null" -> null
            else -> t.toDoubleOrNull() ?: t
        }
    }

    private fun compareValues(actual: Any?, expected: Any?, op: String): Boolean {
        if (op == "==" || op == "!=") {
            val eq = valuesEqual(actual, expected)
            return if (op == "==") eq else !eq
        }
        val a = (actual as? Number)?.toDouble()
            ?: actual?.toString()?.toDoubleOrNull() ?: return false
        val b = (expected as? Number)?.toDouble()
            ?: expected?.toString()?.toDoubleOrNull() ?: return false
        return when (op) {
            ">" -> a > b
            "<" -> a < b
            ">=" -> a >= b
            "<=" -> a <= b
            else -> false
        }
    }

    private fun valuesEqual(a: Any?, b: Any?): Boolean {
        if (a == null || a === JSONObject.NULL) return b == null
        if (b == null) return false
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        if (a is Number || b is Number) {
            val na = a.toString().toDoubleOrNull()
            val nb = b.toString().toDoubleOrNull()
            if (na != null && nb != null) return na == nb
        }
        return a.toString() == b.toString()
    }

    /** Split on [delim] at paren depth 0, ignoring delimiters inside quotes. */
    private fun splitTopLevel(text: String, delim: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var quote: Char? = null
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '(' || c == '[' -> depth++
                c == ')' || c == ']' -> depth--
                depth == 0 && text.startsWith(delim, i) -> {
                    out.add(text.substring(start, i))
                    i += delim.length
                    start = i
                    continue
                }
            }
            i++
        }
        out.add(text.substring(start))
        return out
    }

    private fun indexOfTopLevel(text: String, token: String): Int {
        var depth = 0
        var quote: Char? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '(' || c == '[' -> depth++
                c == ')' || c == ']' -> depth--
                depth == 0 && text.startsWith(token, i) -> return i
            }
            i++
        }
        return -1
    }

    /** All descendants (including self) in document order. */
    private fun descend(node: Any?, out: MutableList<Any?>) {
        if (node == null) return
        out.add(node)
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) descend(node.get(keys.next()), out)
            }
            is JSONArray -> for (k in 0 until node.length()) descend(node.get(k), out)
            is List<*> -> for (item in node) descend(item, out)
            is Map<*, *> -> for (v in node.values) descend(v, out)
        }
    }
}
