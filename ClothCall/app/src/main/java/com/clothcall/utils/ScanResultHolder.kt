package com.clothcall.utils

/**
 * In-memory bridge between QuickScanScreen and CallUIScreen.
 * Lives only for the duration of one scan session.
 */
object ScanResultHolder {
    var response: String = ""
    var caregiverName: String? = null
    var fadeThreshold: Int? = null

    val reportedStains: MutableSet<String> = mutableSetOf()

    // Accumulates messages for multi-turn follow-up requests (role → content)
    val conversationHistory: MutableList<Pair<String, String>> = mutableListOf()

    // Base64 images kept for multi-turn context
    var base64Image: String = ""
    var baselineBase64: String? = null

    fun reset() {
        response = ""
        caregiverName = null
        fadeThreshold = null
        reportedStains.clear()
        conversationHistory.clear()
        base64Image = ""
        baselineBase64 = null
    }
}
