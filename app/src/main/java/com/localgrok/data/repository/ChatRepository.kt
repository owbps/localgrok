package com.localgrok.data.repository

import com.localgrok.data.local.dao.ChatDao
import com.localgrok.data.local.dao.MessageDao
import com.localgrok.data.local.entity.ChatEntity
import com.localgrok.data.local.entity.MessageEntity
import com.localgrok.data.remote.OllamaApiService
import com.localgrok.data.remote.OllamaClient
import com.localgrok.data.remote.SearxngApiService
import com.localgrok.data.remote.SearxngClient
import com.localgrok.data.remote.model.OllamaChatRequest
import com.localgrok.data.remote.model.OllamaChatResponse
import com.localgrok.data.remote.model.OllamaMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Repository for managing chat data and API communication
 */
class ChatRepository(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao
) {
    private var ollamaService: OllamaApiService? = null
    private var searxngService: SearxngApiService? = null
    private val json = OllamaClient.getJson()

    // Tool call detection regex
    companion object {
        // Generic tool call regex - captures tool name and full JSON
        // With closing tag
        private val TOOL_CALL_REGEX_CLOSED = Regex(
            """<tool_call>\s*(\{[^}]+\})\s*</tool_call>""",
            RegexOption.DOT_MATCHES_ALL
        )

        // Without closing tag (model sometimes omits it)
        private val TOOL_CALL_REGEX_OPEN = Regex(
            """<tool_call>\s*(\{[^}]+\})""",
            RegexOption.DOT_MATCHES_ALL
        )

        // Pattern to detect if response is STARTING with a tool call
        private const val TOOL_CALL_PREFIX = "<tool_call>"

        // Regex to strip tool_result tags from responses (model sometimes echoes these back)
        // Matches <tool_result>...</tool_result> with any content inside
        private val TOOL_RESULT_REGEX = Regex(
            """<tool_result>[\s\S]*?</tool_result>""",
            RegexOption.DOT_MATCHES_ALL
        )

        // Also strip unclosed tool_result tags
        private val TOOL_RESULT_UNCLOSED_REGEX = Regex(
            """<tool_result>[\s\S]*$""",
            RegexOption.DOT_MATCHES_ALL
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // API Configuration
    // ═══════════════════════════════════════════════════════════════════════

    fun configureServer(serverIp: String, port: Int = 11434) {
        ollamaService = OllamaClient.createService(serverIp, port)
    }

    fun configureSearxng(serverIp: String, port: Int = 8888) {
        searxngService = SearxngClient.createService(serverIp, port)
    }

    fun isServerConfigured(): Boolean = ollamaService != null

    fun isSearchConfigured(): Boolean = searxngService != null

    // ═══════════════════════════════════════════════════════════════════════
    // Chat Operations (Local Database)
    // ═══════════════════════════════════════════════════════════════════════

    fun getAllChats(): Flow<List<ChatEntity>> = chatDao.getAllChats()

    fun getChatById(chatId: Long): Flow<ChatEntity?> = chatDao.getChatByIdFlow(chatId)

    suspend fun createChat(title: String, model: String): Long {
        val chat = ChatEntity(title = title, model = model)
        return chatDao.insertChat(chat)
    }

    suspend fun updateChatTitle(chatId: Long, title: String) {
        chatDao.updateChatTitle(chatId, title)
    }

    suspend fun deleteChat(chatId: Long) {
        chatDao.deleteChatById(chatId)
    }

    suspend fun deleteAllChats() {
        chatDao.deleteAllChats()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Message Operations (Local Database)
    // ═══════════════════════════════════════════════════════════════════════

    fun getMessagesForChat(chatId: Long): Flow<List<MessageEntity>> =
        messageDao.getMessagesForChat(chatId)

    suspend fun addUserMessage(chatId: Long, content: String): Long {
        val message = MessageEntity(
            chatId = chatId,
            role = "user",
            content = content
        )
        chatDao.updateChatTimestamp(chatId)
        return messageDao.insertMessage(message)
    }

    suspend fun createAssistantMessage(chatId: Long): Long {
        val message = MessageEntity(
            chatId = chatId,
            role = "assistant",
            content = "",
            isStreaming = true
        )
        return messageDao.insertMessage(message)
    }

    suspend fun updateMessageContent(messageId: Long, content: String, isStreaming: Boolean) {
        messageDao.updateMessageContent(messageId, content, isStreaming)
    }

    /**
     * Update message with full state including thinking indicator
     */
    suspend fun updateMessageState(
        messageId: Long,
        content: String,
        isStreaming: Boolean,
        isThinking: Boolean,
        toolUsed: Boolean = false,
        toolDisplayName: String = ""
    ) {
        messageDao.updateMessageState(
            messageId,
            content,
            isStreaming,
            isThinking,
            toolUsed,
            toolDisplayName
        )
    }

    /**
     * Update message with full state including thinking indicator and reasoning content
     */
    suspend fun updateMessageStateWithReasoning(
        messageId: Long,
        content: String,
        isStreaming: Boolean,
        isThinking: Boolean,
        reasoningContent: String,
        toolUsed: Boolean = false,
        toolDisplayName: String = ""
    ) {
        messageDao.updateMessageStateWithReasoning(
            messageId,
            content,
            isStreaming,
            isThinking,
            reasoningContent,
            toolUsed,
            toolDisplayName
        )
    }

    /**
     * Update only the reasoning content of a message
     */
    suspend fun updateReasoningContent(messageId: Long, reasoningContent: String) {
        messageDao.updateReasoningContent(messageId, reasoningContent)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Streaming Chat API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Send a message and receive streaming response tokens
     */
    fun streamChat(
        chatId: Long,
        model: String,
        messages: List<MessageEntity>
    ): Flow<StreamingResult> = flow {
        val service = ollamaService ?: run {
            emit(StreamingResult.Error("Server not configured"))
            return@flow
        }

        try {
            emit(StreamingResult.Started)

            // Convert to Ollama message format
            val ollamaMessages = messages.map { msg ->
                OllamaMessage(
                    role = msg.role,
                    content = msg.content
                )
            }

            val request = OllamaChatRequest(
                model = model,
                messages = ollamaMessages,
                stream = true
            )

            val response = service.chatStream(request)

            if (!response.isSuccessful) {
                emit(StreamingResult.Error("API Error: ${response.code()} - ${response.message()}"))
                return@flow
            }

            val responseBody = response.body() ?: run {
                emit(StreamingResult.Error("Empty response body"))
                return@flow
            }

            // Read streaming response line by line
            responseBody.byteStream().bufferedReader().use { reader ->
                var fullContent = ""

                reader.forEachLine { line ->
                    if (line.isNotBlank()) {
                        try {
                            val chatResponse = json.decodeFromString<OllamaChatResponse>(line)

                            chatResponse.message?.content?.let { token ->
                                fullContent += token
                                // Emit is not allowed in forEachLine directly, we'll handle this differently
                            }

                            if (chatResponse.done) {
                                // Done signal handled below
                            }
                        } catch (e: Exception) {
                            // Skip malformed JSON lines
                        }
                    }
                }
            }

        } catch (e: Exception) {
            emit(StreamingResult.Error("Network error: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Send a message and receive streaming response with callback
     * This is the preferred method for streaming as it handles token emission properly
     *
     * @param model The model to use for generation
     * @param messages The conversation history
     * @param systemPrompt Optional system prompt to prepend
     * @param thinkEnabled Whether to enable thinking mode (Brain Toggle)
     * @param onThinkingToken Callback for each thinking token received (only when thinkEnabled = true)
     * @param onToken Callback for each content token received
     * @param onComplete Callback when generation is complete
     * @param onError Callback for errors
     */
    suspend fun streamChatWithCallback(
        model: String,
        messages: List<MessageEntity>,
        systemPrompt: String? = null,
        thinkEnabled: Boolean = false,
        onThinkingToken: suspend (String) -> Unit = {},
        onToken: suspend (String) -> Unit,
        onComplete: suspend () -> Unit,
        onError: suspend (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val service = ollamaService ?: run {
            onError("Server not configured. Please set server IP in settings.")
            return@withContext
        }

        try {
            // Build messages list with optional system prompt
            val ollamaMessages = buildList {
                // Add system prompt if provided
                if (!systemPrompt.isNullOrBlank()) {
                    add(OllamaMessage(role = "system", content = systemPrompt))
                }
                // Add conversation history
                addAll(messages.map { msg ->
                    OllamaMessage(role = msg.role, content = msg.content)
                })
            }

            val request = OllamaChatRequest(
                model = model,
                messages = ollamaMessages,
                stream = true,
                think = thinkEnabled
            )

            val response = service.chatStream(request)

            if (!response.isSuccessful) {
                onError("API Error: ${response.code()} - ${response.message()}")
                return@withContext
            }

            val responseBody = response.body() ?: run {
                onError("Empty response body")
                return@withContext
            }

            // Read streaming response line by line
            // Use runInterruptible to make blocking I/O cancellable
            responseBody.byteStream().bufferedReader().use { reader ->
                try {
                    var line: String? = runInterruptible { reader.readLine() }
                    while (line != null) {
                        // Check for cancellation on each iteration
                        coroutineContext.ensureActive()

                        if (line.isNotBlank()) {
                            try {
                                val chatResponse = json.decodeFromString<OllamaChatResponse>(line)

                                // When thinking is enabled, process thinking tokens first
                                if (thinkEnabled) {
                                    chatResponse.message?.thinking?.let { thinkingToken ->
                                        if (thinkingToken.isNotEmpty()) {
                                            onThinkingToken(thinkingToken)
                                        }
                                    }
                                }

                                // Process regular content tokens
                                chatResponse.message?.content?.let { token ->
                                    if (token.isNotEmpty()) {
                                        onToken(token)
                                    }
                                }

                                if (chatResponse.done) {
                                    onComplete()
                                }
                            } catch (e: Exception) {
                                // Skip malformed JSON lines (but not CancellationException)
                                if (e is CancellationException) throw e
                            }
                        }
                        line = runInterruptible { reader.readLine() }
                    }
                } catch (e: CancellationException) {
                    // Cancellation requested - close the response body to interrupt blocking I/O
                    responseBody.close()
                    throw e  // Re-throw to properly cancel the coroutine
                }
            }

        } catch (e: CancellationException) {
            // Don't report cancellation as an error - it's expected when user stops generation
            throw e
        } catch (e: Exception) {
            onError("Network error: ${e.message}")
        }
    }

    /**
     * List available models from the server
     */
    suspend fun listModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        val service = ollamaService ?: return@withContext Result.failure(
            Exception("Server not configured")
        )

        try {
            val response = service.listModels()
            if (response.isSuccessful) {
                val models = response.body()?.models?.map { it.name } ?: emptyList()
                Result.success(models)
            } else {
                Result.failure(Exception("Failed to fetch models: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check server connectivity
     */
    suspend fun checkServerConnection(): Boolean = withContext(Dispatchers.IO) {
        val service = ollamaService ?: return@withContext false

        try {
            val response = service.healthCheck()
            // Close the response body to avoid resource leaks
            response.body()?.close()
            response.isSuccessful
        } catch (e: Exception) {
            android.util.Log.e("ChatRepository", "Connection check failed: ${e.message}", e)
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tool Calling
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Clean tool artifacts from response content before displaying to user.
     * Removes <tool_result>...</tool_result> and <tool_call>...</tool_call> tags
     * that the model may have echoed back instead of responding naturally.
     */
    fun cleanToolArtifacts(response: String): String {
        var cleaned = response

        // Remove tool_result tags (closed)
        cleaned = TOOL_RESULT_REGEX.replace(cleaned, "")

        // Remove tool_result tags (unclosed - model stopped mid-output)
        cleaned = TOOL_RESULT_UNCLOSED_REGEX.replace(cleaned, "")

        // Remove tool_call tags (closed) - in case model outputs tool call in non-first position
        cleaned = TOOL_CALL_REGEX_CLOSED.replace(cleaned, "")

        // Remove tool_call tags (unclosed)
        cleaned = TOOL_CALL_REGEX_OPEN.replace(cleaned, "")

        return cleaned.trim()
    }

    /**
     * Check if the response is starting with a tool call pattern
     * Used for early detection to show tool execution indicator immediately
     */
    fun isStartingWithToolCall(response: String): Boolean {
        val trimmed = response.trimStart()
        return trimmed.startsWith(TOOL_CALL_PREFIX) ||
                // Also check partial matches as tokens come in
                TOOL_CALL_PREFIX.startsWith(trimmed) && trimmed.isNotEmpty() && trimmed.length < TOOL_CALL_PREFIX.length
    }

    /**
     * Detect and parse a tool call from the response
     * Returns ToolCall with name and parameters, or null if no tool call found
     */
    fun detectToolCall(response: String): ToolCall? {
        val payload = findToolCallPayload(response) ?: return null
        val normalizedName = payload.name.lowercase()
        return when (normalizedName) {
            "web_search", "websearch" -> payload.query?.let { ToolCall.WebSearch(it) }
            // Accept legacy or misspelled variants to avoid silent failures
            "get_datetime", "get_timedate", "get_time", "current_time" -> ToolCall.GetDateTime
            else -> null
        }
    }

    /**
     * Extract and parse the tool call payload from the response.
     * Uses balanced brace counting to handle JSON properly (including } inside string values).
     */
    private fun findToolCallPayload(response: String): ToolCallPayload? {
        // Locate the tool_call tag
        val startIndex = response.indexOf(TOOL_CALL_PREFIX)
        if (startIndex == -1) return null
        val afterTagIndex = startIndex + TOOL_CALL_PREFIX.length

        // Grab everything after <tool_call>, optionally stopping at </tool_call>
        val trailing = response.substring(afterTagIndex)
        val endIndex = trailing.indexOf("</tool_call>")
        val inner = if (endIndex != -1) trailing.substring(0, endIndex) else trailing

        // Find the first balanced JSON object within the inner string
        val jsonStart = inner.indexOf('{')
        if (jsonStart == -1) return null

        var depth = 0
        var jsonEnd = -1
        var inString = false
        var escapeNext = false

        for (i in jsonStart until inner.length) {
            val char = inner[i]

            if (escapeNext) {
                escapeNext = false
                continue
            }

            when (char) {
                '\\' -> {
                    escapeNext = true
                }

                '"' -> {
                    inString = !inString
                }

                '{' -> {
                    if (!inString) depth++
                }

                '}' -> {
                    if (!inString) {
                        depth--
                        if (depth == 0) {
                            jsonEnd = i
                            break
                        }
                    }
                }
            }
        }

        val jsonString = if (jsonEnd != -1) {
            inner.substring(jsonStart, jsonEnd + 1)
        } else {
            // Fallback: take the rest of the string if no closing brace yet (streaming)
            inner.substring(jsonStart)
        }

        return parseJsonPayload(jsonString)
    }

    /**
     * Parse JSON payload string into ToolCallPayload
     */
    private fun parseJsonPayload(jsonString: String): ToolCallPayload? {
        return runCatching {
            val element: JsonElement = json.decodeFromString(jsonString)
            val obj = element.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
            val query = obj["query"]?.jsonPrimitive?.contentOrNull
            ToolCallPayload(name = name, query = query)
        }.getOrNull()
    }

    /**
     * Simplified representation of a tool call parsed from model output.
     */
    private data class ToolCallPayload(
        val name: String,
        val query: String?
    )

    /**
     * Get the response content before the tool call (to display to user)
     * For tool-first responses, this should be empty
     */
    fun getContentBeforeToolCall(response: String): String {
        // Try with closing tag first
        val matchClosed = TOOL_CALL_REGEX_CLOSED.find(response)
        if (matchClosed != null) {
            return response.substring(0, matchClosed.range.first).trim()
        }
        // Try without closing tag
        val matchOpen = TOOL_CALL_REGEX_OPEN.find(response)
        if (matchOpen != null) {
            return response.substring(0, matchOpen.range.first).trim()
        }
        return response
    }

    /**
     * Execute a tool and return the result
     */
    suspend fun executeTool(toolCall: ToolCall): String {
        return when (toolCall) {
            is ToolCall.WebSearch -> executeWebSearch(toolCall.query)
            is ToolCall.GetDateTime -> getCurrentDateTime()
        }
    }

    /**
     * Execute a web search using SearXNG
     */
    private suspend fun executeWebSearch(query: String): String = withContext(Dispatchers.IO) {
        val service = searxngService
            ?: return@withContext "Search not configured. Please set SearXNG port in settings."

        try {
            val response = service.search(query)

            if (!response.isSuccessful) {
                return@withContext "Search failed: ${response.code()} - ${response.message()}"
            }

            val searchResponse = response.body() ?: return@withContext "Empty search response"
            val formattedResults = SearxngClient.formatSearchResults(searchResponse)

            formattedResults
        } catch (e: Exception) {
            android.util.Log.e("ChatRepository", "Search error: ${e.message}", e)
            "Search error: ${e.message}"
        }
    }

    /**
     * Get current date and time
     */
    private fun getCurrentDateTime(): String {
        val now = java.time.LocalDateTime.now()
        val formatter =
            java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a")
        return "Current date and time: ${now.format(formatter)}"
    }
}

/**
 * Represents a parsed tool call
 */
sealed class ToolCall {
    data class WebSearch(val query: String) : ToolCall()
    data object GetDateTime : ToolCall()

    val displayName: String
        get() = when (this) {
            is WebSearch -> "Searching..."
            is GetDateTime -> "Checking time..."
        }
}

/**
 * Represents streaming result states
 */
sealed class StreamingResult {
    data object Started : StreamingResult()
    data class Token(val token: String) : StreamingResult()
    data object Completed : StreamingResult()
    data class Error(val message: String) : StreamingResult()
}

