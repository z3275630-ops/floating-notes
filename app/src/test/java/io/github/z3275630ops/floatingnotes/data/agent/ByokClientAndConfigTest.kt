package io.github.z3275630ops.floatingnotes.data.agent

import io.github.z3275630ops.floatingnotes.data.backup.MiniJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import kotlin.concurrent.thread

class ByokClientAndConfigTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private class InMemorySecretKeyProvider(
        private var currentKey: SecretKey = AiSecretCodec.generateAesKeyForTesting(),
    ) : ByokSecretKeyProvider {
        override fun getOrCreateKey(): SecretKey = currentKey
        override fun getExistingKeyOrNull(): SecretKey? = currentKey
        override fun resetKey() {
            currentKey = AiSecretCodec.generateAesKeyForTesting()
        }
    }

    private data class MockHttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private class LocalMockServer(
        private val handler: (Socket, MockHttpRequest) -> Unit,
    ) : Closeable {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val workerPool = Executors.newCachedThreadPool()

        @Volatile
        private var running = true

        val baseUrl: String
            get() = "http://127.0.0.1:${serverSocket.localPort}"

        init {
            thread(isDaemon = true, name = "byok-mock-http-server") {
                while (running) {
                    val socket = try {
                        serverSocket.accept()
                    } catch (_: IOException) {
                        break
                    }
                    workerPool.execute {
                        socket.use { client ->
                            runCatching {
                                val request = readHttpRequest(client.getInputStream()) ?: return@runCatching
                                handler(client, request)
                            }
                        }
                    }
                }
            }
        }

        override fun close() {
            running = false
            runCatching { serverSocket.close() }
            workerPool.shutdownNow()
        }

        private fun readHttpRequest(rawInput: InputStream): MockHttpRequest? {
            val input = BufferedInputStream(rawInput)
            val headerBytes = ByteArrayOutputStream()
            var matchState = 0
            while (true) {
                val b = input.read()
                if (b == -1) return null
                headerBytes.write(b)
                matchState = when {
                    matchState == 0 && b == '\r'.code -> 1
                    matchState == 1 && b == '\n'.code -> 2
                    matchState == 2 && b == '\r'.code -> 3
                    matchState == 3 && b == '\n'.code -> 4
                    b == '\r'.code -> 1
                    else -> 0
                }
                if (matchState == 4) break
                if (headerBytes.size() > 64 * 1024) {
                    throw IOException("HTTP header too large")
                }
            }

            val headerText = headerBytes.toByteArray().toString(Charsets.UTF_8)
            val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
            if (lines.isEmpty()) return null

            val requestParts = lines.first().split(' ')
            val method = requestParts.getOrNull(0).orEmpty()
            val rawTarget = requestParts.getOrNull(1).orEmpty()
            val path = rawTarget.substringBefore('?')

            val headers = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                }
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                readExactBytes(input, contentLength).toString(Charsets.UTF_8)
            } else if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
                readChunkedBody(input).toString(Charsets.UTF_8)
            } else {
                ""
            }

            return MockHttpRequest(
                method = method,
                path = path,
                headers = headers,
                body = body,
            )
        }

        private fun readExactBytes(input: InputStream, length: Int): ByteArray {
            val buf = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(buf, offset, length - offset)
                if (read < 0) throw EOFException("Unexpected EOF while reading request body")
                offset += read
            }
            return buf
        }

        private fun readChunkedBody(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val sizeLine = readAsciiLine(input) ?: break
                val chunkSize = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: 0
                if (chunkSize <= 0) {
                    // Consume trailing CRLF
                    readAsciiLine(input)
                    break
                }
                out.write(readExactBytes(input, chunkSize))
                readAsciiLine(input) // Trailing CRLF after chunk data
            }
            return out.toByteArray()
        }

        private fun readAsciiLine(input: InputStream): String? {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) return if (line.size() == 0) null else line.toByteArray().toString(Charsets.US_ASCII)
                if (b == '\n'.code) break
                if (b != '\r'.code) line.write(b)
            }
            return line.toByteArray().toString(Charsets.US_ASCII)
        }
    }

    private fun startMockServer(handler: (Socket, MockHttpRequest) -> Unit): LocalMockServer =
        LocalMockServer(handler)

    private fun sendJson(socket: Socket, status: Int, body: String?) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            else -> "Status"
        }
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val headers = if (bodyBytes == null) {
            "HTTP/1.1 $status $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        } else {
            "HTTP/1.1 $status $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
        }
        val out = socket.getOutputStream()
        out.write(headers.toByteArray(Charsets.US_ASCII))
        if (bodyBytes != null) {
            out.write(bodyBytes)
        }
        out.flush()
    }

    @Test
    fun normalizeChatCompletionsUrlHandlesVariantsAndRejectsInvalidSchemes() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            normalizeChatCompletionsUrl("api.deepseek.com"),
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            normalizeChatCompletionsUrl("api.openai.com/v1"),
        )
        assertEquals(
            "http://localhost:11434/v1/chat/completions",
            normalizeChatCompletionsUrl("http://localhost:11434"),
        )
        assertEquals(
            "http://localhost:11434/v1/chat/completions",
            normalizeChatCompletionsUrl("localhost:11434"),
        )
        assertEquals(
            "https://api.siliconflow.cn/v1/chat/completions",
            normalizeChatCompletionsUrl("https://api.siliconflow.cn/v1/"),
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            normalizeChatCompletionsUrl("https://api.openai.com/v1/chat/completions/"),
        )
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            normalizeChatCompletionsUrl("https://ark.cn-beijing.volces.com/api/v3"),
        )
        assertEquals(
            "https://example.com/openai/v1/chat/completions?api-version=2024-06-01",
            normalizeChatCompletionsUrl("https://example.com/openai/v1?api-version=2024-06-01"),
        )

        assertThrows(IllegalArgumentException::class.java) { normalizeChatCompletionsUrl("") }
        assertThrows(IllegalArgumentException::class.java) { normalizeChatCompletionsUrl("   ") }
        assertThrows(IllegalArgumentException::class.java) { normalizeChatCompletionsUrl("ftp://api.example.com/v1") }
        assertThrows(IllegalArgumentException::class.java) { normalizeChatCompletionsUrl("file:///etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) { normalizeChatCompletionsUrl("javascript:alert(1)") }
        assertThrows(IllegalArgumentException::class.java) { normalizeChatCompletionsUrl("https://") }
    }

    @Test
    fun maskedApiKeyNeverExposesPlaintextAndSecretCodecRoundTrips() {
        val longKey = "sk-proj-testkey-9876543210abcd"
        val maskedLong = AiSecretCodec.maskApiKey(longKey)
        assertEquals("sk-****abcd", maskedLong)
        assertFalse(maskedLong.contains("testkey"))
        assertFalse(maskedLong.contains("9876543210"))

        val shortKey = "123456"
        val maskedShort = AiSecretCodec.maskApiKey(shortKey)
        assertEquals("****", maskedShort)
        assertFalse(maskedShort.contains("1234"))

        val mediumKey = "abcd1234"
        val maskedMed = AiSecretCodec.maskApiKey(mediumKey)
        assertEquals("****34", maskedMed)
        assertFalse(maskedMed.contains("abcd12"))

        assertEquals("", AiSecretCodec.maskApiKey("   "))
        assertEquals("", AiSecretCodec.maskApiKey(null))

        val key = AiSecretCodec.generateAesKeyForTesting()
        val encrypted = AiSecretCodec.encrypt(longKey, key)
        assertFalse(encrypted.contains(longKey))
        assertEquals(longKey, AiSecretCodec.decrypt(encrypted, key))

        val wrongKey = AiSecretCodec.generateAesKeyForTesting()
        assertNull(AiSecretCodec.decrypt(encrypted, wrongKey))
        assertNull(AiSecretCodec.decrypt("corrupted-payload", key))
    }

    @Test
    fun configStorePersistsAtomicallyHidesPlaintextAndSelfHealsCorruptedCiphertext() {
        val dir = tempFolder.newFolder("byok_store")
        val keyProvider = InMemorySecretKeyProvider()
        val store = ByokAiConfigStore(dir, keyProvider)

        val initial = store.loadSnapshot()
        assertFalse(initial.enabled)
        assertFalse(initial.hasApiKey)
        assertFalse(initial.isReadyForRemote)

        val secretPlain = "sk-live-secret-token-99887766"
        val saved = store.saveConfig(
            preset = AiProviderPreset.DEEPSEEK,
            enabled = true,
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
            newApiKeyOrNull = "  $secretPlain \n",
        )
        assertTrue(saved.enabled)
        assertTrue(saved.hasApiKey)
        assertTrue(saved.isReadyForRemote)
        assertEquals("sk-****7766", saved.maskedApiKey)

        val configFile = File(dir, ByokAiConfigStore.CONFIG_FILE_NAME)
        val secretFile = File(dir, ByokAiConfigStore.SECRET_FILE_NAME)
        assertTrue(configFile.isFile)
        assertTrue(secretFile.isFile)
        assertFalse(File(dir, "${ByokAiConfigStore.CONFIG_FILE_NAME}.tmp").exists())
        assertFalse(configFile.readText(Charsets.UTF_8).contains(secretPlain))
        assertFalse(secretFile.readText(Charsets.UTF_8).contains(secretPlain))

        val (resolvedSnap, resolvedKey) = store.resolveCredentials()
        assertEquals(secretPlain, resolvedKey)
        assertEquals("sk-****7766", resolvedSnap.maskedApiKey)

        // Saving config without passing a new key preserves the encrypted key.
        val tested = store.recordTestResult(success = true, message = "连接成功（42 ms）", testedAtMs = 123456L)
        assertEquals(true, tested.lastTestSuccess)
        assertEquals(123456L, tested.lastTestedAt)
        assertEquals(secretPlain, store.resolveCredentials().second)

        // Corrupt ciphertext -> safe degradation returns null and cleans up broken file.
        secretFile.writeText("v1:broken_iv:broken_cipher", Charsets.UTF_8)
        val (degradedSnap, degradedKey) = store.resolveCredentials()
        assertNull(degradedKey)
        assertFalse(degradedSnap.hasApiKey)
        assertEquals("", degradedSnap.maskedApiKey)
        assertFalse(secretFile.exists())

        // Ollama preset does not require an API key to be ready.
        val ollama = store.saveConfig(
            preset = AiProviderPreset.OLLAMA,
            enabled = true,
            baseUrl = "http://127.0.0.1:11434/v1",
            model = "qwen2.5:7b",
            newApiKeyOrNull = null,
            clearApiKey = true,
        )
        assertTrue(ollama.isReadyForRemote)
        assertFalse(ollama.hasApiKey)
    }

    @Test
    fun openAiClientParsesMultiTurnToolCallsReasoningAndObjectArguments() = runBlocking {
        val capturedRequests = CopyOnWriteArrayList<Map<String, Any?>>()
        val capturedAuths = CopyOnWriteArrayList<String?>()
        val turnCounter = AtomicInteger(0)

        startMockServer { socket, req ->
            assertEquals("POST", req.method)
            assertEquals("/v1/chat/completions", req.path)
            capturedAuths.add(req.headers["authorization"])
            capturedRequests.add(MiniJson.parseObject(req.body))

            val turn = turnCounter.incrementAndGet()
            if (turn == 1) {
                // Returns both string-encoded arguments and object-encoded arguments, plus reasoning_content.
                sendJson(
                    socket,
                    200,
                    """
                    {
                      "model": "deepseek-chat",
                      "choices": [
                        {
                          "index": 0,
                          "message": {
                            "role": "assistant",
                            "content": null,
                            "reasoning_content": "先检索相关笔记再读取详情",
                            "tool_calls": [
                              {
                                "id": "call_search_1",
                                "type": "function",
                                "function": {
                                  "name": "search_notes",
                                  "arguments": "{\"query\":\"浮记\",\"limit\":5}"
                                }
                              },
                              {
                                "id": "call_read_2",
                                "type": "function",
                                "function": {
                                  "name": "read_note",
                                  "arguments": {"note_id": "note-42"}
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                )
            } else {
                sendJson(
                    socket,
                    200,
                    """
                    {
                      "model": "deepseek-chat",
                      "choices": [
                        {
                          "index": 0,
                          "message": {
                            "role": "assistant",
                            "content": "已根据检索结果整理完成[note-42]。"
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                )
            }
        }.use { server ->
            val baseUrl = server.baseUrl
            val client = OpenAiCompatibleClient()
            val tools = listOf(
                OpenAiToolDefinition(
                    name = "search_notes",
                    description = "搜索笔记",
                    parametersJsonSchema = """{"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer"}},"required":["query"]}""",
                ),
            )
            val turn1Messages = listOf(
                OpenAiChatMessage(role = "system", content = "你是研究助手"),
                OpenAiChatMessage(role = "user", content = "总结浮记进展"),
            )
            val res1 = client.chatCompletions(
                baseUrl = baseUrl,
                model = "deepseek-chat",
                apiKey = "sk-test-key-123456",
                messages = turn1Messages,
                tools = tools,
            )

            assertTrue(res1.isSuccess)
            assertEquals("先检索相关笔记再读取详情", res1.reasoningContent)
            assertEquals(2, res1.toolCalls.size)
            assertEquals("call_search_1", res1.toolCalls[0].id)
            assertEquals("search_notes", res1.toolCalls[0].name)
            assertEquals("浮记", MiniJson.parseObject(res1.toolCalls[0].argumentsJson)["query"])
            assertEquals("read_note", res1.toolCalls[1].name)
            assertEquals("note-42", MiniJson.parseObject(res1.toolCalls[1].argumentsJson)["note_id"])

            // Verify tool schema was serialized as JSON object, not raw string.
            @Suppress("UNCHECKED_CAST")
            val sentTools = capturedRequests[0]["tools"] as List<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST")
            val sentFn = sentTools[0]["function"] as Map<String, Any?>
            assertTrue(sentFn["parameters"] is Map<*, *>)
            assertEquals("Bearer sk-test-key-123456", capturedAuths[0])

            val turn2Messages = turn1Messages + listOf(
                OpenAiChatMessage(role = "assistant", content = res1.content, toolCalls = res1.toolCalls),
                OpenAiChatMessage(
                    role = "tool",
                    content = """{"hits":["note-42"]}""",
                    toolCallId = "call_search_1",
                    name = "search_notes",
                ),
            )
            val res2 = client.chatCompletions(
                baseUrl = "$baseUrl/v1",
                model = "deepseek-chat",
                apiKey = "sk-test-key-123456",
                messages = turn2Messages,
                tools = tools,
            )
            assertTrue(res2.isSuccess)
            assertEquals("已根据检索结果整理完成[note-42]。", res2.content)
            @Suppress("UNCHECKED_CAST")
            val sentMsgsTurn2 = capturedRequests[1]["messages"] as List<Map<String, Any?>>
            assertEquals("tool", sentMsgsTurn2.last()["role"])
            assertEquals("call_search_1", sentMsgsTurn2.last()["tool_call_id"])
        }
    }

    @Test
    fun testConnectionMapsHttpErrorsAndTimeoutsToChinese() = runBlocking {
        val nextStatus = AtomicInteger(200)
        val delayMs = AtomicInteger(0)

        startMockServer { socket, req ->
            val parsed = MiniJson.parseObject(req.body)
            assertEquals(8L, (parsed["max_tokens"] as Number).toLong())
            val d = delayMs.get()
            if (d > 0) Thread.sleep(d.toLong())
            val code = nextStatus.get()
            when (code) {
                200 -> sendJson(
                    socket,
                    200,
                    """{"model":"test-model","choices":[{"message":{"role":"assistant","content":"","reasoning_content":"ok"}}]}""",
                )
                401 -> sendJson(socket, 401, """{"error":{"message":"Incorrect API key"}}""")
                403 -> sendJson(socket, 403, """{"error":{"message":"Forbidden"}}""")
                404 -> sendJson(socket, 404, null)
                429 -> sendJson(socket, 429, """{"error":{"message":"Rate limit exceeded"}}""")
                else -> sendJson(socket, code, """{"error":{"message":"Server error"}}""")
            }
        }.use { server ->
            val baseUrl = server.baseUrl
            val client = OpenAiCompatibleClient(connectTimeoutMs = 2_000, readTimeoutMs = 200)

            val ok = client.testConnection(baseUrl, "test-model", "sk-1234567890", requiresApiKey = true)
            assertTrue(ok.success)
            assertTrue(ok.latencyMs >= 1L)
            assertTrue(ok.message.contains("连接成功"))

            nextStatus.set(401)
            val err401 = client.testConnection(baseUrl, "test-model", "sk-bad", requiresApiKey = true)
            assertFalse(err401.success)
            assertTrue(err401.message.contains("API Key 无效或无权限"))

            nextStatus.set(403)
            val err403 = client.testConnection(baseUrl, "test-model", "sk-bad", requiresApiKey = true)
            assertFalse(err403.success)
            assertTrue(err403.message.contains("API Key 无效或无权限"))

            nextStatus.set(404)
            val err404 = client.testConnection(baseUrl, "missing-model", "sk-1234", requiresApiKey = true)
            assertFalse(err404.success)
            assertTrue(err404.message.contains("接口地址或模型名错误"))

            nextStatus.set(429)
            val err429 = client.testConnection(baseUrl, "test-model", "sk-1234", requiresApiKey = true)
            assertFalse(err429.success)
            assertTrue(err429.message.contains("请求限流或额度不足"))

            nextStatus.set(200)
            delayMs.set(450)
            val errTimeout = client.testConnection(baseUrl, "test-model", "sk-1234", requiresApiKey = true)
            assertFalse(errTimeout.success)
            assertTrue(errTimeout.message.contains("网络超时"))
        }
    }

    @Test
    fun clientRethrowsCancellationException() = runBlocking {
        val inFlight = CountDownLatch(1)
        startMockServer { socket, _ ->
            inFlight.countDown()
            Thread.sleep(600)
            runCatching {
                sendJson(socket, 200, """{"choices":[{"message":{"role":"assistant","content":"late"}}]}""")
            }
        }.use { server ->
            val client = OpenAiCompatibleClient(connectTimeoutMs = 2_000, readTimeoutMs = 5_000)
            val job = async(Dispatchers.IO) {
                client.chatCompletions(
                    baseUrl = server.baseUrl,
                    model = "m",
                    apiKey = "sk-1",
                    messages = listOf(OpenAiChatMessage("user", "hello")),
                )
            }
            assertTrue(inFlight.await(2, TimeUnit.SECONDS))
            job.cancel()
            var caughtCancellation = false
            try {
                job.await()
            } catch (_: CancellationException) {
                caughtCancellation = true
            }
            assertTrue(caughtCancellation)
        }
    }

    @Test
    fun voiceCleanParsesRemoteJsonAndFallsBackDeterministicallyOffline() = runBlocking {
        val capturedPrompts = CopyOnWriteArrayList<String>()
        val nextStatus = AtomicInteger(200)
        val nextContent = AtomicInteger(0)

        startMockServer { socket, req ->
            capturedPrompts.add(req.body)
            val status = nextStatus.get()
            if (status != 200) {
                sendJson(socket, status, """{"error":{"message":"upstream down"}}""")
                return@startMockServer
            }
            val jsonPayload = when (nextContent.get()) {
                0 -> """```json
{"cleaned_text":"明天上午十点开产品评审会，记得带上周报数据。","todos":["准备周报数据"]}
```"""
                1 -> "not valid json at all"
                else -> """{"cleaned_text":"${"这是一段被过度扩写润色的长文内容。".repeat(12)}","todos":[]}"""
            }
            val wrapped = MiniJson.encode(
                linkedMapOf(
                    "model" to "deepseek-chat",
                    "choices" to listOf(
                        linkedMapOf(
                            "message" to linkedMapOf(
                                "role" to "assistant",
                                "content" to jsonPayload,
                            ),
                        ),
                    ),
                ),
            )
            sendJson(socket, 200, wrapped)
        }.use { server ->
            val client = OpenAiCompatibleClient()
            val readySnapshot = ByokAiConfigSnapshot(
                preset = AiProviderPreset.DEEPSEEK,
                enabled = true,
                baseUrl = server.baseUrl,
                model = "deepseek-chat",
                hasApiKey = true,
                maskedApiKey = "sk-****1234",
            )

            val remoteResult = cleanVoiceTranscriptWithByok(
                rawTranscript = "嗯那个明天上午十点开产品评审会啊记得带上周报数据",
                client = client,
                snapshot = readySnapshot,
                apiKey = "sk-real-key",
            )
            assertTrue(remoteResult.usedRemoteAi)
            assertEquals("明天上午十点开产品评审会，记得带上周报数据。", remoteResult.cleanedText)
            assertEquals(listOf("准备周报数据"), remoteResult.extractedTodos)
            assertTrue(capturedPrompts.first().contains("严禁扩写"))
            assertTrue(capturedPrompts.first().contains("严禁改写用户原意"))

            // Invalid JSON -> falls back locally
            nextContent.set(1)
            val fallbackMalformed = cleanVoiceTranscriptWithByok(
                rawTranscript = "嗯 那个 明天 记得 买 牛奶",
                client = client,
                snapshot = readySnapshot,
                apiKey = "sk-real-key",
            )
            assertFalse(fallbackMalformed.usedRemoteAi)
            assertEquals("明天记得买牛奶。", fallbackMalformed.cleanedText)
            assertEquals(listOf("买牛奶"), fallbackMalformed.extractedTodos)

            // Over-expansion guardrail -> falls back locally
            nextContent.set(2)
            val fallbackOverExpanded = cleanVoiceTranscriptWithByok(
                rawTranscript = "嗯那个明天开会",
                client = client,
                snapshot = readySnapshot,
                apiKey = "sk-real-key",
            )
            assertFalse(fallbackOverExpanded.usedRemoteAi)
            assertEquals("明天开会。", fallbackOverExpanded.cleanedText)

            // Disabled -> skips network and cleans locally
            val offlineSnapshot = readySnapshot.copy(enabled = false)
            val offlineResult = cleanVoiceTranscriptWithByok(
                rawTranscript = "呃就是说今天下午把代码测试补完",
                client = client,
                snapshot = offlineSnapshot,
                apiKey = null,
            )
            assertFalse(offlineResult.usedRemoteAi)
            assertEquals("今天下午把代码测试补完。", offlineResult.cleanedText)
        }
    }

    @Test
    fun dualCollisionHighlightsFarFetchedBoundaryAndFallsBackWhenUnavailable() = runBlocking {
        val capturedBodies = CopyOnWriteArrayList<String>()
        val failNext = AtomicInteger(0)

        startMockServer { socket, req ->
            capturedBodies.add(req.body)
            if (failNext.get() == 1) {
                sendJson(socket, 500, """{"error":{"message":"boom"}}""")
                return@startMockServer
            }
            val responseJson = MiniJson.encode(
                linkedMapOf(
                    "model" to "gpt-4o-mini",
                    "choices" to listOf(
                        linkedMapOf(
                            "message" to linkedMapOf(
                                "role" to "assistant",
                                "content" to """{"bridge_summary":"两条卡片关联较弱，不宜强行牵合。","shared_thread":"各自保留独立边界。","action_prompt":"先分别补全各自场景即可。"}""",
                            ),
                        ),
                    ),
                ),
            )
            sendJson(socket, 200, responseJson)
        }.use { server ->
            val client = OpenAiCompatibleClient()
            val snapshot = ByokAiConfigSnapshot(
                preset = AiProviderPreset.OPENAI,
                enabled = true,
                baseUrl = server.baseUrl,
                model = "gpt-4o-mini",
                hasApiKey = true,
                maskedApiKey = "sk-****1234",
            )

            val farFetchedResult = analyzeDualCollisionWithByok(
                leftText = "昨晚买了两斤苹果",
                rightText = "Rust 所有权借用检查笔记",
                reflection = "好像完全无关",
                isFarFetched = true,
                client = client,
                snapshot = snapshot,
                apiKey = "sk-1234",
            )
            assertTrue(farFetchedResult.usedRemoteAi)
            assertTrue(farFetchedResult.bridgeSummary.contains("不宜强行"))
            assertNotNull(farFetchedResult.note)
            assertTrue(capturedBodies[0].contains("牵强风险与不强行关联的边界"))

            // Remote failure -> falls back cleanly with usedRemoteAi = false
            failNext.set(1)
            val failed = analyzeDualCollisionWithByok(
                leftText = "卡片A",
                rightText = "卡片B",
                reflection = "",
                isFarFetched = false,
                client = client,
                snapshot = snapshot,
                apiKey = "sk-1234",
            )
            assertFalse(failed.usedRemoteAi)

            // Disabled -> returns usedRemoteAi = false without hitting server
            val disabled = analyzeDualCollisionWithByok(
                leftText = "卡片A",
                rightText = "卡片B",
                reflection = "",
                isFarFetched = false,
                client = client,
                snapshot = snapshot.copy(enabled = false),
                apiKey = null,
            )
            assertFalse(disabled.usedRemoteAi)
        }
    }
}
