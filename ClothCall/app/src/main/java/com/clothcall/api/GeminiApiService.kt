package com.clothcall.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import com.clothcall.utils.ScanResultHolder
import java.util.concurrent.TimeUnit

private const val TAG = "GroqAPI"
private const val BASE_URL = "https://api.groq.com/openai/v1/chat/completions"
private const val MODEL = "meta-llama/llama-4-scout-17b-16e-instruct"

private val SYSTEM_PROMPT = """
You are a clothing condition assistant. You receive two images: the first is a baseline photo of a garment when it was in good condition, the second is today's photo of the same garment being worn.

Your job is to compare them and report any stains, marks, or fading visible in today's photo that were not present or were less visible in the baseline.

Rules you must follow without exception:
- Never say "I am an AI", "as an AI", "I should note", "I cannot", "I would suggest", or any self-referential language
- Never add disclaimers, caveats, safety notes, or suggestions to consult anyone
- Never use commanding language: never say "you should", "you must", "I recommend", "consider", or "make sure"
- Use passive voice: "a mark is visible" not "I can see a mark", "fading is noticeable" not "I notice fading"
- Describe stain location precisely using garment region and side: "near the lower left cuff", "along the right collar edge", "on the front center panel"
- For fading, compare directly to the baseline using soft language: "appears slightly lighter than the baseline photo", "color drift is visible around the shoulder area compared to the reference"
- If a caregiver name and threshold are provided, reference them by name: "this is around where [name] marked similar items as borderline", "still within the range [name] considered fine", "this has passed the level [name] marked as acceptable"
- If confidence is low due to lighting or angle, say so in one short phrase before the final question: "Lighting makes this difficult to assess with certainty, but a faint mark appears near the left pocket."
- Maximum length: 3 sentences total plus the final question
- The last sentence of every response must be exactly: Do you still want to wear it?
- Do not add anything after that sentence
""".trimIndent()

private val SINGLE_IMAGE_PROMPT = """
You are a clothing condition assistant. You receive one image of a garment.

Your job is to report any visible stains, marks, discoloration, damage, or fading based on the garment's current appearance.

Rules you must follow without exception:
- Never say "I am an AI", "as an AI", "I should note", "I cannot", or any self-referential language
- Never add disclaimers, caveats, or suggestions
- Never use commanding language: never say "you should", "you must", or "I recommend"
- Use passive voice throughout: "a mark is visible" not "I can see a mark", "fading is apparent" not "I notice fading"
- Describe stain location precisely: "near the lower left cuff", "along the right collar edge"
- Assess fading based on the garment's current visible appearance — describe overall color saturation and visible wear
- If a caregiver name and fade threshold are provided in the user message, calibrate your fading language exactly to their tolerance:
  - Fading clearly below the threshold → "still within the range [name] considers acceptable"
  - Fading at or near the threshold → "around the level [name] marked as borderline"
  - Fading clearly above the threshold → "this has passed the level [name] marked as acceptable"
- If a caregiver name is provided, reference them by name when describing both stains and fading
- If nothing is found, say: "No visible marks, stains, or fading were detected on this garment. Do you still want to wear it?"
- If confidence is low due to lighting or angle, say so in one short phrase before the assessment
- Maximum length: 3 sentences total plus the final question
- The last sentence of every response must be exactly: Do you still want to wear it?
- Do not add anything after that sentence
""".trimIndent()

class GeminiApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeClothing(
        apiKey: String,
        base64Image: String,
        baselineBase64: String? = null,
        caregiverName: String?,
        fadeThreshold: Int?
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "analyzeClothing — base64 len: ${base64Image.length}, hasBaseline: ${baselineBase64 != null}")
            val reportedStains = ScanResultHolder.reportedStains.takeIf { it.isNotEmpty() }?.joinToString("; ")
            val (userMsg, systemPrompt) = if (baselineBase64 != null) {
                userMessageWithTwoImages(baselineBase64, base64Image, caregiverName, fadeThreshold, reportedStains) to SYSTEM_PROMPT
            } else {
                userMessageWithImage(base64Image, caregiverName, fadeThreshold, reportedStains) to SINGLE_IMAGE_PROMPT
            }
            val messages = JSONArray().apply {
                put(systemMessage(systemPrompt))
                put(userMsg)
            }
            extractText(post(apiKey, buildBody(messages)))
        }.also { result ->
            result.onFailure { Log.e(TAG, "analyzeClothing failed", it) }
        }
    }

    suspend fun requestMoreDetail(
        apiKey: String,
        base64Image: String,
        baselineBase64: String? = null,
        followUpText: String,
        firstResponseText: String,
        caregiverName: String?,
        fadeThreshold: Int?
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val (firstUserMsg, systemPrompt) = if (baselineBase64 != null) {
                userMessageWithTwoImages(baselineBase64, base64Image, caregiverName, fadeThreshold, null) to SYSTEM_PROMPT
            } else {
                userMessageWithImage(base64Image, caregiverName, fadeThreshold, null) to SINGLE_IMAGE_PROMPT
            }
            val messages = JSONArray().apply {
                put(systemMessage(systemPrompt))
                put(firstUserMsg)
                put(assistantMessage(ScanResultHolder.conversationHistory.firstOrNull()?.second ?: firstResponseText))
                put(userMessageText(followUpText))
            }
            extractText(post(apiKey, buildBody(messages)))
        }.also { result ->
            result.onFailure { Log.e(TAG, "requestMoreDetail failed", it) }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun systemMessage(prompt: String) = JSONObject().apply {
        put("role", "system")
        put("content", prompt)
    }

    private fun imageUrlPart(base64: String) = JSONObject().apply {
        put("type", "image_url")
        put("image_url", JSONObject().apply {
            put("url", "data:image/jpeg;base64,$base64")
        })
    }

    private fun textPart(text: String) = JSONObject().apply {
        put("type", "text")
        put("text", text)
    }

    private fun userMessageWithImage(
        base64Image: String,
        caregiverName: String?,
        fadeThreshold: Int?,
        reportedStains: String?
    ): JSONObject {
        val text = buildString {
            append("Please analyze this clothing item.")
            if (caregiverName != null) {
                append(" Caregiver name: $caregiverName.")
            }
            if (fadeThreshold != null) {
                append(" Fade threshold: $fadeThreshold%.")
                append(" Any fading below this threshold should be described as still acceptable to them.")
                append(" Any fading at or above this threshold should be described as at or past their limit.")
            }
            if (!reportedStains.isNullOrBlank()) {
                append(" Already reported this session and must not be mentioned again: $reportedStains. Only describe new findings not in this list.")
            }
        }
        return JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply {
                put(imageUrlPart(base64Image))
                put(textPart(text))
            })
        }
    }

    private fun userMessageWithTwoImages(
        baselineBase64: String,
        currentBase64: String,
        caregiverName: String?,
        fadeThreshold: Int?,
        reportedStains: String?
    ): JSONObject {
        val text = buildString {
            append("The first image is the baseline reference for this garment. The second image is what is being worn today. Compare them for fading, stains, and condition changes.")
            if (caregiverName != null) {
                append(" Caregiver name: $caregiverName. Fade threshold: ${fadeThreshold ?: 0}%. Any fading below this threshold should be described as still acceptable to them. Any fading at or above this threshold should be described as at or past their limit.")
            } else if (fadeThreshold != null) {
                append(" Fade threshold: $fadeThreshold%. Any fading below this threshold should be described as still acceptable to them. Any fading at or above this threshold should be described as at or past their limit.")
            }
            if (!reportedStains.isNullOrBlank()) {
                append(" Already reported this session and must not be mentioned again: $reportedStains. Only describe new findings not in this list.")
            }
        }
        return JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply {
                put(imageUrlPart(baselineBase64))
                put(imageUrlPart(currentBase64))
                put(textPart(text))
            })
        }
    }

    private fun assistantMessage(text: String) = JSONObject().apply {
        put("role", "assistant")
        put("content", text)
    }

    private fun userMessageText(text: String) = JSONObject().apply {
        put("role", "user")
        put("content", JSONArray().apply {
            put(textPart(text))
        })
    }

    private fun buildBody(messages: JSONArray) = JSONObject().apply {
        put("model", MODEL)
        put("messages", messages)
        put("max_tokens", 1024)
    }

    private fun post(apiKey: String, body: JSONObject): String {
        if (apiKey.isBlank()) throw IllegalArgumentException("API key is empty — enter it in settings")

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string() ?: ""
            Log.d(TAG, "HTTP ${response.code} — body length ${raw.length}")
            if (!response.isSuccessful) {
                Log.e(TAG, "Error body: $raw")
                val errorMsg = runCatching {
                    JSONObject(raw).getJSONObject("error").getString("message")
                }.getOrNull()
                throw Exception(errorMsg ?: "HTTP ${response.code}: $raw")
            }
            return raw
        }
    }

    private fun extractText(rawJson: String): String {
        val json = JSONObject(rawJson)
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw Exception("Groq returned no choices")
        }
        val content = choices.getJSONObject(0)
            .optJSONObject("message")
            ?.optString("content")
        if (content.isNullOrBlank()) {
            val finishReason = choices.getJSONObject(0).optString("finish_reason")
            throw Exception("Groq response had empty content (finish_reason: $finishReason)")
        }
        return content
    }
}
