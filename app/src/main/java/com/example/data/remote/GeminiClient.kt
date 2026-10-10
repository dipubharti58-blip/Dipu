package com.example.data.remote

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.local.ChatMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiClient(private val context: Context) {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val TAG = "GeminiClient"
        private const val PREFS_NAME = "dipu_ai_prefs"
        private const val KEY_CUSTOM_API_KEY = "custom_gemini_api_key"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
        val PRIMARY_MODEL = "gemini-2.5-flash"
        val FALLBACK_MODEL = "gemini-flash-latest"
    }

    fun getApiKey(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customKey = prefs.getString(KEY_CUSTOM_API_KEY, "")?.trim() ?: ""
        if (customKey.isNotEmpty()) {
            return customKey
        }
        val buildKey = BuildConfig.GEMINI_API_KEY.trim()
        if (buildKey.isNotEmpty() && buildKey != "MY_GEMINI_API_KEY") {
            return buildKey
        }
        return ""
    }

    fun setCustomApiKey(key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_API_KEY, key.trim()).apply()
    }

    fun isApiKeyConfigured(): Boolean {
        return getApiKey().isNotEmpty()
    }

    suspend fun generateResponse(
        prompt: String,
        history: List<ChatMessageEntity> = emptyList(),
        preferredLanguage: String = "auto"
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException(
                    "Gemini API key is not configured. Please add GEMINI_API_KEY to the Secrets panel in AI Studio or enter it in app Settings."
                )
            )
        }

        // Try primary model first, fallback if model unavailable
        val primaryResult = executeGenerateContent(PRIMARY_MODEL, apiKey, prompt, history, preferredLanguage)
        if (primaryResult.isSuccess) {
            return@withContext primaryResult
        }

        val error = primaryResult.exceptionOrNull()
        if (error != null && (error.message?.contains("404") == true || error.message?.contains("NOT_FOUND") == true)) {
            Log.w(TAG, "Primary model failed with 404, attempting fallback model $FALLBACK_MODEL")
            return@withContext executeGenerateContent(FALLBACK_MODEL, apiKey, prompt, history, preferredLanguage)
        }

        return@withContext primaryResult
    }

    private fun executeGenerateContent(
        model: String,
        apiKey: String,
        prompt: String,
        history: List<ChatMessageEntity>,
        preferredLanguage: String
    ): Result<String> {
        try {
            val url = "$BASE_URL$model:generateContent?key=$apiKey"

            val contentsArray = JSONArray()

            // Include up to the last 12 previous turns for context and speed
            val recentHistory = history.takeLast(12)
            for (msg in recentHistory) {
                if (msg.isError || msg.text.isBlank()) continue
                val contentObj = JSONObject()
                contentObj.put("role", if (msg.sender == "user") "user" else "model")
                val partsArray = JSONArray()
                val partObj = JSONObject()
                partObj.put("text", msg.text)
                partsArray.put(partObj)
                contentObj.put("parts", partsArray)
                contentsArray.put(contentObj)
            }

            // Current prompt
            val currentContent = JSONObject()
            currentContent.put("role", "user")
            val currentParts = JSONArray()
            val currentPart = JSONObject()
            currentPart.put("text", prompt)
            currentParts.put(currentPart)
            currentContent.put("parts", currentParts)
            contentsArray.put(currentContent)

            val requestJson = JSONObject()
            requestJson.put("contents", contentsArray)

            // System instruction tailored for Dipu AI with bilingual competence
            val langHint = when (preferredLanguage) {
                "hi" -> " The user prefers responses in Hindi (हिन्दी)."
                "en" -> " The user prefers responses in English."
                else -> " Auto-detect user language: if the user asks in Hindi/Hinglish, respond in polite, clear Hindi. If in English, respond in English."
            }

            val systemInstruction = JSONObject()
            val sysParts = JSONArray()
            val sysPart = JSONObject()
            sysPart.put(
                "text",
                "You are Dipu AI, an intelligent, helpful, kind, and capable personal AI assistant. " +
                        "You understand both English and Hindi (हिन्दी), as well as Hinglish fluently.$langHint " +
                        "Provide well-structured answers using clean markdown formatting (headings, bullet points, bold keywords, code blocks). " +
                        "Keep tone warm, courteous, and respectful."
            )
            sysParts.put(sysPart)
            systemInstruction.put("parts", sysParts)
            requestJson.put("systemInstruction", systemInstruction)

            val generationConfig = JSONObject()
            generationConfig.put("temperature", 0.7)
            generationConfig.put("maxOutputTokens", 2048)
            requestJson.put("generationConfig", generationConfig)

            val requestBody = requestJson.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = parseErrorMessage(response.code, responseBody)
                return Result.failure(Exception(errorMsg))
            }

            val root = JSONObject(responseBody)
            if (root.has("candidates")) {
                val candidates = root.getJSONArray("candidates")
                if (candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val textBuilder = StringBuilder()
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("text")) {
                                textBuilder.append(part.getString("text"))
                            }
                        }
                        val text = textBuilder.toString().trim()
                        if (text.isNotEmpty()) {
                            return Result.success(text)
                        }
                    }
                }
            }

            if (root.has("error")) {
                val errObj = root.getJSONObject("error")
                val message = errObj.optString("message", "Unknown error from Gemini API")
                return Result.failure(Exception(message))
            }

            return Result.failure(Exception("Received empty response from Gemini API."))
        } catch (e: IOException) {
            Log.e(TAG, "Network error calling Gemini", e)
            return Result.failure(
                Exception("Network connection failed. Please check your internet connectivity and try again.")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error calling Gemini", e)
            return Result.failure(e)
        }
    }

    private fun parseErrorMessage(statusCode: Int, responseBody: String): String {
        return try {
            val root = JSONObject(responseBody)
            val errorObj = root.optJSONObject("error")
            val apiMsg = errorObj?.optString("message") ?: ""

            when (statusCode) {
                400 -> {
                    if (apiMsg.contains("API_KEY_INVALID", ignoreCase = true) ||
                        apiMsg.contains("API key not valid", ignoreCase = true)
                    ) {
                        "Invalid Gemini API key. Please check your key in the Secrets panel in AI Studio or Settings."
                    } else {
                        "Bad Request: $apiMsg"
                    }
                }
                403 -> "Access forbidden: Check if Gemini API is enabled for your Google Cloud project."
                404 -> "Model not found (404): $apiMsg"
                429 -> "Rate limit reached. Please wait a moment before sending another prompt."
                500, 503 -> "Gemini AI service temporarily unavailable. Please try again shortly."
                else -> if (apiMsg.isNotEmpty()) apiMsg else "Gemini API error ($statusCode)"
            }
        } catch (_: Exception) {
            "API error (HTTP $statusCode). Please check your internet connection and API key."
        }
    }
}
