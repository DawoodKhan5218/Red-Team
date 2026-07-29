package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var currentDir by remember {
        mutableStateOf(
            Environment.getExternalStorageDirectory().takeIf { it.exists() && it.canRead() }
                ?: context.filesDir
        )
    }

    var fileList by remember(currentDir) {
        mutableStateOf(
            try {
                currentDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        )
    }

    var selectedFile by remember { mutableStateOf<File?>(null) }
    var showDetailsDialog by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }

    BackHandler(enabled = currentDir.parentFile != null && currentDir != Environment.getExternalStorageDirectory()) {
        currentDir.parentFile?.let { parent ->
            if (parent.exists() && parent.canRead()) {
                currentDir = parent
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HighDensityBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = HighDensityOnSurface)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "File Explorer",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = HighDensityOnSurface
                        )
                        Text(
                            text = currentDir.absolutePath,
                            fontSize = 11.sp,
                            color = HighDensityOnSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(240.dp)
                        )
                    }
                }

                if (currentDir.parentFile != null) {
                    OutlinedButton(
                        onClick = {
                            currentDir.parentFile?.let { parent ->
                                if (parent.exists() && parent.canRead()) {
                                    currentDir = parent
                                }
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, HighDensityOutline)
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = "Up", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Up", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // File List Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = HighDensityCardWhite),
                border = BorderStroke(1.dp, HighDensityOutline)
            ) {
                if (fileList.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = HighDensityPrimary.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Folder is empty or permission required",
                                color = HighDensityOnSurface.copy(alpha = 0.6f),
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(fileList) { file ->
                            FileItemRow(
                                file = file,
                                dateFormat = dateFormat,
                                onClick = {
                                    if (file.isDirectory) {
                                        if (file.canRead()) {
                                            currentDir = file
                                        }
                                    } else {
                                        selectedFile = file
                                        showDetailsDialog = true
                                    }
                                },
                                onInfoClick = {
                                    selectedFile = file
                                    showDetailsDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // File Details / Actions Dialog
    if (showDetailsDialog && selectedFile != null) {
        val file = selectedFile!!
        AlertDialog(
            onDismissRequest = { showDetailsDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        tint = HighDensityPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Path: ${file.absolutePath}", fontSize = 12.sp)
                    Text("Size: ${if (file.isDirectory) "${file.listFiles()?.size ?: 0} items" else formatFileSize(file.length())}", fontSize = 12.sp)
                    Text("Modified: ${dateFormat.format(Date(file.lastModified()))}", fontSize = 12.sp)
                    Text("Writable: ${file.canWrite()}", fontSize = 12.sp)
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!file.isDirectory) {
                        TextButton(
                            onClick = {
                                try {
                                    val uri = Uri.fromFile(file)
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "*/*")
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                                showDetailsDialog = false
                            }
                        ) {
                            Text("Open")
                        }
                    }
                    Button(
                        onClick = { showDetailsDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = HighDensityPrimary)
                    ) {
                        Text("Close")
                    }
                }
            }
        )
    }
}

@Composable
fun FileItemRow(
    file: File,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit,
    onInfoClick: () -> Unit
) {
    val isDir = file.isDirectory
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF9FAFB)),
        border = BorderStroke(1.dp, Color(0xFFE5E7EB))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (isDir) HighDensityPrimary.copy(alpha = 0.12f) else Color(0xFFE0E7FF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        tint = if (isDir) HighDensityPrimary else Color(0xFF4F46E5),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = HighDensityOnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isDir) "${file.listFiles()?.size ?: 0} items • ${dateFormat.format(Date(file.lastModified()))}"
                               else "${formatFileSize(file.length())} • ${dateFormat.format(Date(file.lastModified()))}",
                        fontSize = 11.sp,
                        color = HighDensityOnSurface.copy(alpha = 0.6f)
                    )
                }
            }

            IconButton(onClick = onInfoClick, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "Info",
                    tint = HighDensityOnSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

fun formatFileSize(sizeBytes: Long): String {
    val kb = sizeBytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.getDefault(), "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.getDefault(), "%.2f MB", mb)
        kb >= 1.0 -> String.format(Locale.getDefault(), "%.2f KB", kb)
        else -> "$sizeBytes B"
    }
}
