package com.clothcall.ui.viewmodels

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.clothcall.api.GeminiApiService
import com.clothcall.data.db.CaregiverProfileDao
import com.clothcall.data.db.GarmentDao
import com.clothcall.utils.PreferencesManager
import com.clothcall.utils.ScanResultHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

sealed class ScanState {
    object Idle : ScanState()
    object Loading : ScanState()
    object Done : ScanState()
    data class Error(val message: String) : ScanState()
}

class ScanViewModel(
    private val apiService: GeminiApiService,
    private val caregiverDao: CaregiverProfileDao,
    private val garmentDao: GarmentDao,
    private val prefs: PreferencesManager
) : ViewModel() {

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state

    fun analyze(bitmap: Bitmap) {
        viewModelScope.launch {
            _state.value = ScanState.Loading
            ScanResultHolder.reset()

            val base64 = bitmapToBase64(bitmap)
            ScanResultHolder.base64Image = base64

            // Prefer the explicitly active profile; fall back to the most recently added one
            // so the "Trusted:" bar on the HomeScreen is always honoured even if the DB flag
            // hasn't been written yet (e.g. first launch before any explicit selection).
            val profile = caregiverDao.getActiveProfile() ?: caregiverDao.getFirstProfile()
            ScanResultHolder.caregiverName = profile?.name
            ScanResultHolder.fadeThreshold = profile?.fadeThreshold

            val baselineBase64 = loadBaselineBase64()
            ScanResultHolder.baselineBase64 = baselineBase64

            val result = apiService.analyzeClothing(
                apiKey = prefs.apiKey,
                base64Image = base64,
                baselineBase64 = baselineBase64,
                caregiverName = profile?.name,
                fadeThreshold = profile?.fadeThreshold
            )

            result.onSuccess { text ->
                ScanResultHolder.response = text
                ScanResultHolder.conversationHistory.clear()
                ScanResultHolder.conversationHistory.add("assistant" to text)
                _state.value = ScanState.Done
            }.onFailure { e ->
                _state.value = ScanState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun resetState() { _state.value = ScanState.Idle }

    suspend fun classifyAlignment(base64Frame: String): Result<String> =
        apiService.classifyAlignment(prefs.apiKey, base64Frame)

    private suspend fun loadBaselineBase64(): String? {
        val id = prefs.selectedGarmentId
        if (id < 0) return null
        val garment = garmentDao.getGarmentById(id) ?: return null
        val path = garment.baselinePath ?: return null
        val file = File(path)
        if (!file.exists()) return null
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return bitmapToBase64(bmp)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val scaled = scaleBitmap(bitmap, maxDim = 1024)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun scaleBitmap(src: Bitmap, maxDim: Int): Bitmap {
        val ratio = maxDim.toFloat() / maxOf(src.width, src.height)
        if (ratio >= 1f) return src
        return Bitmap.createScaledBitmap(
            src,
            (src.width * ratio).toInt(),
            (src.height * ratio).toInt(),
            true
        )
    }

    companion object {
        fun factory(
            apiService: GeminiApiService,
            caregiverDao: CaregiverProfileDao,
            garmentDao: GarmentDao,
            prefs: PreferencesManager
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ScanViewModel(apiService, caregiverDao, garmentDao, prefs) }
        }
    }
}
