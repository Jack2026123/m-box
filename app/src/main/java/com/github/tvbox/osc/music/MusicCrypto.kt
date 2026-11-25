package com.github.tvbox.osc.music

import android.util.Base64
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MusicFree 插件的加解密桥。
 * 插件里的 crypto-js 调用全部转到这里。
 */
object MusicCrypto {

    fun run(op: String, a: String?, b: String?, c: String?): String {
        return try {
            when (op) {
                "md5" -> digest("MD5", a.orEmpty())
                "sha1" -> digest("SHA-1", a.orEmpty())
                "sha256" -> digest("SHA-256", a.orEmpty())
                "hmacSha1" -> hmac("HmacSHA1", a.orEmpty(), b.orEmpty())
                "hmacSha256" -> hmac("HmacSHA256", a.orEmpty(), b.orEmpty())
                "base64Encode" -> Base64.encodeToString(
                    a.orEmpty().toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP,
                )
                "base64Decode" -> runCatching {
                    String(Base64.decode(a.orEmpty(), Base64.DEFAULT), Charsets.UTF_8)
                }.getOrDefault("")
                "hexEncode" -> toHex(a.orEmpty().toByteArray(Charsets.UTF_8))
                "hexDecode" -> runCatching {
                    val hex = a.orEmpty()
                    ByteArray(hex.length / 2) { i ->
                        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                    }.toString(Charsets.UTF_8)
                }.getOrDefault("")
                "aesEncrypt" -> aes(true, a.orEmpty(), b.orEmpty(), c.orEmpty())
                "aesDecrypt" -> aes(false, a.orEmpty(), b.orEmpty(), c.orEmpty())
                else -> ""
            }
        } catch (th: Throwable) {
            com.github.tvbox.osc.util.LOG.e("mf-crypto-failed " + op + " : " + th.message)
            ""
        }
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    private fun digest(alg: String, text: String): String {
        val md = MessageDigest.getInstance(alg)
        return toHex(md.digest(text.toByteArray(Charsets.UTF_8)))
    }

    private fun hmac(alg: String, msg: String, key: String): String {
        val mac = Mac.getInstance(alg)
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), alg))
        return toHex(mac.doFinal(msg.toByteArray(Charsets.UTF_8)))
    }

    /**
     * AES。opt 是 JSON 字符串，可能含 mode / iv。
     * iv 有值走 CBC，没有走 ECB。
     */
    private fun aes(encrypt: Boolean, text: String, key: String, optJson: String): String {
        var mode = "CBC"
        var iv = ""
        runCatching {
            if (optJson.isNotBlank()) {
                val o = org.json.JSONObject(optJson)
                val m = o.optString("mode")
                if (m.isNotBlank()) mode = m.uppercase()
                iv = o.optString("iv")
            }
        }

        val keyBytes = normalizeKey(key.toByteArray(Charsets.UTF_8))
        val useCbc = mode != "ECB" && iv.isNotBlank()
        val transform = if (useCbc) "AES/CBC/PKCS5Padding" else "AES/ECB/PKCS5Padding"

        val cipher = Cipher.getInstance(transform)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        if (useCbc) {
            cipher.init(
                if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                keySpec,
                IvParameterSpec(normalizeKey(iv.toByteArray(Charsets.UTF_8))),
            )
        } else {
            cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, keySpec)
        }

        return if (encrypt) {
            Base64.encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        } else {
            val raw = runCatching { Base64.decode(text, Base64.DEFAULT) }.getOrElse {
                text.toByteArray(Charsets.UTF_8)
            }
            String(cipher.doFinal(raw), Charsets.UTF_8)
        }
    }

    /** AES 密钥必须 16/24/32 字节 */
    private fun normalizeKey(input: ByteArray): ByteArray = when {
        input.size == 16 || input.size == 24 || input.size == 32 -> input
        input.size > 24 -> input.copyOf(32)
        input.size > 16 -> input.copyOf(24)
        else -> input.copyOf(16)
    }

    fun bigIntOp(op: String, x: String, y: String): String {
        return try {
            val a = BigInteger(x.ifBlank { "0" })
            val b = BigInteger(y.ifBlank { "0" })
            when (op) {
                "add" -> a.add(b).toString()
                "sub" -> a.subtract(b).toString()
                "mul" -> a.multiply(b).toString()
                "div" -> if (b == BigInteger.ZERO) "0" else a.divide(b).toString()
                "mod" -> if (b == BigInteger.ZERO) "0" else a.mod(b).toString()
                else -> "0"
            }
        } catch (th: Throwable) {
            "0"
        }
    }
}