package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatSessionEntity
import com.example.data.remote.GeminiClient
import com.example.data.repository.ChatRepository
import com.example.util.NetworkMonitor
import com.example.util.SpeechRecognitionHelper
import com.example.util.TextToSpeechHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ChatRepository
    private val networkMonitor = NetworkMonitor(application)
    private val ttsHelper = TextToSpeechHelper(application)
    private var speechHelper: SpeechRecognitionHelper? = null

    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = networkMonitor.isCurrentlyConnected()
    )

    val sessions: StateFlow<List<ChatSessionEntity>>

    private val _currentSession = MutableStateFlow<ChatSessionEntity?>(null)
    val currentSession: StateFlow<ChatSessionEntity?> = _currentSession.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    val messages: StateFlow<List<ChatMessageEntity>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    // "auto", "hi" (Hindi), "en" (English)
    private val _selectedLanguage = MutableStateFlow("auto")
    val selectedLanguage: StateFlow<String> = _selectedLanguage.asStateFlow()

    private val _isApiKeyConfigured = MutableStateFlow(true)
    val isApiKeyConfigured: StateFlow<Boolean> = _isApiKeyConfigured.asStateFlow()

    val isSpeaking: StateFlow<Boolean> = ttsHelper.isSpeaking
    val speakingMessageId: StateFlow<String?> = ttsHelper.currentMessageId

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _soundLevel = MutableStateFlow(0f)
    val soundLevel: StateFlow<Float> = _soundLevel.asStateFlow()

    private val _snackbarEvent = MutableSharedFlow<String>()
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    private var messageObservationJob: Job? = null

    init {
        val db = AppDatabase.getDatabase(application)
        val geminiClient = GeminiClient(application)
        repository = ChatRepository(db.chatDao(), geminiClient)

        _isApiKeyConfigured.value = repository.isApiKeyConfigured()

        sessions = repository.allSessions.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        speechHelper = SpeechRecognitionHelper(
            context = application,
            onResult = { text ->
                _inputText.value = text
                _isListening.value = false
            },
            onError = { error ->
                _isListening.value = false
                viewModelScope.launch {
                    _snackbarEvent.emit(error)
                }
            }
        )

        // Monitor speech helper states
        viewModelScope.launch {
            speechHelper?.isListening?.collect { listening ->
                _isListening.value = listening
            }
        }
        viewModelScope.launch {
            speechHelper?.soundLevel?.collect { level ->
                _soundLevel.value = level
            }
        }

        // Initialize with first session or create one
        viewModelScope.launch {
            sessions.collect { sessionList ->
                if (_currentSession.value == null) {
                    if (sessionList.isNotEmpty()) {
                        selectSession(sessionList.first())
                    } else {
                        createNewSession()
                    }
                }
            }
        }
    }

    fun selectSession(session: ChatSessionEntity) {
        _currentSession.value = session
        messageObservationJob?.cancel()
        messageObservationJob = viewModelScope.launch {
            repository.getMessagesForSession(session.id).collect { msgs ->
                _messages.value = msgs
            }
        }
    }

    fun createNewSession() {
        ttsHelper.stop()
        viewModelScope.launch {
            val newSession = repository.createNewSession()
            selectSession(newSession)
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            if (_currentSession.value?.id == sessionId) {
                _currentSession.value = null
            }
        }
    }

    fun clearAllHistory() {
        ttsHelper.stop()
        viewModelScope.launch {
            repository.clearAllHistory()
            _currentSession.value = null
            createNewSession()
            _snackbarEvent.emit("Chat history cleared.")
        }
    }

    fun setInputText(text: String) {
        _inputText.value = text
    }

    fun setLanguage(lang: String) {
        _selectedLanguage.value = lang
    }

    fun sendMessage(promptOverride: String? = null) {
        val prompt = (promptOverride ?: _inputText.value).trim()
        if (prompt.isEmpty() || _isLoading.value) return

        val activeSession = _currentSession.value ?: return

        // Clear input box
        if (promptOverride == null) {
            _inputText.value = ""
        }

        ttsHelper.stop()
        _isLoading.value = true

        viewModelScope.launch {
            val result = repository.sendMessage(
                sessionId = activeSession.id,
                userPrompt = prompt,
                preferredLanguage = _selectedLanguage.value
            )

            _isLoading.value = false
            _isApiKeyConfigured.value = repository.isApiKeyConfigured()

            if (result.isFailure) {
                val errorMsg = result.exceptionOrNull()?.message ?: "Failed to get AI reply"
                _snackbarEvent.emit(errorMsg)
            }
        }
    }

    fun retryMessage(messageId: String) {
        val activeSession = _currentSession.value ?: return
        if (_isLoading.value) return

        ttsHelper.stop()
        _isLoading.value = true

        viewModelScope.launch {
            val result = repository.retryLastMessage(
                sessionId = activeSession.id,
                failedMessageId = messageId,
                preferredLanguage = _selectedLanguage.value
            )
            _isLoading.value = false
            if (result.isFailure) {
                _snackbarEvent.emit(result.exceptionOrNull()?.message ?: "Retry failed")
            }
        }
    }

    fun toggleTts(messageId: String, text: String) {
        ttsHelper.speak(messageId, text, _selectedLanguage.value)
    }

    fun stopTts() {
        ttsHelper.stop()
    }

    fun startVoiceInput() {
        ttsHelper.stop()
        speechHelper?.startListening(_selectedLanguage.value)
    }

    fun stopVoiceInput() {
        speechHelper?.stopListening()
    }

    fun onSpeechResult(recognizedText: String) {
        _inputText.value = recognizedText
    }

    fun setCustomApiKey(key: String) {
        repository.setCustomApiKey(key)
        _isApiKeyConfigured.value = repository.isApiKeyConfigured()
        viewModelScope.launch {
            _snackbarEvent.emit("API key updated successfully!")
        }
    }

    fun getApiKey(): String = repository.getApiKey()

    override fun onCleared() {
        super.onCleared()
        ttsHelper.shutdown()
        speechHelper?.stopListening()
    }
}
