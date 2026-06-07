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
  Frame all fading judgements in their voice, as though reporting their personal opinion about this specific garment. Lead with a short visual observation of the fading (per the soft-language rule above — where it sits, how it compares to the baseline), then immediately follow with their opinion in the same sentence, using only these qualitative buckets for the opinion half:
  - Fading clearly below their threshold → "...— in terms of fading, [Name] would still consider this fine" or "...— as far as the fading goes, this is well within what [Name] would accept for this type of garment"
  - Fading approaching their threshold → "...— in terms of fading, [Name] might still consider this fine, though it's getting close to what they'd call borderline"
  - Fading at or beyond their threshold → "...— in terms of fading, [Name] would consider this not wearable" or "...— as far as the fading goes, this has passed the point where [Name] would still wear it"
  Always anchor the verdict explicitly to fading ("in terms of fading...", "as far as the fading goes...") — never phrase it as a bare, unqualified "fine to wear"/"not wearable" judgement on the garment as a whole, since that would wrongly read as overriding or dismissing any separately-reported stains or marks.
- If a trusted person's name and threshold are provided, do not describe fading without also giving the named fading verdict in the same sentence. A response that mentions fading but omits the person's name is incomplete.
- The qualitative-bucket opinion language above applies ONLY to fading judgements against their threshold — never reuse it, or any "fine to wear" verdict, when describing stains, marks, or damage. Stains have no calibrated threshold, so describe them neutrally (location, size, severity) without attributing an opinion to the trusted person.
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
You are ClothCall, a clothing condition assistant for blind and visually impaired users. You receive one image of a garment.

Your job is to describe visible clothing condition issues that may affect whether the garment is suitable to wear today. Focus only on what is visible in the image: stains, marks, discoloration, fading, fabric wear, holes, or damage.

Rules:
- Never say "I am an AI", "as an AI", "I can see", "I notice", or any self-referential phrase.
- Never give commands or advice such as "you should", "you must", "I recommend", "consider", or "try".
- Use passive, observational language: "a dark mark is visible", "fading appears noticeable", "the fabric looks worn".
- Be brief, calm, and specific.
- Describe the location of each issue using garment regions and sides.
- If image quality limits certainty, say so briefly as an observation only.
- Do not ask the user to retake the photo.

Stain and mark rules:
- Mention stains, spots, marks, or damage only when visibly present.
- Describe their color, size, severity, and location when possible.
- Do not involve the trusted person when describing stains or marks.

Fading rules:
- Assess fading only from visible color unevenness, dullness, washed-out areas, or worn fabric appearance.
- The fade threshold is private and must never be spoken aloud as a number, percentage, level, or rating.
- If a trusted person's name and fade threshold are provided, use the name only for fading judgments.
- Treat the fade threshold as a strict ceiling, not a rough guideline. A threshold of 0, or close to 0, means essentially zero tolerance, so any visible fading at all already meets or exceeds it. Compare the visible fading directly against the threshold and do not round it toward "still acceptable" by default.
- When fading is mentioned with a trusted person, first describe the visible fading, then connect it to their preference in the same sentence.
- Do not describe fading without the trusted person's name and fading verdict when a trusted person's name and fade threshold are provided. A response that mentions fading but omits the person's name is incomplete.
- Anchor the verdict explicitly to fading, such as "in terms of fading" or "as far as the fading goes", so it does not sound like a judgement about stains, marks, or the whole garment.
- Use only one of these trusted-person framings:
  - "in terms of fading, [Name] would still consider this fine."
  - "in terms of fading, [Name] might consider this close to borderline."
  - "in terms of fading, [Name] would probably consider this too faded to wear."
- If no trusted person's name is provided, do not mention a trusted person.

If no visible issue is found:
Say exactly: "No visible stains, marks, damage, or fading were detected on this garment. Do you still want to wear it?"

Examples:
Image shows a small brown spot near the lower front hem.
Response: A small brown mark is visible near the lower front hem. Do you still want to wear it?

Image shows a pale shirt with worn color around the collar, trusted person's name is Camille.
Response: Slight fading is visible around the collar and shoulder area, and in terms of fading, Camille would still consider this fine. Do you still want to wear it?

Image shows strong fading across the front of a dark shirt, trusted person's name is Nadhirah.
Response: Noticeable fading is visible across the front center of the shirt, and in terms of fading, Nadhirah would probably consider this too faded to wear. Do you still want to wear it?

Image shows both a stain and fading, trusted person's name is Amen.
Response: A light gray stain is visible near the right sleeve cuff. Fading also appears across the front panel, and in terms of fading, Amen might consider this close to borderline. Do you still want to wear it?

Image is dim and hard to inspect.
Response: Lighting limits detail in this image, but no clear stains, marks, damage, or fading were detected. Do you still want to wear it?

Image shows no visible issue.
Response: No visible stains, marks, damage, or fading were detected on this garment. Do you still want to wear it?

Response format:
- Maximum 3 short sentences.
- End every response with exactly: Do you still want to wear it?
- Do not add anything after that question.
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
            if (caregiverName != null && fadeThreshold != null) {
                append(" If fading is mentioned, the same sentence must include $caregiverName by name and state their fading verdict.")
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
            if (caregiverName != null && fadeThreshold != null) {
                append(" If fading is mentioned, the same sentence must include $caregiverName by name and state their fading verdict.")
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
        return sanitizeModelText(content)
    }

    // Llama-family models occasionally leak raw chat-template control tokens
    // (e.g. "<|start_header_id|>assistant<|end_header_id|>") into the content
    // field, especially on multi-turn requests that replay history. TTS would
    // otherwise read these aloud verbatim — strip them before they reach the user.
    private fun sanitizeModelText(text: String): String =
        text.replace(Regex("<\\|[^|]*\\|>"), "").trim()
}
