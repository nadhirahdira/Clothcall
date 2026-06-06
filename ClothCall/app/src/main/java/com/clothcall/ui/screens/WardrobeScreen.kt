package com.clothcall.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.camera.core.CameraSelector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.clothcall.data.db.Garment
import com.clothcall.ui.viewmodels.WardrobeViewModel
import java.io.File
import java.util.Locale

private enum class WardrobeStep { LIST, CAMERA, NAME, EDIT }

@Composable
fun WardrobeScreen(navController: NavController, viewModel: WardrobeViewModel) {
    val garments by viewModel.garments.collectAsState()
    var step by remember { mutableStateOf(WardrobeStep.LIST) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var editingGarment by remember { mutableStateOf<Garment?>(null) }
    var editBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var editName by remember { mutableStateOf("") }

    when (step) {
        WardrobeStep.LIST -> GarmentList(
            garments = garments,
            onAdd = { step = WardrobeStep.CAMERA },
            onDelete = { viewModel.deleteGarment(it) },
            onEdit = { garment ->
                editingGarment = garment
                editBitmap = null
                editName = garment.name
                step = WardrobeStep.EDIT
            },
            onBack = { navController.popBackStack() }
        )
        WardrobeStep.CAMERA -> WardrobeCameraScreen(
            onCapture = { bitmap ->
                if (editingGarment == null) {
                    capturedBitmap = bitmap
                    step = WardrobeStep.NAME
                } else {
                    editBitmap = bitmap
                    step = WardrobeStep.EDIT
                }
            },
            onBack = { step = WardrobeStep.LIST }
        )
        WardrobeStep.NAME -> capturedBitmap?.let { bmp ->
            NameGarmentScreen(
                bitmap = bmp,
                onSave = { name ->
                    viewModel.addGarment(navController.context, name, bmp)
                    capturedBitmap = null
                    step = WardrobeStep.LIST
                },
                onBack = { step = WardrobeStep.CAMERA }
            )
        }
        WardrobeStep.EDIT -> editingGarment?.let { garment ->
            EditGarmentScreen(
                garment = garment,
                editedName = editName,
                editedBitmap = editBitmap,
                onNameChange = { editName = it },
                onRetake = { step = WardrobeStep.CAMERA },
                onSave = { name, bitmap ->
                    viewModel.updateGarment(navController.context, garment, name, bitmap)
                    editingGarment = null
                    editBitmap = null
                    editName = ""
                    step = WardrobeStep.LIST
                },
                onBack = {
                    editingGarment = null
                    editBitmap = null
                    editName = ""
                    step = WardrobeStep.LIST
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GarmentList(
    garments: List<Garment>,
    onAdd: () -> Unit,
    onDelete: (Garment) -> Unit,
    onEdit: (Garment) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wardrobe") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, "Add garment") }
                }
            )
        }
    ) { padding ->
        if (garments.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Checkroom, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Text("Your wardrobe is empty.\nTap + to add a garment.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(garments, key = { it.id }) { garment ->
                    GarmentCard(
                        garment = garment,
                        onEdit = { onEdit(garment) },
                        onDelete = { onDelete(garment) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GarmentCard(garment: Garment, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val file = File(garment.imagePath)
            if (file.exists()) {
                Image(
                    painter = rememberAsyncImagePainter(file),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Checkroom, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(garment.name, style = MaterialTheme.typography.bodyLarge)
                Text("Tap edit to rename or retake the cloth photo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }

            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, "Edit", tint = MaterialTheme.colorScheme.primary)
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun WardrobeCameraScreen(onCapture: (Bitmap) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_FRONT) }

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
                    cameraProviderFuture.addListener({
                        val provider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                        } catch (e: Exception) { Log.e("Wardrobe", "Camera error", e) }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                }
            )
        }

        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = androidx.compose.ui.graphics.Color.White)
        }

        IconButton(
            onClick = {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT)
                    CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        ) {
            Icon(Icons.Filled.FlipCameraAndroid, "Flip camera",
                tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(32.dp))
        }

        FloatingActionButton(
            onClick = {
                imageCapture.takePicture(ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            onCapture(imageProxyToBitmap(image))
                            image.close()
                        }
                        override fun onError(e: ImageCaptureException) { Log.e("Wardrobe", "Capture error", e) }
                    })
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).size(72.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Filled.Camera, "Capture", modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
private fun NameGarmentScreen(bitmap: Bitmap, onSave: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    val stt = remember { SpeechRecognizer.createSpeechRecognizer(context) }

    DisposableEffect(Unit) { onDispose { stt.destroy() } }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }

        Text("Name this garment", style = MaterialTheme.typography.headlineMedium)

        Image(
            painter = rememberAsyncImagePainter(bitmap),
            contentDescription = null,
            modifier = Modifier.size(160.dp).clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Garment name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = {
                    isListening = true
                    startSttForName(stt) { result ->
                        if (result.isNotBlank()) name = result
                        isListening = false
                    }
                }) {
                    Icon(
                        if (isListening) Icons.Filled.Mic else Icons.Filled.MicNone,
                        contentDescription = "Speak name"
                    )
                }
            }
        )

        Button(
            onClick = { if (name.isNotBlank()) onSave(name.trim()) },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("Save to wardrobe")
        }
    }
}

@Composable
private fun EditGarmentScreen(
    garment: Garment,
    editedName: String,
    editedBitmap: Bitmap?,
    onNameChange: (String) -> Unit,
    onRetake: () -> Unit,
    onSave: (String, Bitmap?) -> Unit,
    onBack: () -> Unit
) {
    val imageModifier = Modifier
        .fillMaxWidth()
        .height(220.dp)
        .clip(RoundedCornerShape(16.dp))
        .clickable { onRetake() }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }

        Text("Edit garment", style = MaterialTheme.typography.headlineMedium)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(modifier = imageModifier, contentAlignment = Alignment.BottomCenter) {
                    if (editedBitmap != null) {
                        Image(
                            bitmap = editedBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        val file = File(garment.imagePath)
                        Image(
                            painter = rememberAsyncImagePainter(file),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Surface(
                        color = Color.Black.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Tap cloth to retake photo",
                            color = Color.White,
                            modifier = Modifier.padding(10.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                OutlinedTextField(
                    value = editedName,
                    onValueChange = onNameChange,
                    label = { Text("Garment name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Rename or retake this cloth.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onRetake) {
                        Icon(Icons.Filled.Edit, "Retake photo", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Button(
                    onClick = { onSave(editedName.trim(), editedBitmap) },
                    enabled = editedName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("Save changes")
                }
            }
        }
    }
}

private fun startSttForName(stt: SpeechRecognizer, onResult: (String) -> Unit) {
    stt.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(r: Bundle?) {}
        override fun onEvent(e: Int, p: Bundle?) {}
        override fun onError(e: Int) { onResult("") }
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            onResult(matches?.firstOrNull() ?: "")
        }
    })
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    stt.startListening(intent)
}
