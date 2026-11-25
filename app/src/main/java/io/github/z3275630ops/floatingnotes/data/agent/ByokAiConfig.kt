package io.github.z3275630ops.floatingnotes.data.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.z3275630ops.floatingnotes.data.backup.MiniJson
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class AiProviderPreset(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val requiresApiKey: Boolean,
) {
    DEEPSEEK(
        id = "deepseek",
        displayName = "DeepSeek",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        requiresApiKey = true,
    ),
    OPENAI(
        id = "openai",
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        requiresApiKey = true,
    ),
    SILICONFLOW(
        id = "siliconflow",
        displayName = "硅基流动",
        defaultBaseUrl = "https://api.siliconflow.cn/v1",
        defaultModel = "deepseek-ai/DeepSeek-V3",
        requiresApiKey = true,
    ),
    OLLAMA(
        id = "ollama",
        displayName = "Ollama(本地)",
        defaultBaseUrl = "http://127.0.0.1:11434/v1",
        defaultModel = "qwen2.5:7b",
        requiresApiKey = false,
    ),
    CUSTOM(
        id = "custom",
        displayName = "自定义",
        defaultBaseUrl = "",
        defaultModel = "",
        requiresApiKey = true,
    ),
    ;

    companion object {
        fun fromId(id: String?, default: AiProviderPreset = DEEPSEEK): AiProviderPreset {
            val normalized = id?.trim().orEmpty()
            if (normalized.isEmpty()) return default
            return entries.firstOrNull {
                it.id.equals(normalized, ignoreCase = true) ||
                    it.name.equals(normalized, ignoreCase = true)
            } ?: default
        }
    }
}

internal data class ByokAiConfigSnapshot(
    val preset: AiProviderPreset = AiProviderPreset.DEEPSEEK,
    val enabled: Boolean = false,
    val baseUrl: String = AiProviderPreset.DEEPSEEK.defaultBaseUrl,
    val model: String = AiProviderPreset.DEEPSEEK.defaultModel,
    val hasApiKey: Boolean = false,
    val maskedApiKey: String = "",
    val lastTestedAt: Long? = null,
    val lastTestSuccess: Boolean? = null,
    val lastTestMessage: String? = null,
) {
    val isReadyForRemote: Boolean
        get() = enabled &&
            baseUrl.isNotBlank() &&
            model.isNotBlank() &&
            (!preset.requiresApiKey || hasApiKey)
}

/**
 * Pure-Kotlin AES-GCM codec and key masker for BYOK API keys.
 * Uses only standard JDK crypto + java.util.Base64 (API 26+) so unit tests run on pure JVM.
 */
internal object AiSecretCodec {
    const val KEYSTORE_ALIAS = "floating_notes_byok_aes_v1"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_TAG_LENGTH_BITS = 128
    private const val PAYLOAD_VERSION = "v1"

    fun maskApiKey(rawKey: String?): String {
        val trimmed = rawKey?.replace("\r", "")?.replace("\n", "")?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        if (trimmed.length <= 6) return "****"
        if (trimmed.length <= 10) return "****${trimmed.takeLast(2)}"
        val dashIdx = trimmed.indexOf('-')
        if (dashIdx in 1..4 && trimmed.length - dashIdx > 7) {
            return "${trimmed.substring(0, dashIdx + 1)}****${trimmed.takeLast(4)}"
        }
        return "${trimmed.take(3)}****${trimmed.takeLast(4)}"
    }

    fun generateAesKeyForTesting(): SecretKey {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256)
        return generator.generateKey()
    }

    fun encrypt(plaintext: String, secretKey: SecretKey): String {
        val normalized = normalizeSecretInput(plaintext)
        require(normalized.isNotEmpty()) { "API Key 不能为空" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv ?: error("加密器未生成随机 IV")
        val ciphertext = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return "$PAYLOAD_VERSION:${encoder.encodeToString(iv)}:${encoder.encodeToString(ciphertext)}"
    }

    fun decrypt(encodedPayload: String, secretKey: SecretKey): String? {
        val trimmed = encodedPayload.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(':')
        if (parts.size != 3 || parts[0] != PAYLOAD_VERSION) return null
        return try {
            val decoder = Base64.getDecoder()
            val iv = decoder.decode(parts[1])
            val ciphertext = decoder.decode(parts[2])
            if (iv.isEmpty() || ciphertext.isEmpty()) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val plainBytes = cipher.doFinal(ciphertext)
            String(plainBytes, Charsets.UTF_8).takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    internal fun normalizeSecretInput(rawKey: String?): String =
        rawKey?.replace("\r", "")?.replace("\n", "")?.trim().orEmpty()
}

private val NON_HTTP_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:(?!\\d+(?:/|\\?|#|$)).*")

/**
 * Normalizes user-entered OpenAI-compatible base URLs into a concrete `.../chat/completions` URL.
 * Rejects blank inputs and any scheme other than `http` or `https`.
 */
internal fun normalizeChatCompletionsUrl(rawBaseUrl: String): String {
    val trimmed = rawBaseUrl.trim()
    if (trimmed.isEmpty()) {
        throw IllegalArgumentException("API 接口地址不能为空")
    }
    if (trimmed.any { it.isWhitespace() || it.code < 0x20 }) {
        throw IllegalArgumentException("API 接口地址包含非法空白字符")
    }

    val withScheme = if (trimmed.contains("://")) {
        trimmed
    } else {
        if (NON_HTTP_SCHEME_REGEX.matches(trimmed)) {
            throw IllegalArgumentException("仅支持 http:// 或 https:// 接口地址")
        }
        val hostPart = trimmed.substringBefore('/').substringBefore('?').substringBefore('#')
        val isLocal = hostPart.equals("localhost", ignoreCase = true) ||
            hostPart.startsWith("localhost:", ignoreCase = true) ||
            hostPart == "127.0.0.1" ||
            hostPart.startsWith("127.0.0.1:") ||
            hostPart.startsWith("[::1]")
        val defaultScheme = if (isLocal) "http://" else "https://"
        "$defaultScheme$trimmed"
    }

    val uri = try {
        URI(withScheme)
    } catch (e: Exception) {
        throw IllegalArgumentException("API 接口地址格式无效：${e.message ?: "无法解析"}", e)
    }

    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
        throw IllegalArgumentException("仅支持 http:// 或 https:// 接口地址")
    }
    val host = uri.host?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("API 接口地址缺少有效域名或主机名")

    val authority = buildString {
        if (!uri.userInfo.isNullOrEmpty()) {
            append(uri.userInfo).append('@')
        }
        append(host)
        if (uri.port != -1) {
            append(':').append(uri.port)
        }
    }

    val rawPath = (uri.rawPath ?: "").trimEnd('/')
    val normalizedPath = when {
        rawPath.endsWith("/chat/completions", ignoreCase = true) -> rawPath
        rawPath.isEmpty() -> "/v1/chat/completions"
        else -> "$rawPath/chat/completions"
    }

    val querySuffix = uri.rawQuery?.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
    return "$scheme://$authority$normalizedPath$querySuffix"
}

internal interface ByokSecretKeyProvider {
    fun getOrCreateKey(): SecretKey
    fun getExistingKeyOrNull(): SecretKey?
    fun resetKey()
}

internal class AndroidKeyStoreSecretKeyProvider(
    private val alias: String = AiSecretCodec.KEYSTORE_ALIAS,
) : ByokSecretKeyProvider {

    override fun getOrCreateKey(): SecretKey {
        getExistingKeyOrNull()?.let { return it }
        val keyStore = loadKeyStore()
        runCatching {
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }
        }
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    override fun getExistingKeyOrNull(): SecretKey? = try {
        val keyStore = loadKeyStore()
        if (!keyStore.containsAlias(alias)) {
            null
        } else {
            keyStore.getKey(alias, null) as? SecretKey
        }
    } catch (_: Exception) {
        null
    }

    override fun resetKey() {
        runCatching {
            val keyStore = loadKeyStore()
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }
        }
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/**
 * Isolated local persistence for BYOK LLM settings and encrypted API keys.
 * Non-secret settings live in `byok_ai_config.json`; the encrypted key lives in `byok_ai_secret.enc`
 * and is never written to BackupCodec or SharedPreferences.
 */
internal class ByokAiConfigStore internal constructor(
    private val filesDir: File,
    private val keyProvider: ByokSecretKeyProvider,
) {
    constructor(context: Context) : this(
        filesDir = (context.applicationContext ?: context).filesDir,
        keyProvider = AndroidKeyStoreSecretKeyProvider(AiSecretCodec.KEYSTORE_ALIAS),
    )

    private val lock = Any()
    private val configFile = File(filesDir, CONFIG_FILE_NAME)
    private val secretFile = File(filesDir, SECRET_FILE_NAME)

    fun loadSnapshot(): ByokAiConfigSnapshot = synchronized(lock) {
        val stored = readConfigRecordOrDefault()
        val secretPresent = secretFile.isFile && secretFile.length() > 0L
        val masked = if (secretPresent) {
            stored.maskedApiKey.ifBlank { MASKED_FALLBACK }
        } else {
            ""
        }
        stored.toSnapshot(hasApiKey = secretPresent, maskedApiKey = masked)
    }

    fun saveConfig(
        preset: AiProviderPreset,
        enabled: Boolean,
        baseUrl: String,
        model: String,
        newApiKeyOrNull: String?,
        clearApiKey: Boolean = false,
    ): ByokAiConfigSnapshot = synchronized(lock) {
        val previous = readConfigRecordOrDefault()
        val cleanBaseUrl = baseUrl.trim()
        val cleanModel = model.trim()
        val normalizedNewKey = AiSecretCodec.normalizeSecretInput(newApiKeyOrNull)

        var maskedKey = if (secretFile.isFile && secretFile.length() > 0L) previous.maskedApiKey else ""
        var hasSecret = secretFile.isFile && secretFile.length() > 0L
        var keyChanged = false

        when {
            clearApiKey -> {
                deleteSecretFileSafely()
                maskedKey = ""
                hasSecret = false
                keyChanged = previous.maskedApiKey.isNotEmpty()
            }
            normalizedNewKey.isNotEmpty() -> {
                val encrypted = try {
                    val key = keyProvider.getOrCreateKey()
                    AiSecretCodec.encrypt(normalizedNewKey, key)
                } catch (_: Exception) {
                    keyProvider.resetKey()
                    val freshKey = keyProvider.getOrCreateKey()
                    AiSecretCodec.encrypt(normalizedNewKey, freshKey)
                }
                atomicWriteUtf8(secretFile, encrypted)
                maskedKey = AiSecretCodec.maskApiKey(normalizedNewKey)
                hasSecret = true
                keyChanged = true
            }
        }

        val endpointChanged = previous.preset != preset ||
            previous.baseUrl != cleanBaseUrl ||
            previous.model != cleanModel ||
            keyChanged

        val updatedRecord = StoredConfigRecord(
            preset = preset,
            enabled = enabled,
            baseUrl = cleanBaseUrl,
            model = cleanModel,
            maskedApiKey = if (hasSecret) maskedKey.ifBlank { MASKED_FALLBACK } else "",
            lastTestedAt = if (endpointChanged) null else previous.lastTestedAt,
            lastTestSuccess = if (endpointChanged) null else previous.lastTestSuccess,
            lastTestMessage = if (endpointChanged) null else previous.lastTestMessage,
        )
        writeConfigRecord(updatedRecord)
        updatedRecord.toSnapshot(hasApiKey = hasSecret, maskedApiKey = updatedRecord.maskedApiKey)
    }

    fun recordTestResult(
        success: Boolean,
        message: String,
        testedAtMs: Long = System.currentTimeMillis(),
    ): ByokAiConfigSnapshot = synchronized(lock) {
        val current = readConfigRecordOrDefault()
        val hasSecret = secretFile.isFile && secretFile.length() > 0L
        val updated = current.copy(
            maskedApiKey = if (hasSecret) current.maskedApiKey.ifBlank { MASKED_FALLBACK } else "",
            lastTestedAt = testedAtMs,
            lastTestSuccess = success,
            lastTestMessage = message.trim(),
        )
        writeConfigRecord(updated)
        updated.toSnapshot(hasApiKey = hasSecret, maskedApiKey = updated.maskedApiKey)
    }

    fun resolveCredentials(): Pair<ByokAiConfigSnapshot, String?> = synchronized(lock) {
        val current = readConfigRecordOrDefault()
        if (!secretFile.isFile || secretFile.length() <= 0L) {
            if (current.maskedApiKey.isNotEmpty()) {
                val cleared = current.copy(maskedApiKey = "")
                writeConfigRecord(cleared)
            }
            return@synchronized current.toSnapshot(hasApiKey = false, maskedApiKey = "") to null
        }

        val decrypted = try {
            val payload = secretFile.readText(Charsets.UTF_8)
            val key = keyProvider.getExistingKeyOrNull()
            if (key == null) {
                null
            } else {
                AiSecretCodec.decrypt(payload, key)
            }
        } catch (_: Exception) {
            keyProvider.resetKey()
            null
        }

        if (decrypted.isNullOrEmpty()) {
            // Safe degradation on API 29+ KeyStore corruption or tampered ciphertext.
            deleteSecretFileSafely()
            val cleared = current.copy(maskedApiKey = "")
            writeConfigRecord(cleared)
            return@synchronized cleared.toSnapshot(hasApiKey = false, maskedApiKey = "") to null
        }

        val masked = AiSecretCodec.maskApiKey(decrypted)
        val synced = if (current.maskedApiKey != masked) {
            current.copy(maskedApiKey = masked).also(::writeConfigRecord)
        } else {
            current
        }
        synced.toSnapshot(hasApiKey = true, maskedApiKey = masked) to decrypted
    }

    private fun readConfigRecordOrDefault(): StoredConfigRecord {
        if (!configFile.isFile) return StoredConfigRecord()
        return try {
            val root = MiniJson.parseObject(configFile.readText(Charsets.UTF_8))
            val preset = AiProviderPreset.fromId(root["preset"] as? String)
            val enabled = root["enabled"] as? Boolean ?: false
            val baseUrl = (root["base_url"] as? String) ?: preset.defaultBaseUrl
            val model = (root["model"] as? String) ?: preset.defaultModel
            val maskedApiKey = (root["masked_api_key"] as? String).orEmpty()
            val lastTestedAt = (root["last_tested_at"] as? Number)?.toLong()
            val lastTestSuccess = root["last_test_success"] as? Boolean
            val lastTestMessage = root["last_test_message"] as? String
            StoredConfigRecord(
                preset = preset,
                enabled = enabled,
                baseUrl = baseUrl,
                model = model,
                maskedApiKey = maskedApiKey,
                lastTestedAt = lastTestedAt,
                lastTestSuccess = lastTestSuccess,
                lastTestMessage = lastTestMessage,
            )
        } catch (_: Exception) {
            StoredConfigRecord()
        }
    }

    private fun writeConfigRecord(record: StoredConfigRecord) {
        val payload = linkedMapOf<String, Any?>(
            "version" to CONFIG_SCHEMA_VERSION,
            "preset" to record.preset.id,
            "enabled" to record.enabled,
            "base_url" to record.baseUrl,
            "model" to record.model,
            "masked_api_key" to record.maskedApiKey,
            "last_tested_at" to record.lastTestedAt,
            "last_test_success" to record.lastTestSuccess,
            "last_test_message" to record.lastTestMessage,
        )
        atomicWriteUtf8(configFile, MiniJson.encode(payload))
    }

    private fun deleteSecretFileSafely() {
        runCatching {
            if (secretFile.exists()) {
                secretFile.delete()
            }
            val tmp = File(secretFile.parentFile, "${secretFile.name}.tmp")
            if (tmp.exists()) {
                tmp.delete()
            }
        }
    }

    private fun atomicWriteUtf8(target: File, content: String) {
        val parent = target.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmp = File(parent, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
            runCatching { out.fd.sync() }
        }
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                if (target.exists()) {
                    target.delete()
                }
                if (!tmp.renameTo(target)) {
                    target.writeText(content, Charsets.UTF_8)
                    tmp.delete()
                }
            }
        }
    }

    private data class StoredConfigRecord(
        val preset: AiProviderPreset = AiProviderPreset.DEEPSEEK,
        val enabled: Boolean = false,
        val baseUrl: String = AiProviderPreset.DEEPSEEK.defaultBaseUrl,
        val model: String = AiProviderPreset.DEEPSEEK.defaultModel,
        val maskedApiKey: String = "",
        val lastTestedAt: Long? = null,
        val lastTestSuccess: Boolean? = null,
        val lastTestMessage: String? = null,
    ) {
        fun toSnapshot(hasApiKey: Boolean, maskedApiKey: String): ByokAiConfigSnapshot =
            ByokAiConfigSnapshot(
                preset = preset,
                enabled = enabled,
                baseUrl = baseUrl,
                model = model,
                hasApiKey = hasApiKey,
                maskedApiKey = maskedApiKey,
                lastTestedAt = lastTestedAt,
                lastTestSuccess = lastTestSuccess,
                lastTestMessage = lastTestMessage,
            )
    }

    companion object {
        const val CONFIG_FILE_NAME = "byok_ai_config.json"
        const val SECRET_FILE_NAME = "byok_ai_secret.enc"
        private const val CONFIG_SCHEMA_VERSION = 1
        private const val MASKED_FALLBACK = "****"
    }
}
