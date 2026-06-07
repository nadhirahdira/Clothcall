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
- For fading, compare directly to the baseline using soft language: "appears slightly lighter than the baseline photo", "colour drift is visible around the shoulder area compared to the reference"
- If a trusted person's name and threshold are provided: their threshold is a private number used only for your own internal comparison — never speak it aloud, in any form ("twenty percent", "20%", "a threshold of 20", "this is at 15 percent" are all forbidden). Always refer to them by their actual name. Never call them "your trusted person", "the trusted person", or any label — only ever use the name given.
  Frame all fading judgements in their voice, as though reporting their personal opinion about this specific garment, using only these qualitative buckets:
  - Fading clearly below their threshold → "[Name] would still consider this fine to wear" or "this is well within what [Name] would accept for this type of garment"
  - Fading approaching their threshold → "[Name] might still consider this fine to wear, though it's getting close to what they'd call borderline"
  - Fading at or beyond their threshold → "[Name] would consider this not wearable" or "this has passed the point where [Name] would still wear it"
- If no trusted person name is provided, do not mention any trusted person at all and do not invent or assume a name
- If confidence is low due to lighting or angle, note it as a passive observation only ("lighting limits precision here", "angle reduces detail visibility") — never suggest the user adjust position, hold the phone, or take another photo
- Maximum length: 3 sentences total plus the final question
- The last sentence of every response must be exactly: Do you still want to wear it?
- Do not add anything after that sentence
""".trimIndent()

private const val FOLLOW_UP_RULES = """

This is a follow-up question from the user about the same garment. Answer conversationally and briefly — maximum 2 sentences. Stay focused on the garment and the user's question. Do not repeat the original report. Do not add disclaimers. Do not say "I am an AI". End with "Do you still want to wear it? Say yes, no, or ask me anything." only if the conversation has not yet been resolved.
"""

private val INTENT_CLASSIFIER_PROMPT = """
You classify user voice responses during a clothing check assistant interaction. The user has just heard a clothing condition report and responded verbally.

Classify their response into exactly one of these intents:
- yes: they want to wear the item (e.g. "yes", "sure", "ok", "fine", "that's fine", "looks good", "I'll wear it")
- no: they do not want to wear it (e.g. "no", "nope", "I'll change", "let me change", "I won't wear it")
- repeat: they want to hear the report again (e.g. "repeat", "say that again", "what did you say", "again")
- already_know: they already know about the issue (e.g. "I know", "already know", "I saw it", "I know about that")
- more_detail: they want more specific information about the same issue (e.g. "more detail", "tell me more", "where exactly", "how bad is it")
- question: they are asking a new follow-up question about the garment that requires reasoning (anything that does not fit the above, including questions about visibility, context, whether to wear it to a specific place, whether a jacket would cover it, etc.)

Reply with only the intent word. Nothing else.
""".trimIndent()

private val ALIGNMENT_PROMPT = """
You are a camera alignment assistant helping a blind user photograph a clothing item they are wearing or holding up to the camera. The user cannot see the preview and cannot judge spatial orientation (left/right/up/down relative to the frame) — only give guidance the user can act on without seeing anything: how far away to hold the camera, and whether the garment is in view at all.

Judge strictly from what is visible in THIS image — how much of the frame the clothing fills and how even the lighting looks. Do not default to a habitual answer.

Pick exactly the one guidance string below that matches what you see, and reply with that string only:

- "Good" — the clothing fills most of the frame and lighting is even, even if not perfectly centered. Favor this whenever the framing is already usable for a photo.
- "Move closer" — the clothing looks small with a lot of empty space surrounding it
- "Move back" — the clothing fills nearly the whole frame and visibly runs off the edges
- "Recenter" — part of the clothing is visible but it is mostly out of frame, cut off, or only a small sliver is showing along one side
- "No clothing found" — no wearable garment appears anywhere in the frame
- "Too dark" — the frame is too dark or low-contrast to make out the clothing's shape

Reply with only the matching string from the list. Nothing else.
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
- Assess fading based on the garment's current visible appearance — describe overall colour saturation and visible wear
- If a trusted person's name and fade threshold are provided: their threshold is a private number used only for your own internal comparison — never speak it aloud, in any form ("twenty percent", "20%", "a threshold of 20", "this is at 15 percent" are all forbidden). Always refer to them by their actual name. Never call them "your trusted person", "the trusted person", or any label — only ever use the name given.
  Frame all fading judgements in their voice, as though reporting their personal opinion about this specific garment, using only these qualitative buckets:
  - Fading clearly below their threshold → "[Name] would still consider this fine to wear" or "this is well within what [Name] would accept for this type of garment"
  - Fading approaching their threshold → "[Name] might still consider this fine to wear, though it's getting close to what they'd call borderline"
  - Fading at or beyond their threshold → "[Name] would consider this not wearable" or "this has passed the point where [Name] would still wear it"
- If a trusted person's name is provided, reference them by name when describing stains as well
- If no trusted person name is provided, do not mention any trusted person at all and do not invent or assume a name
- If nothing is found, say: "No visible marks, stains, or fading were detected on this garment. Do you still want to wear it?"
- If confidence is low due to lighting or angle, note it as a passive observation only ("lighting limits precision here", "angle reduces detail visibility") — never suggest the user adjust position, hold the phone, or take another photo
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

    suspend fun classifyAlignment(
        apiKey: String,
        base64Jpeg: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val messages = JSONArray().apply {
                put(systemMessage(ALIGNMENT_PROMPT))
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(imageUrlPart(base64Jpeg))
                        put(textPart("Assess camera alignment for photographing this clothing item."))
                    })
                })
            }
            val body = JSONObject().apply {
                put("model", MODEL)
                put("messages", messages)
                put("max_tokens", 12)
            }
            extractText(post(apiKey, body)).trim()
        }.also { result ->
            result.onFailure { Log.e(TAG, "classifyAlignment failed", it) }
        }
    }

    suspend fun classifyIntent(
        apiKey: String,
        rawText: String,
        currentResponse: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val userText = "User said: $rawText. Current report was: ${currentResponse.take(100)}."
            val messages = JSONArray().apply {
                put(systemMessage(INTENT_CLASSIFIER_PROMPT))
                put(userMessageText(userText))
            }
            val body = JSONObject().apply {
                put("model", MODEL)
                put("messages", messages)
                put("max_tokens", 10)
            }
            extractText(post(apiKey, body)).trim().lowercase()
        }.also { result ->
            result.onFailure { Log.e(TAG, "classifyIntent failed", it) }
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
                put(systemMessage(systemPrompt + FOLLOW_UP_RULES))
                put(firstUserMsg)
                // Replay full conversation history so every prior follow-up and answer
                // is in context — without this, the second+ follow-up has no memory.
                val history = ScanResultHolder.conversationHistory
                if (history.isEmpty()) {
                    put(assistantMessage(firstResponseText))
                } else {
                    for ((role, content) in history) {
                        when (role) {
                            "assistant" -> put(assistantMessage(content))
                            "user"      -> put(userMessageText(content))
                        }
                    }
                }
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
            append("Please analyse this clothing item.")
            if (caregiverName != null) {
                append(" Trusted person's name: $caregiverName.")
            }
            if (fadeThreshold != null) {
                append(" Their personal fade threshold for internal comparison only — never speak this number aloud: $fadeThreshold%" +
                    " (the point at which they first rated fading as borderline on comparable fabric).")
            }
            if (!reportedStains.isNullOrBlank()) {
                append(" Already reported this session — do not mention again: $reportedStains.")
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
            append("The first image is the baseline reference for this garment. " +
                "The second image is what is being worn today. Compare them for fading, stains, and condition changes.")
            if (caregiverName != null) {
                append(" Trusted person's name: $caregiverName.")
            }
            if (fadeThreshold != null) {
                append(" Their personal fade threshold for internal comparison only — never speak this number aloud: $fadeThreshold%" +
                    " (the point at which they first rated fading as borderline on comparable fabric).")
            }
            if (!reportedStains.isNullOrBlank()) {
                append(" Already reported this session — do not mention again: $reportedStains.")
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
