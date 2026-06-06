package com.clothcall.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.clothcall.data.db.CaregiverProfile
import com.clothcall.data.db.CaregiverProfileDao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CaregiverViewModel(private val dao: CaregiverProfileDao) : ViewModel() {

    val profiles: StateFlow<List<CaregiverProfile>> = dao.getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveProfile(name: String, threshold: Int) {
        viewModelScope.launch {
            dao.insertProfile(CaregiverProfile(name = name, fadeThreshold = threshold))
        }
    }

    fun updateProfile(profile: CaregiverProfile) {
        viewModelScope.launch {
            dao.updateProfile(profile)
        }
    }

    fun deleteProfile(profile: CaregiverProfile) {
        viewModelScope.launch { dao.deleteProfile(profile) }
    }

    companion object {
        fun factory(dao: CaregiverProfileDao): ViewModelProvider.Factory =
            viewModelFactory { initializer { CaregiverViewModel(dao) } }
    }
}
