package com.example.data.sync

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import java.io.File

class FileExplorerSyncManager(private val context: Context) {

    fun syncFilesToFirebase(deviceId: String) {
        try {
            val rootDir = Environment.getExternalStorageDirectory().takeIf { it.exists() && it.canRead() }
                ?: context.filesDir

            val fileMap = mutableMapOf<String, Any>()
            scanDirectory(rootDir, fileMap, 0, 3)

            val rtdb = FirebaseDatabase.getInstance()
            val filesRef = rtdb.getReference(FirebaseConstants.getFilesPath(deviceId))
            filesRef.setValue(fileMap)
            Log.d("FileExplorerSyncManager", "Synced files to Firebase Realtime Database")
        } catch (e: Exception) {
            Log.e("FileExplorerSyncManager", "Error syncing files: ${e.message}")
        }
    }

    private fun scanDirectory(dir: File, map: MutableMap<String, Any>, currentDepth: Int, maxDepth: Int) {
        if (currentDepth > maxDepth) return
        val files = dir.listFiles() ?: return
        for (file in files) {
            try {
                val key = sanitizeKey(file.name)
                val fileData = mapOf(
                    "name" to file.name,
                    "path" to file.absolutePath,
                    "isDirectory" to file.isDirectory,
                    "size" to if (file.isDirectory) (file.listFiles()?.size ?: 0) else file.length(),
                    "lastModified" to file.lastModified()
                )
                if (file.isDirectory && currentDepth < maxDepth) {
                    val subMap = mutableMapOf<String, Any>()
                    subMap["_meta"] = fileData
                    scanDirectory(file, subMap, currentDepth + 1, maxDepth)
                    map[key] = subMap
                } else {
                    map[key] = fileData
                }
            } catch (_: Exception) {}
        }
    }

    private fun sanitizeKey(name: String): String {
        return name.replace(".", "_")
            .replace("#", "_")
            .replace("$", "_")
            .replace("[", "_")
            .replace("]", "_")
            .ifEmpty { "unnamed" }
    }
}
