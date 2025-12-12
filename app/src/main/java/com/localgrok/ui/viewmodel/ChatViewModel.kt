package com.localgrok.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localgrok.data.local.entity.ChatEntity
import com.localgrok.data.local.entity.MessageEntity
import com.localgrok.data.repository.ChatRepository
import com.localgrok.data.repository.SettingsRepository
import com.localgrok.data.repository.ToolCall
import com.localgrok.domain.SystemPromptBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for managing chat state and interactions
 */
class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    // ═══════════════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════════════

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages.asStateFlow()

    private val _chats = MutableStateFlow<List<ChatEntity>>(emptyList())
    val chats: StateFlow<List<ChatEntity>> = _chats.asStateFlow()

    private val _currentChat = MutableStateFlow<ChatEntity?>(null)
    val currentChat: StateFlow<ChatEntity?> = _currentChat.asStateFlow()

    // Streaming state - updates immediately on each token for real-time display
    private val _streamingState = MutableStateFlow<StreamingMessageState?>(null)
    val streamingState: StateFlow<StreamingMessageState?> = _streamingState.asStateFlow()

    // Reasoning content for the current streaming message
    private val _reasoningContent = MutableStateFlow<Map<Long, String>>(emptyMap())
    val reasoningContent: StateFlow<Map<Long, String>> = _reasoningContent.asStateFlow()

    // Brain toggle state (Lightbulb) - controls reasoning and tool-enabled system prompt
    private val _brainToggleEnabled = MutableStateFlow(true)
    val brainToggleEnabled: StateFlow<Boolean> = _brainToggleEnabled.asStateFlow()

    private var currentModel: String = "qwen3:0.6b-fp16"

    // Jobs for cancelling previous flow collections
    private var chatCollectionJob: Job? = null
    private var messagesCollectionJob: Job? = null
    private var streamingJob: Job? = null


    init {
        loadChats()
        initializeServer()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Initialization
    // ═══════════════════════════════════════════════════════════════════════

    private fun initializeServer() {
        viewModelScope.launch {
            val serverIp = settingsRepository.serverIp.first()
            val serverPort = settingsRepository.serverPort.first()
            val searxngPort = settingsRepository.searxngPort.first()
            currentModel = settingsRepository.defaultModel.first()

            _uiState.value = _uiState.value.copy(selectedModel = currentModel)

            if (serverIp.isNotEmpty()) {
                chatRepository.configureServer(serverIp, serverPort)
                chatRepository.configureSearxng(serverIp, searxngPort)
                checkConnection()
            } else {
                _uiState.value = _uiState.value.copy(
                    connectionStatus = ConnectionStatus.NotConfigured
                )
            }
        }
    }

    fun refreshServerConnection() {
        viewModelScope.launch {
            val serverIp = settingsRepository.serverIp.first()
            val serverPort = settingsRepository.serverPort.first()
            val searxngPort = settingsRepository.searxngPort.first()
            currentModel = settingsRepository.defaultModel.first()

            _uiState.value = _uiState.value.copy(selectedModel = currentModel)

            if (serverIp.isNotEmpty()) {
                chatRepository.configureServer(serverIp, serverPort)
                chatRepository.configureSearxng(serverIp, searxngPort)
                checkConnection()
            }
        }
    }

    private suspend fun checkConnection() {
        _uiState.value = _uiState.value.copy(connectionStatus = ConnectionStatus.Connecting)

        val isConnected = chatRepository.checkServerConnection()
        _uiState.value = _uiState.value.copy(
            connectionStatus = if (isConnected) ConnectionStatus.Connected else ConnectionStatus.Error
        )

        if (isConnected) {
            loadModels()
        }
    }

    private suspend fun loadModels() {
        chatRepository.listModels().onSuccess { models ->
            _uiState.value = _uiState.value.copy(availableModels = models)
            if (models.isNotEmpty() && currentModel !in models) {
                currentModel = models.first()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Chat Management
    // ═══════════════════════════════════════════════════════════════════════

    private fun loadChats() {
        viewModelScope.launch {
            chatRepository.getAllChats().collect { chatList ->
                _chats.value = chatList
            }
        }
    }

    fun createNewChat() {
        viewModelScope.launch {
            val chatId = chatRepository.createChat(
                title = "New Chat",
                model = currentModel
            )
            selectChat(chatId)
        }
    }

    fun selectChat(chatId: Long) {
        chatCollectionJob?.cancel()
        messagesCollectionJob?.cancel()

        chatCollectionJob = viewModelScope.launch {
            chatRepository.getChatById(chatId).collect { chat ->
                _currentChat.value = chat
            }
        }

        messagesCollectionJob = viewModelScope.launch {
            chatRepository.getMessagesForChat(chatId).collect { messageList ->
                _messages.value = messageList

                // Load persisted reasoning content from messages
                val persistedReasoning = messageList
                    .filter { it.reasoningContent.isNotBlank() }
                    .associate { it.id to it.reasoningContent }

                // Merge with existing streaming reasoning (don't overwrite active reasoning)
                val currentReasoning = _reasoningContent.value
                _reasoningContent.value = persistedReasoning + currentReasoning.filterKeys { key ->
                    // Keep current reasoning for messages that are actively streaming
                    _streamingState.value?.messageId == key
                }
            }
        }
    }

    fun deleteChat(chatId: Long) {
        viewModelScope.launch {
            chatRepository.deleteChat(chatId)
            if (_currentChat.value?.id == chatId) {
                _currentChat.value = null
                _messages.value = emptyList()
            }
        }
    }

    fun deleteAllChats() {
        viewModelScope.launch {
            chatRepository.deleteAllChats()
            _currentChat.value = null
            _messages.value = emptyList()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Message Handling with Real-Time Streaming
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Send a message with optional model override
     * Uses real-time streaming state for immediate UI updates
     * Supports tool calling (web search) via system prompt
     * System prompt is automatically generated based on brain toggle state
     */
    fun sendMessage(
        content: String,
        model: String? = null
    ) {
        if (content.isBlank()) return
        if (_uiState.value.isGenerating) return

        val useModel = model ?: currentModel
        val thinkEnabled = _brainToggleEnabled.value
        val systemPrompt = SystemPromptBuilder.buildSystemPrompt(_brainToggleEnabled.value)

        streamingJob = viewModelScope.launch {
            // Create chat if needed
            var chatId = _currentChat.value?.id
            val isNewChat = chatId == null

            if (isNewChat) {
                chatId = chatRepository.createChat(
                    title = content.take(50),
                    model = useModel
                )
                selectChat(chatId)
                kotlinx.coroutines.delay(50)
            }

            // Update title if first message
            val currentTitle = _currentChat.value?.title
            if (currentTitle == "New Chat" || _messages.value.isEmpty()) {
                chatRepository.updateChatTitle(chatId!!, content.take(50))
            }

            // Add user message
            chatRepository.addUserMessage(chatId!!, content)

            // Create placeholder for assistant response
            val assistantMessageId = chatRepository.createAssistantMessage(chatId)

            // Initialize streaming state for immediate UI updates
            _streamingState.value = StreamingMessageState(
                messageId = assistantMessageId,
                content = "",
                isThinking = thinkEnabled,
                isStreaming = true,
                toolUsed = false,
                toolDisplayName = null
            )

            _uiState.value = _uiState.value.copy(
                isGenerating = true,
                error = null
            )

            // Start streaming with tool call support
            streamWithToolSupport(
                chatId = chatId,
                assistantMessageId = assistantMessageId,
                model = useModel,
                systemPrompt = systemPrompt,
                thinkEnabled = thinkEnabled
            )
        }
    }

    /**
     * Internal function to handle streaming with tool call support
     * Can be called recursively when a tool call is executed
     */
    private suspend fun streamWithToolSupport(
        chatId: Long,
        assistantMessageId: Long,
        model: String,
        systemPrompt: String?,
        thinkEnabled: Boolean,
        toolResultContext: String? = null,  // Tool result to inject if continuing after tool call
        toolCallCount: Int = 0  // Track how many tools have been called
    ) {

        // Get messages for API context
        var messagesForApi = chatRepository.getMessagesForChat(chatId).first()
            .filter { it.id != assistantMessageId }
            .toMutableList()

        // Build effective system prompt with tool results
        val effectiveSystemPrompt = if (toolResultContext != null) {
            val basePrompt = systemPrompt ?: ""
            """$basePrompt

<tool_result>
$toolResultContext
</tool_result>

CRITICAL INSTRUCTIONS:
- Use the tool results above to answer the user's question in natural, conversational language
- DO NOT echo or repeat the <tool_result> tags - they are for your reference only
- DO NOT list raw search results - summarize and synthesize the information
- Provide a clear, direct answer based on the tool results
- DO NOT call any more tools"""
        } else {
            systemPrompt
        }

        var contentResponse = ""
        var reasoningResponse = ""
        var hasStartedContent = false
        var toolCallDetected = false
        var toolCallEarlyDetected = false  // True when we detect tool call pattern starting
        var detectedToolCall: ToolCall? = null

        chatRepository.streamChatWithCallback(
            model = model,
            messages = messagesForApi,
            systemPrompt = effectiveSystemPrompt,
            thinkEnabled = thinkEnabled,
            onThinkingToken = { token ->
                // Accumulate reasoning content
                reasoningResponse += token

                // Update reasoning content map for this message
                _reasoningContent.value =
                    _reasoningContent.value + (assistantMessageId to reasoningResponse)

                // Update streaming state immediately - still in thinking phase
                _streamingState.value = _streamingState.value?.copy(
                    isThinking = true,
                    isStreaming = true
                )

                // Persist reasoning content to database in background
                chatRepository.updateReasoningContent(assistantMessageId, reasoningResponse)
            },
            onToken = { token ->
                // First content token received - transition from thinking to streaming
                if (!hasStartedContent) {
                    hasStartedContent = true
                }
                contentResponse += token

                // EARLY DETECTION: Check if response is starting with tool call pattern
                // This happens before we have the complete tool call
                // Only trigger on <tool_call>, not <tool_result> (which is normal content)
                val startsWithToolCall = chatRepository.isStartingWithToolCall(contentResponse)
                val startsWithToolResult =
                    contentResponse.trimStart().startsWith("<tool_result>", ignoreCase = true)

                if (!toolCallEarlyDetected && !toolCallDetected && startsWithToolCall && !startsWithToolResult) {
                    toolCallEarlyDetected = true

                    // Immediately show tool execution indicator - hide all content
                    withContext(Dispatchers.Main.immediate) {
                        _streamingState.value = StreamingMessageState(
                            messageId = assistantMessageId,
                            content = "",  // Hide content - will be tool call XML
                            isThinking = false,
                            isStreaming = true,
                            isExecutingTool = true,
                            toolDisplayName = null,  // Tool not known yet
                            toolUsed = false  // Tool not used yet during early detection
                        )
                    }
                }

                // Reset early detection if we determine it's actually a tool_result (not a tool_call)
                if (toolCallEarlyDetected && contentResponse.trimStart()
                        .startsWith("<tool_result>", ignoreCase = true)
                ) {
                    toolCallEarlyDetected =
                        false  // Reset - this is normal content, not a tool call
                    // Update UI to show normal content instead of "Working"
                    val cleanedContent = chatRepository.cleanToolArtifacts(contentResponse)
                    withContext(Dispatchers.Main.immediate) {
                        _streamingState.value = StreamingMessageState(
                            messageId = assistantMessageId,
                            content = cleanedContent,
                            isThinking = false,
                            isStreaming = true,
                            isExecutingTool = false,
                            toolDisplayName = null,
                            toolUsed = false
                        )
                    }
                }

                // Check for complete tool call in accumulated response
                val toolCall = chatRepository.detectToolCall(contentResponse)
                if (toolCall != null && !toolCallDetected) {
                    toolCallDetected = true
                    detectedToolCall = toolCall

                    // Update with the tool display name now that we know it
                    withContext(Dispatchers.Main.immediate) {
                        _streamingState.value = StreamingMessageState(
                            messageId = assistantMessageId,
                            content = "",  // Still hide content
                            isThinking = false,
                            isStreaming = true,
                            isExecutingTool = true,
                            toolDisplayName = toolCall.displayName,
                            toolUsed = true  // Tool is being used
                        )
                    }

                    // We'll handle tool execution in onComplete
                    return@streamChatWithCallback
                }

                // Only update UI with content if NOT in tool call mode
                if (!toolCallEarlyDetected && !toolCallDetected) {
                    // Clean any tool artifacts from streaming content before displaying
                    val cleanedContent = chatRepository.cleanToolArtifacts(contentResponse)

                    // Update streaming state IMMEDIATELY on main thread
                    withContext(Dispatchers.Main.immediate) {
                        _streamingState.value = StreamingMessageState(
                            messageId = assistantMessageId,
                            content = cleanedContent,
                            isThinking = false,
                            isStreaming = true,
                            toolUsed = false,  // No tool used yet
                            toolDisplayName = null  // No tool display name
                        )
                    }

                    // Update database in background (for persistence)
                    chatRepository.updateMessageState(
                        messageId = assistantMessageId,
                        content = cleanedContent,
                        isStreaming = true,
                        isThinking = false,
                        toolUsed = false,
                        toolDisplayName = ""
                    )
                }
            },
            onComplete = {
                viewModelScope.launch {
                    // Check if we detected a tool call during streaming
                    if (toolCallDetected && detectedToolCall != null) {
                        // Execute the tool
                        val toolResult = chatRepository.executeTool(detectedToolCall!!)

                        // Get content before tool call (should be empty for tool-first responses)
                        val contentBeforeToolCall =
                            chatRepository.getContentBeforeToolCall(contentResponse)

                        // Update the message with content before tool call (usually empty)
                        // The natural response will be generated by the recursive call below
                        chatRepository.updateMessageState(
                            messageId = assistantMessageId,
                            content = contentBeforeToolCall,
                            isStreaming = true,
                            isThinking = false,
                            toolUsed = true,  // Mark tool as used
                            toolDisplayName = detectedToolCall.displayName  // Set display name
                        )

                        // Continue the conversation with tool results
                        streamWithToolSupport(
                            chatId = chatId,
                            assistantMessageId = assistantMessageId,
                            model = model,
                            systemPrompt = systemPrompt,
                            thinkEnabled = thinkEnabled,
                            toolResultContext = toolResult,
                            toolCallCount = toolCallCount + 1
                        )
                    } else {
                        // No tool call - normal completion
                        // BUT: If we have toolResultContext, a tool was used earlier, so preserve that
                        val wasToolUsed = toolResultContext != null
                        val toolDisplayNameToUse = if (wasToolUsed && toolResultContext != null) {
                            // Detect which tool was used from the tool result content
                            when {
                                toolResultContext.contains("Search Results:", ignoreCase = true) ||
                                        toolResultContext.contains(
                                            "Search",
                                            ignoreCase = true
                                        ) -> "Searched the web"

                                toolResultContext.contains(
                                    "Current date and time:",
                                    ignoreCase = true
                                ) ||
                                        toolResultContext.contains(
                                            "time",
                                            ignoreCase = true
                                        ) -> "Checked time"

                                else -> "Completed"
                            }
                        } else null

                        val cleanedContent = chatRepository.cleanToolArtifacts(contentResponse)

                        // Check if this is actually a tool_result echo (model echoing back tool results)
                        // vs an actual tool_call that failed to parse
                        val containsToolResult =
                            contentResponse.contains("<tool_result>", ignoreCase = true)
                        val containsToolCall =
                            contentResponse.contains("<tool_call>", ignoreCase = true)

                        // DEBUG: If we detected early tool call pattern but couldn't parse it, show full details
                        val finalContent = when {
                            cleanedContent.isNotBlank() -> cleanedContent
                            // If it's a tool_result echo, clean it - if no other content, show empty
                            // The strengthened prompt should prevent this, but if it happens, just show cleaned content
                            containsToolResult && !containsToolCall -> cleanedContent
                            // If we detected a tool_call but couldn't parse it, show debug info
                            toolCallEarlyDetected && containsToolCall -> {
                                buildString {
                                    append("⚠️ TOOL CALL DETECTION FAILED - DEBUG INFO:\n\n")
                                    append("=== RAW RESPONSE ===\n")
                                    append(contentResponse)
                                    append("\n\n=== DETECTION STATE ===\n")
                                    append("Early Detection: $toolCallEarlyDetected\n")
                                    append("Tool Call Detected: $toolCallDetected\n")
                                    append("Detected Tool Call: $detectedToolCall\n")
                                    append("Contains <tool_result>: $containsToolResult\n")
                                    append("Contains <tool_call>: $containsToolCall\n")

                                    // Try to detect again and show what happens
                                    val attemptedDetection =
                                        chatRepository.detectToolCall(contentResponse)
                                    append("Re-attempt Detection Result: $attemptedDetection\n")

                                    // Check if it starts with tool call
                                    val startsWithToolCall =
                                        chatRepository.isStartingWithToolCall(contentResponse)
                                    append("Starts with tool call: $startsWithToolCall\n")

                                    // Show content before tool call
                                    val contentBefore =
                                        chatRepository.getContentBeforeToolCall(contentResponse)
                                    append("\n=== CONTENT BEFORE TOOL CALL ===\n")
                                    append(if (contentBefore.isBlank()) "(empty)" else contentBefore)
                                    append("\n\n=== REASONING CONTENT ===\n")
                                    append(if (reasoningResponse.isBlank()) "(empty)" else reasoningResponse)
                                }
                            }
                            // Other cases - just show cleaned content or debug
                            toolCallEarlyDetected -> {
                                buildString {
                                    append("⚠️ Early detection triggered but no tool call found:\n\n")
                                    append("=== RAW RESPONSE ===\n")
                                    append(contentResponse)
                                    append("\n\n=== CLEANED ===\n")
                                    append(cleanedContent.ifBlank { "(empty)" })
                                }
                            }

                            else -> cleanedContent
                        }

                        // Final update to streaming state - PRESERVE UI STATE
                        _streamingState.value = StreamingMessageState(
                            messageId = assistantMessageId,
                            content = finalContent,
                            isThinking = false,
                            isStreaming = false,
                            toolUsed = wasToolUsed,  // Preserve tool usage from earlier execution
                            toolDisplayName = toolDisplayNameToUse  // Preserve tool display name
                        )

                        // Persist final state to database
                        chatRepository.updateMessageStateWithReasoning(
                            messageId = assistantMessageId,
                            content = finalContent,
                            isStreaming = false,
                            isThinking = false,
                            reasoningContent = reasoningResponse,
                            toolUsed = wasToolUsed,  // Preserve tool usage
                            toolDisplayName = toolDisplayNameToUse
                                ?: ""  // Preserve tool display name
                        )

                        _uiState.value = _uiState.value.copy(isGenerating = false)

                        // Clear streaming state after a brief delay
                        kotlinx.coroutines.delay(100)
                        _streamingState.value = null
                    }
                }
            },
            onError = { error ->
                viewModelScope.launch {
                    val currentToolState = _streamingState.value
                    _streamingState.value = null
                    chatRepository.updateMessageState(
                        messageId = assistantMessageId,
                        content = "Error: $error",
                        isStreaming = false,
                        isThinking = false,
                        toolUsed = currentToolState?.toolUsed ?: false,
                        toolDisplayName = currentToolState?.toolDisplayName ?: ""
                    )
                    _uiState.value = _uiState.value.copy(
                        isGenerating = false,
                        error = error
                    )
                }
            }
        )
    }

    /**
     * Stop the current generation
     */
    fun stopGeneration() {
        streamingJob?.cancel()
        streamingJob = null

        viewModelScope.launch {
            // Get the current streaming message ID
            val currentMessageId = _streamingState.value?.messageId
            val currentContent = _streamingState.value?.content ?: ""

            if (currentMessageId != null) {
                // Save whatever content we have so far
                chatRepository.updateMessageState(
                    messageId = currentMessageId,
                    content = currentContent,
                    isStreaming = false,
                    isThinking = false,
                    toolUsed = _streamingState.value?.toolUsed ?: false,
                    toolDisplayName = _streamingState.value?.toolDisplayName ?: ""
                )
            }

            // Clear streaming state
            _streamingState.value = null
            _uiState.value = _uiState.value.copy(isGenerating = false)
        }
    }


    fun setModel(model: String) {
        currentModel = model
        _uiState.value = _uiState.value.copy(selectedModel = model)
        viewModelScope.launch {
            settingsRepository.setDefaultModel(model)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Toggle the brain toggle (Lightbulb) state
     * Controls reasoning mode and tool-enabled system prompt
     */
    fun toggleBrain() {
        _brainToggleEnabled.value = !_brainToggleEnabled.value
    }
}

/**
 * UI State for the chat screen
 */
data class ChatUiState(
    val isGenerating: Boolean = false,
    val connectionStatus: ConnectionStatus = ConnectionStatus.NotConfigured,
    val availableModels: List<String> = emptyList(),
    val selectedModel: String = "",
    val error: String? = null
)

/**
 * Real-time streaming message state
 * This updates immediately on each token for responsive UI
 */
data class StreamingMessageState(
    val messageId: Long,
    val content: String,
    val isThinking: Boolean,
    val isStreaming: Boolean,
    val toolUsed: Boolean = false,
    val isExecutingTool: Boolean = false,
    val toolDisplayName: String? = null  // e.g., "Searching...", "Checking time..."
)

/**
 * Connection status to Ollama server
 */
enum class ConnectionStatus {
    NotConfigured,
    Connecting,
    Connected,
    Error
}
