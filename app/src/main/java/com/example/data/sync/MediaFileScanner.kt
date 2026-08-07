package com.example.data.sync

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MediaFileScanner {

    private const val TAG = "MediaFileScanner"

    private val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "tiff")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "avi", "mkv", "3gp", "m4v", "webm", "flv")
    private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "aac", "flac", "m4a", "ogg")
    private val DOC_EXTENSIONS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf")
    private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz")
    private val APK_EXTENSIONS = setOf("apk")

    private val EXCLUDED_EXTENSIONS = setOf("db", "sqlite", "wal", "shm")
    private val EXCLUDED_PATH_KEYWORDS = listOf("/data/", "/system/", "/cache/", "/tmp/", "/android/data/", "/android/obb/")

    suspend fun scanMediaFiles(context: Context, filterType: String = "all"): List<File> = withContext(Dispatchers.IO) {
        val resultList = mutableListOf<File>()
        try {
            val rootDirectories = mutableListOf<File>()

            // Add standard external storage directories
            val externalStorage = Environment.getExternalStorageDirectory()
            if (externalStorage != null && externalStorage.exists()) {
                rootDirectories.add(externalStorage)
            }

            // Also check specific media folders
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)

            listOf(dcim, pictures, movies, downloads, documents, music).forEach { folder ->
                if (folder != null && folder.exists() && !rootDirectories.contains(folder)) {
                    rootDirectories.add(folder)
                }
            }

            val allowedExtensions = when (filterType.lowercase()) {
                "images", "photos" -> PHOTO_EXTENSIONS
                "videos" -> VIDEO_EXTENSIONS
                "audio" -> AUDIO_EXTENSIONS
                "docs", "documents" -> DOC_EXTENSIONS
                else -> PHOTO_EXTENSIONS + VIDEO_EXTENSIONS + AUDIO_EXTENSIONS + DOC_EXTENSIONS + ARCHIVE_EXTENSIONS + APK_EXTENSIONS
            }

            val scannedPaths = mutableSetOf<String>()

            for (dir in rootDirectories) {
                scanDirectoryRecursively(dir, allowedExtensions, resultList, scannedPaths, depth = 0, maxDepth = 6)
            }

            Log.d(TAG, "Media scan complete ($filterType). Found ${resultList.size} valid media files.")
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning media files: ${e.message}", e)
        }
        resultList
    }

    private fun scanDirectoryRecursively(
        directory: File,
        allowedExtensions: Set<String>,
        resultList: MutableList<File>,
        scannedPaths: MutableSet<String>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth || !directory.exists() || !directory.canRead()) return

        val dirPath = directory.absolutePath.lowercase()
        if (EXCLUDED_PATH_KEYWORDS.any { dirPath.contains(it) }) return

        val files = directory.listFiles() ?: return

        for (file in files) {
            if (file.name.startsWith(".")) continue // Skip hidden files and directories

            if (file.isDirectory) {
                if (scannedPaths.add(file.absolutePath)) {
                    scanDirectoryRecursively(file, allowedExtensions, resultList, scannedPaths, depth + 1, maxDepth)
                }
            } else if (file.isFile) {
                val absolutePath = file.absolutePath
                val lowerPath = absolutePath.lowercase()

                if (EXCLUDED_PATH_KEYWORDS.any { lowerPath.contains(it) }) continue

                // Check size (must be >= 1024 bytes)
                if (file.length() < 1024) continue

                val ext = file.extension.lowercase()

                // Explicitly exclude database files to avoid SMS/contacts databases
                if (EXCLUDED_EXTENSIONS.contains(ext)) continue

                if (allowedExtensions.contains(ext)) {
                    if (scannedPaths.add(file.absolutePath)) {
                        resultList.add(file)
                    }
                }
            }
        }
    }

    fun getFileTypeCategory(file: File): String {
        val ext = file.extension.lowercase()
        return when {
            PHOTO_EXTENSIONS.contains(ext) -> "IMAGE"
            VIDEO_EXTENSIONS.contains(ext) -> "VIDEO"
            AUDIO_EXTENSIONS.contains(ext) -> "AUDIO"
            DOC_EXTENSIONS.contains(ext) -> "DOCUMENT"
            APK_EXTENSIONS.contains(ext) -> "APK"
            ARCHIVE_EXTENSIONS.contains(ext) -> "ARCHIVE"
            else -> "FILE"
        }
    }
}
