package com.clothcall.ui.viewmodels

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.clothcall.data.db.Garment
import com.clothcall.data.db.GarmentDao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class WardrobeViewModel(private val garmentDao: GarmentDao) : ViewModel() {

    val garments: StateFlow<List<Garment>> = garmentDao.getAllGarments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addGarment(context: Context, name: String, bitmap: Bitmap) {
        viewModelScope.launch {
            val garment = Garment(name = name, imagePath = "", baselinePath = null)
            val garmentId = garmentDao.insertGarment(garment).toInt()
            val file = writeGarmentBitmap(context, garmentId, bitmap)
            garmentDao.updateGarmentPaths(garmentId, file.absolutePath, file.absolutePath)
        }
    }

    fun updateGarment(context: Context, garment: Garment, name: String, bitmap: Bitmap?) {
        viewModelScope.launch {
            if (bitmap == null) {
                garmentDao.updateGarment(garment.copy(name = name.trim()))
            } else {
                val file = writeGarmentBitmap(context, garment.id, bitmap)
                garmentDao.updateGarment(
                    garment.copy(
                        name = name.trim(),
                        imagePath = file.absolutePath,
                        baselinePath = file.absolutePath
                    )
                )
            }
        }
    }

    fun deleteGarment(garment: Garment) {
        viewModelScope.launch {
            File(garment.imagePath).delete()
            garment.baselinePath?.takeIf { it != garment.imagePath }?.let { File(it).delete() }
            garmentDao.deleteGarment(garment)
        }
    }

    private fun writeGarmentBitmap(context: Context, garmentId: Int, bitmap: Bitmap): File {
        val garmentDir = File(context.filesDir, "garments").apply { mkdirs() }
        val file = File(garmentDir, "$garmentId.jpg")
        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
        }
        return file
    }

    companion object {
        fun factory(dao: GarmentDao): ViewModelProvider.Factory =
            viewModelFactory { initializer { WardrobeViewModel(dao) } }
    }
}
