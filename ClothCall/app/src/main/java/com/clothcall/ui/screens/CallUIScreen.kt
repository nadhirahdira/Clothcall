package com.clothcall.ui.screens

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.clothcall.telecom.TelecomHelper
import com.clothcall.ui.navigation.Route
import com.clothcall.ui.theme.CallBackground
import com.clothcall.ui.theme.CallSurface
import com.clothcall.ui.theme.PulseColor
import com.clothcall.utils.AudioRouter
import com.clothcall.ui.viewmodels.CallPhase
import com.clothcall.ui.viewmodels.CallViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun CallUIScreen(
    navController: NavController,
    isOutMode: Boolean,
    viewModel: CallViewModel
) {
    val context = LocalContext.current
    val phase by viewModel.phase.collectAsState()
    val listeningKey by viewModel.listeningKey.collectAsState()
    val audioRouter = remember { AudioRouter(context) }
    val scope = rememberCoroutineScope()

    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var stt by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    val ringPlayer = remember { mutableStateOf<MediaPlayer?>(null) }
    val vibrator = remember { mutableStateOf<Vibrator?>(null) }

    // Reset to Ringing every time this screen appears
    LaunchedEffect(Unit) { viewModel.reset() }

    // One-time setup: ringtone, vibration, TTS, STT
    DisposableEffect(Unit) {
        // Ringtone
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val player = try { MediaPlayer.create(context, uri) } catch (_: Exception) { null }
        player?.isLooping = true
        player?.start()
        ringPlayer.value = player

        // Vibration — pattern: wait 0ms, vibrate 800ms, pause 600ms, repeat
        val vib = context.getSystemService(Vibrator::class.java)
        vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
        vibrator.value = vib

        // TTS — use a var so the callback can reference it safely after construction
        var ttsEngine: TextToSpeech? = null
        ttsEngine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = ttsEngine?.setLanguage(Locale.US) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    ttsEngine?.setLanguage(Locale.getDefault())
                }
                tts = ttsEngine
                ttsReady = true
            }
        }

        // STT — check availability before creating
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            stt = SpeechRecognizer.createSpeechRecognizer(context)
        }

        onDispose {
            player?.stop(); player?.release()
            vib?.cancel()
            val engine = ttsEngine
            engine?.stop()
            engine?.shutdown()
            stt?.destroy()
            if (isOutMode) TelecomHelper.endCall()
            audioRouter.resetRouting()
        }
    }

    // Answer: stop ring/vibration, wait 2 s (screenshot window), then transition
    val onAnswer: () -> Unit = {
        ringPlayer.value?.stop(); ringPlayer.value?.release(); ringPlayer.value = null
        vibrator.value?.cancel()
        if (isOutMode) TelecomHelper.answerCall()
        else audioRouter.routeToSpeaker()
        scope.launch {
            delay(2_000)
            viewModel.answer()
        }
    }

    // Decline: stop everything and go home
    val onDecline: () -> Unit = {
        ringPlayer.value?.stop(); ringPlayer.value?.release(); ringPlayer.value = null
        vibrator.value?.cancel()
        if (isOutMode) TelecomHelper.endCall()
        navController.navigate(Route.HOME) { popUpTo(Route.CALL_UI) { inclusive = true } }
    }

    BackHandler(enabled = phase is CallPhase.Ringing) { onDecline() }

    // Start TTS when Speaking and TTS engine is ready
    LaunchedEffect(phase, ttsReady) {
        if (phase is CallPhase.Speaking && ttsReady) {
            val text = viewModel.responseText
            if (text.isNotBlank()) {
                speakText(tts!!, text) { viewModel.onTtsDone() }
            }
        }
    }

    // Listening → open mic; re-fires on listeningKey bump so STT retries after silence/error
    LaunchedEffect(phase, listeningKey) {
        if (phase is CallPhase.Listening) {
            delay(300)
            startListening(stt) { words ->
                if (words.isBlank()) viewModel.retryListening()
                else viewModel.handleVoiceCommand(words)
            }
        }
    }

    // Dismissed / Error → navigate away
    LaunchedEffect(phase) {
        when (phase) {
            is CallPhase.Dismissed -> {
                if ((phase as CallPhase.Dismissed).warm) {
                    tts?.speak("Enjoy your day.", TextToSpeech.QUEUE_FLUSH, Bundle(), "bye")
                }
                if (isOutMode) TelecomHelper.endCall()
                delay(1_800)
                navController.navigate(Route.HOME) { popUpTo(Route.CALL_UI) { inclusive = true } }
            }
            is CallPhase.Error -> {
                delay(3_000)
                navController.popBackStack()
            }
            else -> {}
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(CallBackground),
        contentAlignment = Alignment.Center
    ) {
        when (phase) {
            is CallPhase.Ringing -> RingingScreen(
                caregiverName = viewModel.caregiverName,
                onAnswer = onAnswer,
                onDecline = onDecline
            )
            is CallPhase.FetchingDetail -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(color = PulseColor)
                Text("Getting more detail…", color = Color.White, style = MaterialTheme.typography.bodyLarge)
            }
            is CallPhase.Error -> Text(
                text = (phase as CallPhase.Error).msg,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp)
            )
            else -> InCallScreen(
                caregiverName = viewModel.caregiverName,
                responseText = viewModel.responseText,
                isListening = phase is CallPhase.Listening,
                isSpeaking = phase is CallPhase.Speaking
            )
        }
    }
}

// ── Ringing UI ───────────────────────────────────────────────────────────────

@Composable
private fun RingingScreen(
    caregiverName: String,
    onAnswer: () -> Unit,
    onDecline: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "ring_pulse")
    val ring1 by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1000, easing = EaseOut), RepeatMode.Restart),
        label = "r1"
    )
    val ring2 by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            tween(1000, delayMillis = 400, easing = EaseOut), RepeatMode.Restart
        ),
        label = "r2"
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(Modifier.height(80.dp))

        // Name + subtitle
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = caregiverName,
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White
            )
            Text(
                text = "Incoming call…",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.6f)
            )
        }

        // Avatar with two expanding ring pulses
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(ring1)
                    .background(PulseColor.copy(alpha = (1.6f - ring1) * 0.25f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(ring2)
                    .background(PulseColor.copy(alpha = (1.6f - ring2) * 0.25f), CircleShape)
            )
            Box(
                modifier = Modifier.size(100.dp).background(CallSurface, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = PulseColor,
                    modifier = Modifier.size(52.dp)
                )
            }
        }

        // Decline / Answer buttons
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 64.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CallButton(
                icon = Icons.Filled.CallEnd,
                label = "Decline",
                color = Color(0xFFD32F2F),
                onClick = onDecline
            )
            CallButton(
                icon = Icons.Filled.Call,
                label = "Answer",
                color = Color(0xFF388E3C),
                onClick = onAnswer
            )
        }
    }
}

@Composable
private fun CallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            containerColor = color,
            elevation = FloatingActionButtonDefaults.elevation(8.dp)
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Text(label, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge)
    }
}

// ── In-call UI ───────────────────────────────────────────────────────────────

@Composable
private fun InCallScreen(
    caregiverName: String,
    responseText: String,
    isListening: Boolean,
    isSpeaking: Boolean
) {
    val pulse = rememberInfiniteTransition(label = "call_pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = EaseInOutSine), RepeatMode.Reverse
        ),
        label = "callScale"
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(Modifier.height(24.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(caregiverName, color = Color.White, style = MaterialTheme.typography.headlineLarge)
            Text(
                text = if (isSpeaking) "Speaking…" else if (isListening) "Listening…" else "",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Box(
            modifier = Modifier
                .size(110.dp)
                .scale(if (isSpeaking) scale else 1f)
                .background(CallSurface, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .background(PulseColor.copy(alpha = if (isSpeaking) 0.9f else 0.4f), CircleShape)
            )
        }

        Text(
            text = responseText,
            color = Color.White.copy(alpha = 0.88f),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                imageVector = if (isListening) Icons.Filled.Mic else Icons.Filled.MicOff,
                contentDescription = null,
                tint = if (isListening) PulseColor else Color.Gray,
                modifier = Modifier.size(28.dp)
            )
            if (isListening) {
                Text(
                    text = "Listening for your reply...",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ── TTS / STT helpers ────────────────────────────────────────────────────────

private fun speakText(tts: TextToSpeech, text: String, onDone: () -> Unit) {
    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(id: String?) {}
        override fun onDone(id: String?) { if (id == "cc_call") onDone() }
        @Deprecated("Deprecated in API level 21")
        override fun onError(id: String?) { if (id == "cc_call") onDone() }
        override fun onError(id: String?, errorCode: Int) { if (id == "cc_call") onDone() }
    })
    tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "cc_call")
}

private fun startListening(stt: SpeechRecognizer?, onResult: (String) -> Unit) {
    val recognizer = stt ?: return
    recognizer.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(r: Bundle?) {
            val partial = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (partial.isNotBlank()) onResult(partial)
        }
        override fun onEvent(e: Int, p: Bundle?) {}
        override fun onError(e: Int) {
            android.util.Log.w("ClothCall_STT", "STT error code $e")
            onResult("")
        }
        override fun onResults(results: Bundle?) {
            onResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: "")
        }
    })
    recognizer.startListening(
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }
    )
}
