package com.github.tvbox.osc.reading

import com.github.tvbox.osc.util.LOG
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 漫画 ruleContent.imageDecode 的精简实现。
 * 覆盖漫蛙等常见「AES/CBC + key 作 IV」；从脚本提取默认 key。
 */
object LegadoImageDecode {

    private val scriptBySource = ConcurrentHashMap<String, String>()

    fun register(sourceKey: String, imageDecodeJs: String?) {
        if (sourceKey.isBlank()) return
        if (imageDecodeJs.isNullOrBlank()) scriptBySource.remove(sourceKey)
        else scriptBySource[sourceKey] = imageDecodeJs
    }

    fun decrypt(sourceKey: String?, bytes: ByteArray): ByteArray {
        if (sourceKey.isNullOrBlank() || bytes.isEmpty()) return bytes
        if (scriptBySource[sourceKey] == null) return bytes
        if (looksLikeImageMagic(bytes)) return bytes
        val js = scriptBySource[sourceKey] ?: return bytes
        val key = extractAesKey(js) ?: return bytes
        return runCatching { aesCbcPkcs7(bytes, key) }
            .onFailure { LOG.e("LegadoImageDecode", it) }
            .getOrDefault(bytes)
            .let { out -> if (out.isEmpty()) bytes else out }
    }

    private fun looksLikeImageMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return true
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) return true
        if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte()) return true
        if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()) return true
        return false
    }

    fun extractAesKey(js: String): String? {
        Regex("""\|\|\s*["']([^"']{8,64})["']""").find(js)?.let { return it.groupValues[1] }
        Regex("""key\s*=\s*["']([^"']{8,64})["']""").find(js)?.let { return it.groupValues[1] }
        if (js.contains("manwa", ignoreCase = true) || js.contains("createSymmetricCrypto")) {
            return "my2ecret782ecret"
        }
        return null
    }

    fun aesCbcPkcs7(bytes: ByteArray, keyText: String): ByteArray {
        val keyBytes = keyText.toByteArray(Charsets.UTF_8)
        val key = when {
            keyBytes.size >= 32 -> keyBytes.copyOf(32)
            keyBytes.size >= 24 -> keyBytes.copyOf(24)
            else -> keyBytes.copyOf(16)
        }
        val iv = key.copyOf(16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(bytes)
    }
}
