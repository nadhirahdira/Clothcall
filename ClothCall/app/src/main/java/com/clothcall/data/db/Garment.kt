package com.clothcall.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "garments")
data class Garment(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val imagePath: String,
    val baselinePath: String? = null,
    val dateAdded: Long = System.currentTimeMillis()
)
