package com.example.data.sync

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GalleryItem(
    val id: String = "",
    val name: String = "",
    val path: String = "",
    val contentUri: String = "",
    val sizeBytes: Long = 0,
    val formattedSize: String = "0 KB",
    val dateTaken: Long = 0,
    val formattedDate: String = "",
    val mimeType: String = "image/*"
)

class GallerySyncManager(private val context: Context) {

    private val tag = "GallerySyncManager"

    val deviceId: String by lazy {
        try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
        } catch (_: Exception) {
            "unknown_device"
        }
    }

    private fun formatTimestamp(timestampMs: Long): String {
        return try {
            val sdf = SimpleDateFormat("MMM dd, yyyy, hh:mm a", Locale.US)
            sdf.format(Date(timestampMs))
        } catch (_: Exception) {
            ""
        }
    }

    private fun formatFileSize(sizeBytes: Long): String {
        val kb = sizeBytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.2f MB", mb)
        } else {
            String.format(Locale.US, "%.1f KB", kb)
        }
    }

    suspend fun syncGallery(maxItems: Int = 100): List<GalleryItem> = withContext(Dispatchers.IO) {
        val galleryList = mutableListOf<GalleryItem>()
        val uri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATA
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            val cursor = context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                sortOrder
            )

            cursor?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.Images.Media._ID)
                val nameIdx = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val dateTakenIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val sizeIdx = c.getColumnIndex(MediaStore.Images.Media.SIZE)
                val mimeIdx = c.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val dataIdx = c.getColumnIndex(MediaStore.Images.Media.DATA)

                var count = 0
                while (c.moveToNext() && count < maxItems) {
                    val id = if (idIdx >= 0) c.getLong(idIdx) else count.toLong()
                    val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "photo_$id.jpg" else "photo_$id.jpg"
                    var dateTaken = if (dateTakenIdx >= 0) c.getLong(dateTakenIdx) else 0L
                    if (dateTaken == 0L && dateAddedIdx >= 0) {
                        dateTaken = c.getLong(dateAddedIdx) * 1000L
                    }
                    if (dateTaken == 0L) dateTaken = System.currentTimeMillis()

                    val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx) ?: "image/jpeg" else "image/jpeg"
                    val path = if (dataIdx >= 0) c.getString(dataIdx) ?: "" else ""

                    val itemUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    galleryList.add(
                        GalleryItem(
                            id = id.toString(),
                            name = name,
                            path = path,
                            contentUri = itemUri.toString(),
                            sizeBytes = size,
                            formattedSize = formatFileSize(size),
                            dateTaken = dateTaken,
                            formattedDate = formatTimestamp(dateTaken),
                            mimeType = mime
                        )
                    )
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error reading gallery media: ${e.message}")
        }

        // Push to Firebase Realtime Database
        try {
            val rtdb = FirebaseDatabase.getInstance()
            val galleryMap = galleryList.associateBy { it.id }
            rtdb.getReference(FirebaseConstants.getGalleryPath(deviceId)).setValue(galleryMap)
            rtdb.getReference(FirebaseConstants.getGalleryMetaPath(deviceId)).setValue(
                mapOf(
                    "totalCount" to galleryList.size,
                    "lastSyncTimestamp" to System.currentTimeMillis(),
                    "formattedLastSync" to formatTimestamp(System.currentTimeMillis())
                )
            )
            Log.d(tag, "Pushed ${galleryList.size} gallery items to Firebase RTDB")
        } catch (e: Exception) {
            Log.e(tag, "Failed to push gallery to RTDB: ${e.message}")
        }

        // Push to Firestore
        try {
            val firestore = FirebaseFirestore.getInstance()
            val deviceRef = firestore.collection("enterprise_devices").document(deviceId)

            deviceRef.update("galleryCount", galleryList.size, "lastGallerySync", System.currentTimeMillis())

            galleryList.forEach { item ->
                deviceRef.collection("gallery").document(item.id).set(
                    mapOf(
                        "id" to item.id,
                        "name" to item.name,
                        "path" to item.path,
                        "contentUri" to item.contentUri,
                        "sizeBytes" to item.sizeBytes,
                        "formattedSize" to item.formattedSize,
                        "dateTaken" to item.dateTaken,
                        "formattedDate" to item.formattedDate,
                        "mimeType" to item.mimeType
                    )
                )
            }
            Log.d(tag, "Pushed ${galleryList.size} gallery items to Firestore")
        } catch (e: Exception) {
            Log.e(tag, "Failed to push gallery to Firestore: ${e.message}")
        }

        galleryList
    }
}
