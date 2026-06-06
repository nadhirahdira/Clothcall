package com.clothcall.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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
import java.util.Locale

private enum class FrameQuality { UNKNOWN, GOOD, POOR }

/**
 * Shared bus so MainActivity can fire the camera capture when the user
 * presses the volume-down button. CameraCapture registers its lambda on
 * entry and clears it on dispose, so outside the camera screen volume-down
 * works normally.
 */
internal object VolumeCaptureBus {
    @Volatile var trigger: (() -> Unit)? = null
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
            onCapture = { bitmap -> viewModel.analyze(bitmap) }
        )
    }
}

@Composable
private fun CameraCapture(
    onCapture: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_FRONT) }

    // Frame quality state — persists across lens flips
    var frameQuality by remember { mutableStateOf(FrameQuality.UNKNOWN) }
    var lastSpeakMs by remember { mutableLongStateOf(0L) }
    var guidanceTts by remember { mutableStateOf<TextToSpeech?>(null) }
    var guidanceReady by remember { mutableStateOf(false) }
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

    // Register volume-down capture trigger — cleared automatically when screen leaves composition
    DisposableEffect(imageCapture) {
        VolumeCaptureBus.trigger = {
            imageCapture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        onCapture(imageProxyToBitmap(image))
                        image.close()
                    }
                    override fun onError(e: ImageCaptureException) {
                        Log.e("QuickScan", "Volume-down capture error", e)
                    }
                }
            )
        }
        onDispose { VolumeCaptureBus.trigger = null }
    }

    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.setLanguage(Locale.US)
                guidanceTts = engine
                // Short one-time startup instruction
                engine?.speak(
                    "Point your camera at your clothing, then tap the volume down button.",
                    TextToSpeech.QUEUE_FLUSH, Bundle(), "cam_init"
                )
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
                        if (!guidanceReady) return@setAnalyzer
                        val tts = guidanceTts ?: return@setAnalyzer
                        // Hysteresis: commit to GOOD only at >=600, POOR only at <400.
                        // The 400-599 zone keeps the current state to prevent rapid oscillation.
                        val quality = measureFrameQuality(imageProxy, frameQuality)
                        val now = System.currentTimeMillis()

                        if (quality != frameQuality) {
                            // State transition — speak immediately, no time gate
                            val prev = frameQuality
                            frameQuality = quality
                            lastSpeakMs = now
                            when {
                                // UNKNOWN→anything on first frame: silent (startup message covered it)
                                prev == FrameQuality.UNKNOWN -> {}
                                quality == FrameQuality.GOOD ->
                                    tts.speak(
                                        "Clothing is in frame. Tap the volume down button to take the photo.",
                                        TextToSpeech.QUEUE_FLUSH, Bundle(), "cam_good"
                                    )
                                quality == FrameQuality.POOR ->
                                    tts.speak(
                                        "Camera moved away. Point it at your clothing.",
                                        TextToSpeech.QUEUE_FLUSH, Bundle(), "cam_poor"
                                    )
                            }
                        } else if (quality != FrameQuality.UNKNOWN && now - lastSpeakMs > 8_000L) {
                            // Periodic reminder so the user knows it is still watching
                            lastSpeakMs = now
                            when (quality) {
                                FrameQuality.GOOD ->
                                    tts.speak(
                                        "Clothing still in frame. Tap the volume down button to take the photo.",
                                        TextToSpeech.QUEUE_FLUSH, Bundle(), "cam_remind_good"
                                    )
                                FrameQuality.POOR ->
                                    tts.speak(
                                        "Point the camera at your clothing.",
                                        TextToSpeech.QUEUE_FLUSH, Bundle(), "cam_remind_poor"
                                    )
                                else -> {}
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
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(32.dp)
            )
        }

        // Capture button — bottom centre
        FloatingActionButton(
            onClick = {
                imageCapture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            onCapture(imageProxyToBitmap(image))
                            image.close()
                        }
                        override fun onError(e: ImageCaptureException) {
                            Log.e("QuickScan", "Capture error", e)
                        }
                    }
                )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .size(72.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Filled.Camera, contentDescription = "Capture", modifier = Modifier.size(36.dp))
        }
    }
}

/**
 * Samples a 20×20 grid of luminance (Y-plane) values and classifies frame quality.
 * Hysteresis prevents oscillation: >=600 commits to GOOD, <400 commits to POOR,
 * and the 400–599 zone holds the [current] state.
 */
private fun measureFrameQuality(imageProxy: ImageProxy, current: FrameQuality): FrameQuality {
    return try {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = imageProxy.width
        val height = imageProxy.height

        var sum = 0L
        var sumSq = 0L
        var count = 0
        for (row in 0 until 20) {
            val y = height * row / 20
            for (col in 0 until 20) {
                val x = width * col / 20
                val pos = y * rowStride + x * pixelStride
                if (pos < buffer.limit()) {
                    val luma = buffer.get(pos).toInt() and 0xFF
                    sum += luma
                    sumSq += luma.toLong() * luma
                    count++
                }
            }
        }
        if (count == 0) return FrameQuality.UNKNOWN
        val mean = sum.toFloat() / count
        val variance = sumSq.toFloat() / count - mean * mean
        when {
            variance >= 600f -> FrameQuality.GOOD
            variance < 400f  -> FrameQuality.POOR
            else             -> current  // hysteresis zone — hold current state
        }
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
