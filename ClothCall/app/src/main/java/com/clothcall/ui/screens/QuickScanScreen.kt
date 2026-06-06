package com.clothcall.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.clothcall.data.db.Garment
import com.clothcall.telecom.TelecomHelper
import com.clothcall.ui.navigation.Route
import com.clothcall.ui.viewmodels.ScanState
import com.clothcall.ui.viewmodels.ScanViewModel
import com.clothcall.utils.PreferencesManager
import com.clothcall.utils.ScanResultHolder
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState

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
                // Always "ClothCall" as system caller ID — avoids confusion with the
                // trusted person's real number appearing on the lock screen
                TelecomHelper.startIncomingCall(context, "ClothCall")
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
            onCapture = { bitmap -> viewModel.analyze(bitmap) },
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

    // Default to front camera so the user sees themselves
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_FRONT) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // key(lensFacing) destroys and recreates the AndroidView on flip,
        // avoiding duplicate addListener calls that stacked up in update{}
        key(lensFacing) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                    val selector = if (lensFacing == CameraSelector.LENS_FACING_FRONT)
                        CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProviderFuture.addListener({
                        val provider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
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
                    CameraSelector.LENS_FACING_BACK
                else
                    CameraSelector.LENS_FACING_FRONT
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

internal fun imageProxyToBitmap(image: ImageProxy): Bitmap {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
