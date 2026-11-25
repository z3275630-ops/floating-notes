package io.github.z3275630ops.floatingnotes.data.agent

import io.github.z3275630ops.floatingnotes.core.model.safeUtf16Slice
import io.github.z3275630ops.floatingnotes.data.backup.BackupValidationException
import io.github.z3275630ops.floatingnotes.data.backup.MiniJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext

internal data class OpenAiToolDefinition(
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
)

internal data class OpenAiToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

internal data class OpenAiChatMessage(
    val role: String,
    val content: String = "",
    val toolCalls: List<OpenAiToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
)

internal data class OpenAiChatResult(
    val content: String = "",
    val toolCalls: List<OpenAiToolCall> = emptyList(),
    val reasoningContent: String? = null,
    val model: String = "",
    val errorMessage: String? = null,
    val httpStatus: Int? = null,
) {
    val isSuccess: Boolean
        get() = errorMessage == null
}

internal data class ConnectionTestResult(
    val success: Boolean,
    val latencyMs: Long,
    val message: String,
)

/**
 * Pure-Kotlin OpenAI-compatible `/chat/completions` client built on `HttpURLConnection` + `MiniJson`.
 * Runs on `Dispatchers.IO`, disconnects sockets on coroutine cancellation, and always rethrows `CancellationException`.
 */
internal class OpenAiCompatibleClient(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {
    suspend fun chatCompletions(
        baseUrl: String,
        model: String,
        apiKey: String?,
        messages: List<OpenAiChatMessage>,
        tools: List<OpenAiToolDefinition> = emptyList(),
        temperature: Double = 0.3,
        maxTokens: Int? = 2048,
    ): OpenAiChatResult = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()

        val cleanModel = model.trim()
        if (cleanModel.isEmpty()) {
            return@withContext OpenAiChatResult(errorMessage = "请先填写模型名称（Model）")
        }

        val endpointUrl = try {
            normalizeChatCompletionsUrl(baseUrl)
        } catch (e: IllegalArgumentException) {
            return@withContext OpenAiChatResult(errorMessage = e.message ?: "API 接口地址格式无效")
        }

        val requestPayload = buildRequestBody(
            model = cleanModel,
            messages = messages,
            tools = tools,
            temperature = temperature,
            maxTokens = maxTokens,
        )

        var conn: HttpURLConnection? = null
        val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                runCatching { conn?.disconnect() }
            }
        }

        try {
            coroutineContext.ensureActive()
            val opened = (URL(endpointUrl).openConnection() as? HttpURLConnection)
                ?: return@withContext OpenAiChatResult(errorMessage = "无法创建 HTTP 连接")
            conn = opened
            opened.requestMethod = "POST"
            opened.connectTimeout = connectTimeoutMs
            opened.readTimeout = readTimeoutMs
            opened.doInput = true
            opened.doOutput = true
            opened.useCaches = false
            opened.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            opened.setRequestProperty("Accept", "application/json")
            val sanitizedKey = AiSecretCodec.normalizeSecretInput(apiKey)
            if (sanitizedKey.isNotEmpty()) {
                opened.setRequestProperty("Authorization", "Bearer $sanitizedKey")
            }

            val bodyBytes = requestPayload.toByteArray(Charsets.UTF_8)
            opened.outputStream.use { out ->
                out.write(bodyBytes)
                out.flush()
            }

            coroutineContext.ensureActive()
            val statusCode = opened.responseCode
            val rawBody = if (statusCode in 200..299) {
                readBoundedText(opened.inputStream)
            } else {
                // On 4xx/5xx with empty body, opened.inputStream throws FileNotFoundException; read only errorStream.
                runCatching { readBoundedText(opened.errorStream) }.getOrDefault("")
            }
            coroutineContext.ensureActive()

            if (statusCode !in 200..299) {
                return@withContext OpenAiChatResult(
                    model = cleanModel,
                    errorMessage = mapHttpErrorMessage(statusCode, rawBody),
                    httpStatus = statusCode,
                )
            }

            parseSuccessResponse(rawBody = rawBody, requestedModel = cleanModel, httpStatus = statusCode)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            coroutineContext.ensureActive()
            OpenAiChatResult(
                model = cleanModel,
                errorMessage = mapThrowableToChinese(t),
            )
        } finally {
            cancellationHandle?.dispose()
            runCatching { conn?.disconnect() }
        }
    }

    suspend fun testConnection(
        baseUrl: String,
        model: String,
        apiKey: String?,
        requiresApiKey: Boolean,
    ): ConnectionTestResult {
        if (baseUrl.isBlank()) {
            return ConnectionTestResult(
                success = false,
                latencyMs = 0L,
                message = "请先填写 API 接口地址（Base URL）",
            )
        }
        if (model.isBlank()) {
            return ConnectionTestResult(
                success = false,
                latencyMs = 0L,
                message = "请先填写模型名称（Model）",
            )
        }
        val cleanKey = AiSecretCodec.normalizeSecretInput(apiKey)
        if (requiresApiKey && cleanKey.isEmpty()) {
            return ConnectionTestResult(
                success = false,
                latencyMs = 0L,
                message = "当前供应商需要填写 API Key",
            )
        }

        val startNs = System.nanoTime()
        val result = chatCompletions(
            baseUrl = baseUrl,
            model = model,
            apiKey = cleanKey.ifEmpty { null },
            messages = listOf(OpenAiChatMessage(role = "user", content = "Hi")),
            tools = emptyList(),
            temperature = 0.0,
            maxTokens = 8,
        )
        val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)

        return if (result.isSuccess) {
            val resolved = result.model.ifBlank { model.trim() }
            ConnectionTestResult(
                success = true,
                latencyMs = latencyMs,
                message = "连接成功（${latencyMs} ms），模型 $resolved 响应正常",
            )
        } else {
            ConnectionTestResult(
                success = false,
                latencyMs = latencyMs,
                message = result.errorMessage ?: "连接测试失败，请检查配置",
            )
        }
    }

    private fun buildRequestBody(
        model: String,
        messages: List<OpenAiChatMessage>,
        tools: List<OpenAiToolDefinition>,
        temperature: Double,
        maxTokens: Int?,
    ): String {
        val root = linkedMapOf<String, Any?>(
            "model" to model,
            "messages" to messages.map(::encodeChatMessage),
            "temperature" to (temperature.takeIf { it.isFinite() } ?: 0.3),
            "stream" to false,
        )
        if (maxTokens != null && maxTokens > 0) {
            root["max_tokens"] = maxTokens
        }
        if (tools.isNotEmpty()) {
            root["tools"] = tools.map { tool ->
                linkedMapOf(
                    "type" to "function",
                    "function" to linkedMapOf(
                        "name" to tool.name,
                        "description" to tool.description,
                        "parameters" to parseToolSchemaObject(tool.parametersJsonSchema),
                    ),
                )
            }
            root["tool_choice"] = "auto"
        }
        return MiniJson.encode(root)
    }

    private fun encodeChatMessage(message: OpenAiChatMessage): Map<String, Any?> {
        val obj = linkedMapOf<String, Any?>(
            "role" to message.role,
            "content" to message.content,
        )
        if (!message.name.isNullOrBlank()) {
            obj["name"] = message.name
        }
        if (!message.toolCallId.isNullOrBlank()) {
            obj["tool_call_id"] = message.toolCallId
        }
        if (message.toolCalls.isNotEmpty()) {
            obj["tool_calls"] = message.toolCalls.map { tc ->
                linkedMapOf(
                    "id" to tc.id,
                    "type" to "function",
                    "function" to linkedMapOf(
                        "name" to tc.name,
                        "arguments" to tc.argumentsJson,
                    ),
                )
            }
        }
        return obj
    }

    private fun parseToolSchemaObject(rawSchema: String): Map<String, Any?> {
        val trimmed = rawSchema.trim()
        if (trimmed.isEmpty()) {
            return linkedMapOf("type" to "object", "properties" to emptyMap<String, Any?>())
        }
        return runCatching { MiniJson.parseObject(trimmed) }.getOrElse {
            linkedMapOf("type" to "object", "properties" to emptyMap<String, Any?>())
        }
    }

    private fun parseSuccessResponse(
        rawBody: String,
        requestedModel: String,
        httpStatus: Int,
    ): OpenAiChatResult {
        if (rawBody.isBlank()) {
            return OpenAiChatResult(
                model = requestedModel,
                errorMessage = "模型服务返回为空，请检查接口地址是否为 OpenAI 兼容 /chat/completions",
                httpStatus = httpStatus,
            )
        }
        val root = MiniJson.parseObject(rawBody)

        // Some gateways return HTTP 200 with an embedded {"error": ...} payload.
        val embeddedError = extractServerErrorText(root)
        if (embeddedError != null && root["choices"] == null) {
            return OpenAiChatResult(
                model = requestedModel,
                errorMessage = "模型接口返回错误：$embeddedError",
                httpStatus = httpStatus,
            )
        }

        val resolvedModel = (root["model"] as? String)?.takeIf { it.isNotBlank() } ?: requestedModel
        val choices = root["choices"] as? List<*>
            ?: return OpenAiChatResult(
                model = resolvedModel,
                errorMessage = "模型返回缺少 choices 字段，请确认接口兼容 OpenAI 格式",
                httpStatus = httpStatus,
            )
        val firstChoice = choices.firstOrNull() as? Map<*, *>
            ?: return OpenAiChatResult(
                model = resolvedModel,
                errorMessage = "模型返回未包含有效候选结果（choices 为空）",
                httpStatus = httpStatus,
            )

        @Suppress("UNCHECKED_CAST")
        val messageMap = (firstChoice["message"] as? Map<String, Any?>)
            ?: (firstChoice["delta"] as? Map<String, Any?>)
            ?: return OpenAiChatResult(
                model = resolvedModel,
                errorMessage = "模型返回缺少 message 字段",
                httpStatus = httpStatus,
            )

        val content = extractContentText(messageMap["content"])
        val reasoning = (messageMap["reasoning_content"] as? String)
            ?.takeIf { it.isNotBlank() }
            ?: (messageMap["reasoning"] as? String)?.takeIf { it.isNotBlank() }
        val toolCalls = parseToolCalls(messageMap)

        return OpenAiChatResult(
            content = content,
            toolCalls = toolCalls,
            reasoningContent = reasoning,
            model = resolvedModel,
            errorMessage = null,
            httpStatus = httpStatus,
        )
    }

    private fun extractContentText(rawContent: Any?): String = when (rawContent) {
        null -> ""
        is String -> rawContent
        is List<*> -> buildString {
            for (part in rawContent) {
                when (part) {
                    is String -> append(part)
                    is Map<*, *> -> {
                        val text = part["text"] as? String
                        if (!text.isNullOrEmpty()) append(text)
                    }
                }
            }
        }
        else -> rawContent.toString()
    }

    private fun parseToolCalls(messageMap: Map<String, Any?>): List<OpenAiToolCall> {
        val rawList = messageMap["tool_calls"] as? List<*>
        if (!rawList.isNullOrEmpty()) {
            val out = ArrayList<OpenAiToolCall>(rawList.size)
            rawList.forEachIndexed { index, item ->
                val callMap = item as? Map<*, *> ?: return@forEachIndexed
                val id = (callMap["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "call_$index"
                val functionMap = callMap["function"] as? Map<*, *> ?: callMap
                val name = (functionMap["name"] as? String)?.trim().orEmpty()
                if (name.isEmpty()) return@forEachIndexed
                val argsJson = normalizeToolArguments(functionMap["arguments"])
                out.add(OpenAiToolCall(id = id, name = name, argumentsJson = argsJson))
            }
            return out
        }

        // Legacy single function_call fallback
        val legacyFn = messageMap["function_call"] as? Map<*, *> ?: return emptyList()
        val name = (legacyFn["name"] as? String)?.trim().orEmpty()
        if (name.isEmpty()) return emptyList()
        return listOf(
            OpenAiToolCall(
                id = "call_0",
                name = name,
                argumentsJson = normalizeToolArguments(legacyFn["arguments"]),
            ),
        )
    }

    private fun normalizeToolArguments(rawArgs: Any?): String = when (rawArgs) {
        null -> "{}"
        is String -> rawArgs.trim().ifEmpty { "{}" }
        is Map<*, *> -> MiniJson.encode(rawArgs)
        is Iterable<*> -> MiniJson.encode(rawArgs)
        else -> MiniJson.encode(rawArgs)
    }

    private fun mapHttpErrorMessage(statusCode: Int, rawBody: String): String {
        val detail = extractDetailSnippet(rawBody)
        val suffix = detail?.let { "（$it）" }.orEmpty()
        return when (statusCode) {
            401, 403 -> "API Key 无效或无权限$suffix"
            404 -> "接口地址或模型名错误，请检查 Base URL 与 Model$suffix"
            429 -> "请求限流或额度不足，请稍后重试或检查账户余额$suffix"
            400, 422 -> "请求参数或模型名称不被接口支持（HTTP $statusCode）$suffix"
            in 500..599 -> "模型服务端暂时异常（HTTP $statusCode），请稍后重试$suffix"
            else -> "请求失败（HTTP $statusCode）$suffix"
        }
    }

    private fun extractDetailSnippet(rawBody: String): String? {
        if (rawBody.isBlank()) return null
        val parsed = runCatching { MiniJson.parseObject(rawBody) }.getOrNull()
        val msg = parsed?.let(::extractServerErrorText)
            ?: rawBody.replace(Regex("\\s+"), " ").trim()
        val clean = msg.takeIf { it.isNotBlank() } ?: return null
        return if (clean.length > 120) "${safeUtf16Slice(clean, 0, 120)}…" else clean
    }

    private fun extractServerErrorText(root: Map<String, Any?>): String? {
        val err = root["error"]
        when (err) {
            is Map<*, *> -> {
                val msg = (err["message"] as? String)?.trim()
                if (!msg.isNullOrEmpty()) return msg
                val type = (err["type"] as? String)?.trim()
                if (!type.isNullOrEmpty()) return type
            }
            is String -> if (err.isNotBlank()) return err.trim()
        }
        return (root["message"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: (root["detail"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun mapThrowableToChinese(t: Throwable): String {
        if (t is BackupValidationException) {
            return "模型返回的数据不是有效的 JSON 格式"
        }
        if (t is IllegalArgumentException) {
            return t.message ?: "接口参数无效"
        }
        if (hasCause<UnknownHostException>(t)) {
            return "域名解析失败，地址不可达，请检查网络或 Base URL"
        }
        if (hasCause<SocketTimeoutException>(t)) {
            return "网络超时，模型响应时间过长或服务未及时响应"
        }
        if (hasCause<ConnectException>(t)) {
            return "无法连接到服务器，地址不可达或服务未启动"
        }
        if (hasCause<SSLException>(t) || hasCause<CertificateException>(t)) {
            return "TLS 安全连接失败，请检查服务器 HTTPS 证书或设备系统时间"
        }
        if (hasCause<UnknownServiceException>(t)) {
            return "明文 HTTP 请求被系统安全策略拦截，请改用 HTTPS 或检查本地网络配置"
        }
        if (t is IOException) {
            val detail = t.message?.trim()?.takeIf { it.isNotEmpty() }
            return if (detail != null) "网络通信异常：$detail" else "网络通信异常，请检查网络连接"
        }
        return "请求处理失败：${t.message?.takeIf { it.isNotBlank() } ?: t::class.simpleName.orEmpty()}"
    }

    private inline fun <reified T : Throwable> hasCause(root: Throwable): Boolean {
        var cur: Throwable? = root
        var depth = 0
        while (cur != null && depth < 12) {
            if (cur is T) return true
            cur = cur.cause
            depth++
        }
        return false
    }

    private fun readBoundedText(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.use { input ->
            val buffer = ByteArray(8192)
            val out = java.io.ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val allowed = minOf(read, MAX_RESPONSE_BYTES - total)
                if (allowed > 0) {
                    out.write(buffer, 0, allowed)
                    total += allowed
                }
                if (total >= MAX_RESPONSE_BYTES) break
            }
            out.toString(Charsets.UTF_8.name())
        }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 60_000
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    }
}
