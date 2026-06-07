package com.clothcall.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.clothcall.data.db.CaregiverProfile
import com.clothcall.data.db.CaregiverProfileDao
import com.clothcall.data.db.Garment
import com.clothcall.data.db.GarmentDao
import com.clothcall.utils.PreferencesManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val caregiverDao: CaregiverProfileDao,
    private val garmentDao: GarmentDao,
    private val prefs: PreferencesManager
) : ViewModel() {

    val profiles: StateFlow<List<CaregiverProfile>> = caregiverDao.getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val garments: StateFlow<List<Garment>> = garmentDao.getAllGarments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var isOutMode: Boolean
        get() = prefs.isOutMode
        set(value) { prefs.isOutMode = value }

    var selectedGarmentId: Int
        get() = prefs.selectedGarmentId
        set(value) { prefs.selectedGarmentId = value }

    var hasSpokenWelcome: Boolean
        get() = prefs.hasSpokenWelcome
        set(value) { prefs.hasSpokenWelcome = value }

    fun setActiveProfile(id: Int) {
        viewModelScope.launch {
            caregiverDao.clearActiveProfile()
            caregiverDao.setActiveProfile(id)
        }
    }

    suspend fun getActiveProfile(): CaregiverProfile? = caregiverDao.getActiveProfile()

    companion object {
        fun factory(
            caregiverDao: CaregiverProfileDao,
            garmentDao: GarmentDao,
            prefs: PreferencesManager
        ): ViewModelProvider.Factory =
            viewModelFactory { initializer { HomeViewModel(caregiverDao, garmentDao, prefs) } }
    }
}
