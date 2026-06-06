package com.clothcall.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CaregiverProfileDao {

    @Query("SELECT * FROM caregiver_profiles ORDER BY id DESC")
    fun getAllProfiles(): Flow<List<CaregiverProfile>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: CaregiverProfile): Long

    @Update
    suspend fun updateProfile(profile: CaregiverProfile)

    @Delete
    suspend fun deleteProfile(profile: CaregiverProfile)

    @Query("SELECT * FROM caregiver_profiles WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveProfile(): CaregiverProfile?

    @Query("SELECT * FROM caregiver_profiles ORDER BY id DESC LIMIT 1")
    suspend fun getFirstProfile(): CaregiverProfile?

    @Query("UPDATE caregiver_profiles SET isActive = 0")
    suspend fun clearActiveProfile()

    @Query("UPDATE caregiver_profiles SET isActive = 1 WHERE id = :id")
    suspend fun setActiveProfile(id: Int)

    @Query("SELECT COUNT(*) FROM caregiver_profiles")
    suspend fun count(): Int
}
