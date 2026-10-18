package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "uploaded_files")
data class UploadedFileEntity(
    @PrimaryKey
    val fileName: String,
    val uploadTimestamp: Long = System.currentTimeMillis()
)
