package com.example.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class TextToSpeechHelper(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _currentMessageId = MutableStateFlow<String?>(null)
    val currentMessageId: StateFlow<String?> = _currentMessageId.asStateFlow()

    companion object {
        private const val TAG = "TextToSpeechHelper"
        val LOCALE_HINDI = Locale.forLanguageTag("hi-IN")
        val LOCALE_ENGLISH = Locale.US
    }

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                    _currentMessageId.value = utteranceId
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                    _currentMessageId.value = null
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                    _currentMessageId.value = null
                    Log.e(TAG, "TTS Error for utterance $utteranceId")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    _isSpeaking.value = false
                    _currentMessageId.value = null
                    Log.e(TAG, "TTS Error ($errorCode) for utterance $utteranceId")
                }
            })
            Log.d(TAG, "TTS successfully initialized")
        } else {
            Log.e(TAG, "TTS initialization failed with code $status")
        }
    }

    fun speak(messageId: String, text: String, preferredLanguage: String = "auto") {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not yet initialized")
            return
        }

        // If currently speaking this message, toggle off
        if (_isSpeaking.value && _currentMessageId.value == messageId) {
            stop()
            return
        }

        stop()

        val cleanText = sanitizeMarkdown(text)
        if (cleanText.isBlank()) return

        // Auto-detect Hindi characters
        val containsDevanagari = cleanText.any { it in '\u0900'..'\u097F' }
        val targetLocale = when {
            containsDevanagari -> LOCALE_HINDI
            preferredLanguage == "hi" -> LOCALE_HINDI
            else -> LOCALE_ENGLISH
        }

        val result = tts?.setLanguage(targetLocale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Target locale $targetLocale not fully supported, falling back to default")
            tts?.language = Locale.getDefault()
        }

        tts?.setSpeechRate(0.95f)
        tts?.setPitch(1.0f)

        _currentMessageId.value = messageId
        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, messageId)
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
        _currentMessageId.value = null
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    private fun sanitizeMarkdown(raw: String): String {
        return raw
            // Remove code blocks
            .replace(Regex("```[\\s\\S]*?```"), "Code block omitted.")
            // Remove inline code
            .replace(Regex("`([^`]+)`"), "$1")
            // Remove headers
            .replace(Regex("#+\\s*"), "")
            // Remove bold/italics
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("\\*([^*]+)\\*"), "$1")
            .replace(Regex("__([^_]+)__"), "$1")
            .replace(Regex("_([^_]+)_"), "$1")
            // Remove bullet points
            .replace(Regex("^[\\s]*[-*+]\\s+", RegexOption.MULTILINE), "")
            // Remove links
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
            // Remove extra whitespace
            .replace(Regex("\\n{2,}"), "\n")
            .trim()
    }
}
