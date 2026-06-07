package com.clothcall.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.FlipCameraAndroid
import com.clothcall.telecom.TelecomHelper
import com.clothcall.ui.navigation.Route
import com.clothcall.ui.viewmodels.ScanState
import com.clothcall.ui.viewmodels.ScanViewModel
import com.clothcall.utils.ScanResultHolder
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Locale

private const val GUIDANCE_INTERVAL_MS = 3_000L
private const val STARTUP_GUIDANCE_DELAY_MS = 4_500L
private const val AUTO_CAPTURE_HOLD_MS = 1_500L
private const val RATE_LIMIT_COOLDOWN_MS = 60_000L
private const val ERROR_COOLDOWN_MS = 8_000L
private const val GUIDANCE_FRAME_WIDTH = 512
private const val GUIDANCE_FRAME_HEIGHT = 384
private const val GUIDANCE_FRAME_QUALITY = 65
private const val DARK_BRIGHTNESS_THRESHOLD = 30
private const val GOOD_GUIDANCE = "Good"
private const val DARK_GUIDANCE = "Too dark"

/**
 * Shared bus so MainActivity can fire the camera capture when the user
 * presses the volume-down button. CameraCapture registers its lambda on
 * entry and clears it on dispose, so outside the camera screen volume-down
 * works normally.
 */
internal object VolumeCaptureBus {
    @Volatile var trigger: (() -> Unit)? = null
}

/**
 * Tracks whether the one-time "point your camera at your clothing" startup
 * instruction has already been spoken this app session — CameraCapture's TTS
 * init effect can re-run (e.g. after retrying from an error screen), and
 * without this guard the instruction would play again each time.
 */
private object StartupInstructionState {
    @Volatile var spoken = false
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun QuickScanScreen(
    navController: NavController,
    viewModel: ScanViewModel,
    isOutMode: Boolean = false
) {
    val permissions = rememberMultiplePermissionsState(
        listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
    )
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        if (!permissions.allPermissionsGranted) permissions.launchMultiplePermissionRequest()
    }

    val context = LocalContext.current
    LaunchedEffect(state) {
        if (state is ScanState.Done) {
            viewModel.resetState()
            if (isOutMode) {
                TelecomHelper.startIncomingCall(context, ScanResultHolder.caregiverName ?: "ClothCall")
            }
            navController.navigate(Route.CALL_UI) {
                popUpTo(Route.QUICK_SCAN) { inclusive = true }
            }
        }
    }

    when {
        state is ScanState.Loading -> LoadingOverlay()
        state is ScanState.Error -> ErrorOverlay(
            message = (state as ScanState.Error).message,
            onRetry = { viewModel.resetState() },
            onBack = { navController.popBackStack() }
        )
        !permissions.allPermissionsGranted -> PermissionDeniedScreen {
            permissions.launchMultiplePermissionRequest()
        }
        else -> CameraCapture(
            viewModel = viewModel,
            onCapture = { bitmap -> viewModel.analyze(bitmap) }
        )
    }
}

@Composable
private fun CameraCapture(
    viewModel: ScanViewModel,
    onCapture: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val scope = rememberCoroutineScope()

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_FRONT) }

    var guidanceTts by remember { mutableStateOf<TextToSpeech?>(null) }
    var guidanceReady by remember { mutableStateOf(false) }

    // Alignment guidance state — persists across lens flips
    var lastGuidance by remember { mutableStateOf<String?>(null) }
    var captureEnabled by remember { mutableStateOf(false) }
    var captureInProgress by remember { mutableStateOf(false) }
    var analysisInFlight by remember { mutableStateOf(false) }
    var lastAnalysisMs by remember { mutableLongStateOf(0L) }
    var guidanceReadyAtMs by remember { mutableLongStateOf(0L) }
    var guidanceCooldownUntilMs by remember { mutableLongStateOf(0L) }

    // Pause guidance analysis when the app is backgrounded or the screen is off
    var isResumed by remember { mutableStateOf(true) }
    var isScreenOn by remember { mutableStateOf(true) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    val imageAnalyzer = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }

    // Manual capture (FAB tap or volume-down) is a deliberate user override —
    // it always fires regardless of alignment guidance. captureEnabled only
    // gates the automatic capture triggered when guidance reports "Good".
    val performCapture: () -> Unit = capture@{
        if (captureInProgress) return@capture
        captureInProgress = true
        guidanceTts?.speak(
            "Got it",
            TextToSpeech.QUEUE_FLUSH, Bundle(), "cam_capture"
        )
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    onCapture(imageProxyToBitmap(image))
                    image.close()
                }
                override fun onError(e: ImageCaptureException) {
                    Log.e("QuickScan", "Capture error", e)
                    captureInProgress = false
                }
            }
        )
    }

    // Register volume-down capture trigger — cleared automatically when screen leaves composition
    DisposableEffect(imageCapture) {
        VolumeCaptureBus.trigger = { performCapture() }
        onDispose { VolumeCaptureBus.trigger = null }
    }

    // Track foreground/background so guidance analysis pauses when the app isn't visible
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true
                Lifecycle.Event.ON_PAUSE -> isResumed = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Track screen on/off so guidance analysis pauses while the display is asleep
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> isScreenOn = false
                    Intent.ACTION_SCREEN_ON -> isScreenOn = true
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.setLanguage(Locale.US)
                guidanceTts = engine
                if (!StartupInstructionState.spoken) {
                    StartupInstructionState.spoken = true
                    engine?.speak(
                        "Point your camera at your clothing. I will guide you and take the photo automatically.",
                        TextToSpeech.QUEUE_FLUSH, Bundle(), "cam_init"
                    )
                }
                guidanceReadyAtMs = System.currentTimeMillis()
                guidanceReady = true
            }
        }
        onDispose {
            imageAnalyzer.clearAnalyzer()
            engine.stop()
            engine.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        key(lensFacing) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                    val selector = if (lensFacing == CameraSelector.LENS_FACING_FRONT)
                        CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

                    imageAnalyzer.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                        val now = System.currentTimeMillis()
                        val canAnalyze = guidanceReady && isResumed && isScreenOn &&
                            !captureInProgress && !analysisInFlight &&
                            now - guidanceReadyAtMs >= STARTUP_GUIDANCE_DELAY_MS &&
                            now >= guidanceCooldownUntilMs &&
                            now - lastAnalysisMs >= GUIDANCE_INTERVAL_MS

                        if (!canAnalyze) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        // Cheap local brightness check first — skips the API call entirely
                        // for frames that are clearly too dark to assess.
                        val brightness = averageBrightness(imageProxy)
                        if (brightness < DARK_BRIGHTNESS_THRESHOLD) {
                            imageProxy.close()
                            lastAnalysisMs = now
                            if (lastGuidance != DARK_GUIDANCE) {
                                lastGuidance = DARK_GUIDANCE
                                captureEnabled = false
                                guidanceTts?.speak(
                                    "Too dark, move to a brighter area",
                                    TextToSpeech.QUEUE_FLUSH, Bundle(), "cam_dark"
                                )
                            }
                            return@setAnalyzer
                        }

                        val base64Frame = frameToGuidanceBase64(imageProxy)
                        if (base64Frame == null) return@setAnalyzer
                        lastAnalysisMs = now
                        analysisInFlight = true
                        scope.launch {
                            val result = viewModel.classifyAlignment(base64Frame)
                            analysisInFlight = false
                            result.onFailure { e ->
                                // Back off so a rate-limited or failing API doesn't get hammered
                                // every GUIDANCE_INTERVAL_MS — rate-limit errors get a much longer pause.
                                val msg = e.message?.lowercase().orEmpty()
                                val isRateLimited = "rate limit" in msg || "429" in msg
                                guidanceCooldownUntilMs = System.currentTimeMillis() +
                                    if (isRateLimited) RATE_LIMIT_COOLDOWN_MS else ERROR_COOLDOWN_MS
                            }
                            val guidance = result.getOrNull()?.trim()
                            if (guidance != null && guidance != lastGuidance) {
                                lastGuidance = guidance
                                captureEnabled = guidance == GOOD_GUIDANCE
                                val spoken = when (guidance) {
                                    GOOD_GUIDANCE -> "Good, hold still"
                                    "Move closer" -> "Move the camera a bit closer"
                                    "Move back" -> "Move the camera back a little"
                                    "Recenter" -> "Try holding the camera so your clothing fills more of the view"
                                    "No clothing found" -> "I can't see any clothing — point the camera at what you're wearing"
                                    else -> guidance
                                }
                                guidanceTts?.speak(
                                    spoken, TextToSpeech.QUEUE_FLUSH, Bundle(), "cam_guidance"
                                )
                                if (guidance == GOOD_GUIDANCE) {
                                    // Auto-capture after a short hold-still window — re-checks
                                    // that alignment is still "Good" so a brief flicker into
                                    // frame doesn't trigger a snapshot of a bad pose.
                                    scope.launch {
                                        delay(AUTO_CAPTURE_HOLD_MS)
                                        if (lastGuidance == GOOD_GUIDANCE && !captureInProgress) {
                                            performCapture()
                                        }
                                    }
                                }
                            }
                        }
                    }

                    cameraProviderFuture.addListener({
                        val provider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner, selector, preview, imageCapture, imageAnalyzer
                            )
                        } catch (e: Exception) {
                            Log.e("QuickScan", "Camera bind failed", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                }
            )
        }

        // Flip button — top right
        IconButton(
            onClick = {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT)
                    CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(
                Icons.Filled.FlipCameraAndroid,
                contentDescription = "Flip camera",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }

        // Capture button — bottom centre. Disabled (dimmed) until guidance reports "Good"
        // so the user cannot accidentally capture a badly-aligned frame.
        FloatingActionButton(
            onClick = performCapture,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .size(72.dp),
            containerColor = if (captureEnabled) MaterialTheme.colorScheme.primary
                             else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Icon(
                Icons.Filled.Camera,
                contentDescription = "Capture",
                tint = if (captureEnabled) Color.White else Color.Gray,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

/**
 * Samples a 20×20 grid of luminance (Y-plane) values and returns the average
 * brightness (0–255). Used as a cheap pre-check before the alignment API call —
 * frames below [DARK_BRIGHTNESS_THRESHOLD] are reported as "Too dark" locally.
 * Does not close [imageProxy] — the caller decides when the frame is done with.
 */
private fun averageBrightness(imageProxy: ImageProxy): Int {
    val plane = imageProxy.planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val width = imageProxy.width
    val height = imageProxy.height

    var sum = 0L
    var count = 0
    for (row in 0 until 20) {
        val y = height * row / 20
        for (col in 0 until 20) {
            val x = width * col / 20
            val pos = y * rowStride + x * pixelStride
            if (pos < buffer.limit()) {
                sum += buffer.get(pos).toInt() and 0xFF
                count++
            }
        }
    }
    return if (count == 0) 255 else (sum / count).toInt()
}

/**
 * Converts a YUV_420_888 analysis frame into a small JPEG base64 string
 * ([GUIDANCE_FRAME_WIDTH]x[GUIDANCE_FRAME_HEIGHT], quality [GUIDANCE_FRAME_QUALITY])
 * suitable for a low-latency alignment-guidance API call.
 * Closes [imageProxy] once encoding is complete (success or failure).
 */
private fun frameToGuidanceBase64(imageProxy: ImageProxy): String? {
    return try {
        val width = imageProxy.width
        val height = imageProxy.height
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val jpegOut = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, jpegOut)
        val jpegBytes = jpegOut.toByteArray()

        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
        val rotation = imageProxy.imageInfo.rotationDegrees
        val rotated = if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } else decoded
        val scaled = Bitmap.createScaledBitmap(rotated, GUIDANCE_FRAME_WIDTH, GUIDANCE_FRAME_HEIGHT, true)

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, GUIDANCE_FRAME_QUALITY, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.e("QuickScan", "Frame encode failed for guidance", e)
        null
    } finally {
        imageProxy.close()
    }
}

internal fun imageProxyToBitmap(image: ImageProxy): Bitmap {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
