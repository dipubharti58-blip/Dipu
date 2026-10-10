package com.example.data.repository

import com.example.data.local.ChatDao
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatSessionEntity
import com.example.data.remote.GeminiClient
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ChatRepository(
    private val chatDao: ChatDao,
    private val geminiClient: GeminiClient
) {
    val allSessions: Flow<List<ChatSessionEntity>> = chatDao.getAllSessions()

    fun getMessagesForSession(sessionId: String): Flow<List<ChatMessageEntity>> {
        return chatDao.getMessagesForSession(sessionId)
    }

    suspend fun createNewSession(title: String = "New Chat"): ChatSessionEntity {
        val session = ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        chatDao.insertSession(session)
        return session
    }

    suspend fun getSessionById(sessionId: String): ChatSessionEntity? {
        return chatDao.getSessionById(sessionId)
    }

    suspend fun updateSessionTitle(sessionId: String, title: String) {
        val existing = chatDao.getSessionById(sessionId)
        if (existing != null) {
            chatDao.updateSession(existing.copy(title = title, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun deleteSession(sessionId: String) {
        chatDao.deleteMessagesForSession(sessionId)
        chatDao.deleteSession(sessionId)
    }

    suspend fun clearAllHistory() {
        chatDao.clearAllMessages()
        chatDao.clearAllSessions()
    }

    suspend fun sendMessage(
        sessionId: String,
        userPrompt: String,
        preferredLanguage: String
    ): Result<ChatMessageEntity> {
        val userMessage = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            sender = "user",
            text = userPrompt.trim(),
            timestamp = System.currentTimeMillis()
        )
        chatDao.insertMessage(userMessage)

        // Update session updatedAt and auto-title if first message
        val currentSession = chatDao.getSessionById(sessionId)
        if (currentSession != null) {
            val newTitle = if (currentSession.title == "New Chat") {
                val clean = userPrompt.trim().take(36)
                if (clean.length < userPrompt.trim().length) "$clean…" else clean
            } else {
                currentSession.title
            }
            chatDao.updateSession(
                currentSession.copy(
                    title = newTitle,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

        // Fetch prior history for multi-turn context
        val history = chatDao.getMessagesListForSession(sessionId)

        // Call Gemini
        val result = geminiClient.generateResponse(
            prompt = userPrompt,
            history = history,
            preferredLanguage = preferredLanguage
        )

        return if (result.isSuccess) {
            val replyText = result.getOrNull() ?: ""
            val modelMessage = ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                sender = "model",
                text = replyText,
                timestamp = System.currentTimeMillis(),
                isError = false
            )
            chatDao.insertMessage(modelMessage)
            Result.success(modelMessage)
        } else {
            val error = result.exceptionOrNull()
            val errorMessage = error?.message ?: "Unknown error while contacting AI service."
            val modelErrorMessage = ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                sender = "model",
                text = errorMessage,
                timestamp = System.currentTimeMillis(),
                isError = true
            )
            chatDao.insertMessage(modelErrorMessage)
            Result.failure(error ?: Exception(errorMessage))
        }
    }

    suspend fun retryLastMessage(
        sessionId: String,
        failedMessageId: String,
        preferredLanguage: String
    ): Result<ChatMessageEntity> {
        val history = chatDao.getMessagesListForSession(sessionId)
        val failedIndex = history.indexOfFirst { it.id == failedMessageId }
        val prompt = if (failedIndex > 0) {
            history[failedIndex - 1].text
        } else {
            "Hello"
        }

        // Remove the failed message
        chatDao.insertMessage(
            ChatMessageEntity(
                id = failedMessageId,
                sessionId = sessionId,
                sender = "model",
                text = "Retrying...",
                timestamp = System.currentTimeMillis(),
                isError = false
            )
        )

        val result = geminiClient.generateResponse(
            prompt = prompt,
            history = history.filter { it.id != failedMessageId },
            preferredLanguage = preferredLanguage
        )

        return if (result.isSuccess) {
            val replyText = result.getOrNull() ?: ""
            val updated = ChatMessageEntity(
                id = failedMessageId,
                sessionId = sessionId,
                sender = "model",
                text = replyText,
                timestamp = System.currentTimeMillis(),
                isError = false
            )
            chatDao.updateMessage(updated)
            Result.success(updated)
        } else {
            val errText = result.exceptionOrNull()?.message ?: "Retry failed."
            val updated = ChatMessageEntity(
                id = failedMessageId,
                sessionId = sessionId,
                sender = "model",
                text = errText,
                timestamp = System.currentTimeMillis(),
                isError = true
            )
            chatDao.updateMessage(updated)
            Result.failure(result.exceptionOrNull() ?: Exception(errText))
        }
    }

    fun isApiKeyConfigured(): Boolean = geminiClient.isApiKeyConfigured()
    fun getApiKey(): String = geminiClient.getApiKey()
    fun setCustomApiKey(key: String) = geminiClient.setCustomApiKey(key)
}
