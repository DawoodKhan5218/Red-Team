package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UploadedFileDao {
    @Query("SELECT COUNT(*) FROM uploaded_files WHERE fileName = :fileName")
    suspend fun isFileUploaded(fileName: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUploadedFile(file: UploadedFileEntity)
}
