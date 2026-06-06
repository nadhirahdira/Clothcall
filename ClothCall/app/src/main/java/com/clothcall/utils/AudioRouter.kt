package com.clothcall.utils

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

class AudioRouter(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    suspend fun initTts(): Boolean = suspendCancellableCoroutine { cont ->
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.US
            cont.resume(ttsReady)
        }
        cont.invokeOnCancellation { tts?.shutdown() }
    }

    @SuppressLint("MissingPermission")
    fun routeToEarpiece() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            if (device != null && audioManager.setCommunicationDevice(device)) {
                return
            }
        }
        @Suppress("DEPRECATION")
        run {
            audioManager.mode = AudioManager.MODE_IN_CALL
            audioManager.isSpeakerphoneOn = false
        }
    }

    fun routeToSpeaker() {
        // Clear any active communication device (set by Out mode or previous session)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        @Suppress("DEPRECATION")
        run {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        }
        // In MODE_NORMAL, TTS (STREAM_MUSIC) routes through the external speaker naturally.
        // setCommunicationDevice(BUILTIN_SPEAKER) is a VoIP API and would force
        // MODE_IN_COMMUNICATION, incorrectly routing audio as if it were a phone call.
    }

    fun resetRouting() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        @Suppress("DEPRECATION")
        run {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        }
    }

    suspend fun speak(text: String, id: String = "cc_utt"): Boolean =
        suspendCancellableCoroutine { cont ->
            val engine = tts
            if (!ttsReady || engine == null) { cont.resume(false); return@suspendCancellableCoroutine }

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id) cont.resume(true)
                }
                @Deprecated("Deprecated in API level 21")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id) cont.resume(false)
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == id) cont.resume(false)
                }
            })

            engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            cont.invokeOnCancellation { engine.stop() }
        }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        resetRouting()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}
