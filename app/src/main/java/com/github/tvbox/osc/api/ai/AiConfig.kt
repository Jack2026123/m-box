package com.github.tvbox.osc.api.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.TextUtils
import android.util.Base64
import com.github.tvbox.osc.util.KV
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// 1.45 AI 源配置存储（按 1.0.4 AiConfig 的 AVBox 形态，OpenAI 兼容协议）。
// KV 键沿用 ai_repair_*；Keystore 别名改为 avbox_ai_repair_v1。
// 三条硬约束照搬：Key 不落明文（Keystore AES-256-GCM 加密后存 IV＋密文）、
// Key 不进提示词不进日志（只给 Authorization 头，其余只露 masked 结果）、
// 缺省 Key 一律为空、绝不抄 1.0.4 树里任何现成 key；未配置时引擎直接拒绝。
object AiConfig {

    private const val KEY_ENABLED = "ai_repair_enabled"
    private const val KEY_BASE_URL = "ai_repair_base_url"
    private const val KEY_MODEL = "ai_repair_model"
    private const val KEY_ROUNDS = "ai_repair_rounds"
    private const val KEY_CIPHER = "ai_repair_key_cipher"
    private const val KEY_LOG_ENABLED = "ai_repair_log_enabled"
    private const val KEY_WEB_ENABLED = "ai_repair_web_enabled"

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "avbox_ai_repair_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    const val DEFAULT_MODEL = "gpt-4o-mini"
    const val DEFAULT_ROUNDS = 3
    const val MAX_ROUNDS = 20

    fun isEnabled(): Boolean = KV.get(KEY_ENABLED, true)
    fun setEnabled(value: Boolean) = KV.put(KEY_ENABLED, value)

    fun getBaseUrl(): String {
        val url = KV.get(KEY_BASE_URL, "")
        return if (url.isEmpty()) DEFAULT_BASE_URL else url.trim()
    }
    fun setBaseUrl(url: String) = KV.put(KEY_BASE_URL, url.trim())

    fun getModel(): String {
        val model = KV.get(KEY_MODEL, "")
        return if (model.isEmpty()) DEFAULT_MODEL else model.trim()
    }
    fun setModel(model: String) = KV.put(KEY_MODEL, model.trim())

    fun getRounds(): Int {
        val rounds = KV.get(KEY_ROUNDS, DEFAULT_ROUNDS)
        if (rounds < 1) return 1
        return minOf(rounds, MAX_ROUNDS)
    }
    fun setRounds(rounds: Int) = KV.put(KEY_ROUNDS, maxOf(1, minOf(rounds, MAX_ROUNDS)))

    fun isLogEnabled(): Boolean = KV.get(KEY_LOG_ENABLED, true)
    fun setLogEnabled(value: Boolean) = KV.put(KEY_LOG_ENABLED, value)

    fun isWebEnabled(): Boolean = KV.get(KEY_WEB_ENABLED, true)
    fun setWebEnabled(value: Boolean) = KV.put(KEY_WEB_ENABLED, value)

    fun isConfigured(): Boolean {
        return !TextUtils.isEmpty(getBaseUrl()) && !TextUtils.isEmpty(getApiKey()) && !TextUtils.isEmpty(getModel())
    }

    fun chatUrl(): String = chatUrl(getBaseUrl())

    fun chatUrl(baseUrl: String?): String {
        var base = baseUrl?.trim() ?: ""
        while (base.endsWith("/")) base = base.substring(0, base.length - 1)
        if (base.endsWith("/chat/completions")) return base
        return base + "/chat/completions"
    }

    fun getApiKey(): String {
        val cipherText = KV.get(KEY_CIPHER, "")
        if (cipherText.isEmpty()) return ""
        return try {
            val packed = Base64.decode(cipherText, Base64.NO_WRAP)
            if (packed.size <= 12) return ""
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, packed, 0, 12))
            val plain = cipher.doFinal(packed, 12, packed.size - 12)
            String(plain, StandardCharsets.UTF_8)
        } catch (e: Throwable) {
            ""
        }
    }

    fun setApiKey(apiKey: String?) {
        if (apiKey.isNullOrEmpty()) {
            KV.put(KEY_CIPHER, "")
            return
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(apiKey.trim().toByteArray(StandardCharsets.UTF_8))
            val packed = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, packed, 0, iv.size)
            System.arraycopy(encrypted, 0, packed, iv.size, encrypted.size)
            KV.put(KEY_CIPHER, Base64.encodeToString(packed, Base64.NO_WRAP))
        } catch (e: Throwable) {
            KV.put(KEY_CIPHER, "")
        }
    }

    fun hasApiKey(): Boolean = !TextUtils.isEmpty(getApiKey())

    fun masked(key: String?): String {
        if (key.isNullOrEmpty()) return "未填写"
        if (key.length <= 8) return "****"
        return key.substring(0, 4) + "****" + key.substring(key.length - 4)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE)
        keyStore.load(null)
        val existing = keyStore.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
