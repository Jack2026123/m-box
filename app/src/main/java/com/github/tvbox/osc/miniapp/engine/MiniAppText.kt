package com.github.tvbox.osc.miniapp.engine

import java.net.URL
import java.security.MessageDigest

object MiniAppText {

    fun isEmpty(s: CharSequence?): Boolean = s == null || s.isEmpty()

    fun isNotEmpty(s: CharSequence?): Boolean = !isEmpty(s)

    fun trimBlanks(s: String?): String? {
        if (s == null || s.isEmpty()) return s
        var start = 0
        var end = s.length
        while (start < end && isBlankChar(s[start])) start++
        while (end > start && isBlankChar(s[end - 1])) end--
        return s.substring(start, end)
    }

    private fun isBlankChar(c: Char): Boolean =
        c == '\n' || c == '\r' || c == '\u000C' || c == '\t'

    fun decodeConflictStr(s: String?): String? {
        if (isEmpty(s)) return s
        return s!!.replace("？？", "?").replace("＆＆", "&").replace("；；", ";").replace("，，", ",")
    }

    fun getBaseUrl(url: String?): String? {
        if (isEmpty(url)) return url
        val host = url!!.replace("http://", "").replace("https://", "").split("/")[0]
        return if (url.startsWith("https")) "https://$host" else "http://$host"
    }

    fun getRealUrl(url: String?): String? {
        if (isEmpty(url)) return url
        var s = url!!.split("@lazyRule=")[0].split("@rule=")[0]
        if (s.startsWith("hiker://empty#")) {
            s = s.replaceFirst("hiker://empty#", "")
        } else if (s.startsWith("webview://")) {
            s = s.replaceFirst("webview://", "")
        }
        return s
    }

    fun getHome(url: String?): String? {
        if (isEmpty(url)) return url
        val real = getRealUrl(url) ?: return url
        if (real.startsWith("thunder://") || real.startsWith("magnet:") ||
            real.startsWith("ftp://") || real.startsWith("ed2k:") || real.startsWith("file://")
        ) {
            return real
        }
        val schemeParts = real.split("://")
        if (schemeParts.size > 1) {
            return schemeParts[0] + "://" + schemeParts[1].split("/")[0]
        }
        val slashParts = real.split("//")
        if (slashParts.size > 1) {
            return slashParts[0] + "//" + slashParts[1].split("/")[0]
        }
        return real
    }

    fun splitUrlByQuestionMark(url: String?): Array<String> {
        if (isEmpty(url)) return arrayOf(url ?: "")
        val split = url!!.split("?")
        return if (split.size <= 1) split.toTypedArray()
        else arrayOf(split[0], arrayToString(split.toTypedArray(), 1, "?"))
    }

    fun arrayToString(arr: Array<String>?, start: Int, sep: String): String =
        arrayToString(arr, start, arr?.size ?: 0, sep)

    fun arrayToString(arr: Array<String>?, start: Int, end: Int, sep: String): String {
        if (arr == null || arr.size <= start) return ""
        if (arr.size <= 1) return arr[0]
        val sb = StringBuilder(arr[start])
        var i = start + 1
        while (i < arr.size && i < end) {
            sb.append(sep).append(arr[i])
            i++
        }
        return sb.toString()
    }

    fun listToString(list: List<String>?, sep: String): String {
        if (list.isNullOrEmpty()) return ""
        if (list.size <= 1) return list[0]
        val sb = StringBuilder(list[0])
        for (i in 1 until list.size) sb.append(sep).append(list[i])
        return sb.toString()
    }

    fun listToString(list: List<String>?, start: Int, sep: String): String {
        if (list.isNullOrEmpty() || list.size <= start) return ""
        if (list.size <= 1) return list[0]
        val sb = StringBuilder(list[start])
        for (i in start + 1 until list.size) sb.append(sep).append(list[i])
        return sb.toString()
    }

    fun escapeJsString(s: String?): String {
        if (s == null) return ""
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun md5(s: String?): String {
        if (s == null) return ""
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString()
    }

    fun hexToBytes(s: String?): ByteArray? {
        if (s == null || s.isEmpty()) return null
        val out = ByteArray(s.length / 2)
        var i = 0
        var j = 0
        while (i + 1 < s.length) {
            out[j] = Integer.parseInt(s.substring(i, i + 2), 16).toByte()
            i += 2
            j++
        }
        return out
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString()
    }

    private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun base64Encode(s: String): String = base64EncodeBytes(s.toByteArray(Charsets.UTF_8))

    fun base64EncodeBytes(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            sb.append(B64_ALPHABET[b0 shr 2])
            sb.append(B64_ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < bytes.size) B64_ALPHABET[((b1 and 0x0F) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < bytes.size) B64_ALPHABET[b2 and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun base64Decode(s: String): String {
        val bytes = base64DecodeBytes(s) ?: return ""
        return String(bytes, Charsets.UTF_8)
    }

    fun base64DecodeBytes(s: String): ByteArray? {
        return try {
            val clean = s.filter { it != '\n' && it != '\r' && it != ' ' }
            val out = ArrayList<Byte>()
            var i = 0
            while (i + 1 < clean.length) {
                val c0 = B64_ALPHABET.indexOf(clean[i])
                val c1 = B64_ALPHABET.indexOf(clean[i + 1])
                if (c0 < 0 || c1 < 0) break
                out.add(((c0 shl 2) or (c1 shr 4)).toByte())
                if (i + 2 < clean.length && clean[i + 2] != '=') {
                    val c2 = B64_ALPHABET.indexOf(clean[i + 2])
                    if (c2 < 0) break
                    out.add((((c1 and 0x0F) shl 4) or (c2 shr 2)).toByte())
                    if (i + 3 < clean.length && clean[i + 3] != '=') {
                        val c3 = B64_ALPHABET.indexOf(clean[i + 3])
                        if (c3 < 0) break
                        out.add((((c2 and 0x03) shl 6) or c3).toByte())
                    }
                }
                i += 4
            }
            out.toByteArray()
        } catch (e: Throwable) {
            null
        }
    }

    fun joinUrl(base: String?, rel: String): String {
        if (isEmpty(base)) return rel
        return try {
            URL(URL(base), rel).toExternalForm()
        } catch (e: Throwable) {
            base!!
        }
    }
}
