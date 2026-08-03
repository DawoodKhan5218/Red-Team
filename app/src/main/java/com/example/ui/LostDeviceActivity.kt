package com.example.ui

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import androidx.compose.ui.unit.sp
import com.example.data.sync.LostModeManager
import com.example.receiver.MyDeviceAdminReceiver
import com.example.util.ToastUtils

class LostDeviceActivity : ComponentActivity() {

    private var activeSiren: Ringtone? = null

    private val remoteCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                LostModeManager.ACTION_UNLOCK_LOST_MODE -> {
                    ToastUtils.showSingleToast(this@LostDeviceActivity, "Unlocked remotely!")
                    stopLockTaskIfNeeded()
                    stopSiren()
                    finish()
                }
                LostModeManager.ACTION_SIREN_STATE -> {
                    val play = intent.getBooleanExtra(LostModeManager.EXTRA_SIREN_PLAY, false)
                    if (play) startSiren() else stopSiren()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable screen pinning (lock task mode) to suppress Home and Recent apps buttons
        startLockTaskIfNeeded()

        // Register broadcast receiver for remote actions
        val filter = IntentFilter().apply {
            addAction(LostModeManager.ACTION_UNLOCK_LOST_MODE)
            addAction(LostModeManager.ACTION_SIREN_STATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(remoteCommandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(remoteCommandReceiver, filter)
        }

        // Configure window to show on top of keyguard / lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContent {
            val config = remember { LostModeManager.getOverlayConfig(this) }
            val themeColors = remember(config.theme) { resolveOverlayTheme(config.theme) }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = themeColors.backgroundColor
            ) {
                LostDeviceScreen(
                    onUnlockSuccess = {
                        stopLockTaskIfNeeded()
                        stopSiren()
                        finish()
                    },
                    onToggleSiren = { play ->
                        if (play) startSiren() else stopSiren()
                    }
                )
            }
        }
    }

    private fun startSiren() {
        try {
            stopSiren()
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            activeSiren = RingtoneManager.getRingtone(applicationContext, alarmUri)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                play()
            }
        } catch (e: Exception) {
            ToastUtils.showSingleToast(this, "Siren error: ${e.message}")
        }
    }

    private fun stopSiren() {
        try {
            activeSiren?.let { siren ->
                try {
                    if (siren.isPlaying) {
                        siren.stop()
                    }
                } catch (_: Exception) {}
                try {
                    siren.stop()
                } catch (_: Exception) {}
            }
            activeSiren = null
        } catch (_: Exception) {}
    }

    private fun startLockTaskIfNeeded() {
        if (!LostModeManager.isLostModeActive(this)) return
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
            if (dpm != null && (dpm.isDeviceOwnerApp(packageName) || dpm.isAdminActive(adminComponent))) {
                try {
                    dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
                } catch (e: Exception) {
                    android.util.Log.e("LostDeviceActivity", "LockTask packages config notice: ${e.message}")
                }
            }
            startLockTask()
        } catch (e: Exception) {
            android.util.Log.e("LostDeviceActivity", "startLockTask notice: ${e.message}")
        }
    }

    private fun stopLockTaskIfNeeded() {
        try {
            stopLockTask()
        } catch (e: Exception) {
            android.util.Log.e("LostDeviceActivity", "stopLockTask notice: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        if (LostModeManager.isLostModeActive(this)) {
            startLockTaskIfNeeded()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && LostModeManager.isLostModeActive(this)) {
            try {
                @Suppress("DEPRECATION")
                val closeDialogs = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                sendBroadcast(closeDialogs)
            } catch (_: Exception) {}
            startLockTaskIfNeeded()
            relaunchLostScreen()
        }
    }

    override fun onPause() {
        super.onPause()
        if (LostModeManager.isLostModeActive(this)) {
            relaunchLostScreen()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (LostModeManager.isLostModeActive(this)) {
            relaunchLostScreen()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (LostModeManager.isLostModeActive(this)) {
            ToastUtils.showSingleToast(this, "Device is locked. Enter owner PIN to unlock.")
        } else {
            super.onBackPressed()
        }
    }

    private fun relaunchLostScreen() {
        val intent = Intent(this, LostDeviceActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(remoteCommandReceiver)
        } catch (_: Exception) {}
        stopSiren()
    }
}

data class OverlayThemeColors(
    val backgroundColor: Color,
    val cardColor: Color,
    val primaryAccent: Color,
    val borderAccent: Color,
    val iconBackground: Color,
    val textColor: Color = Color.White
)

fun resolveOverlayTheme(themeName: String): OverlayThemeColors {
    return when (themeName) {
        "Matrix Cyber" -> OverlayThemeColors(
            backgroundColor = Color(0xFF06130B),
            cardColor = Color(0xFF0F2618),
            primaryAccent = Color(0xFF00E676),
            borderAccent = Color(0xFF2E7D32),
            iconBackground = Color(0xFF00E676).copy(alpha = 0.2f)
        )
        "Deep Navy" -> OverlayThemeColors(
            backgroundColor = Color(0xFF091220),
            cardColor = Color(0xFF13223B),
            primaryAccent = Color(0xFF4FC3F7),
            borderAccent = Color(0xFF0288D1),
            iconBackground = Color(0xFF4FC3F7).copy(alpha = 0.2f)
        )
        "Caution Gold" -> OverlayThemeColors(
            backgroundColor = Color(0xFF181308),
            cardColor = Color(0xFF292110),
            primaryAccent = Color(0xFFFFD54F),
            borderAccent = Color(0xFFF57F17),
            iconBackground = Color(0xFFFFD54F).copy(alpha = 0.2f)
        )
        "Midnight Purple" -> OverlayThemeColors(
            backgroundColor = Color(0xFF120A1B),
            cardColor = Color(0xFF221533),
            primaryAccent = Color(0xFFE040FB),
            borderAccent = Color(0xFF8E24AA),
            iconBackground = Color(0xFFE040FB).copy(alpha = 0.2f)
        )
        else -> OverlayThemeColors( // Red Emergency
            backgroundColor = Color(0xFF0D0F12),
            cardColor = Color(0xFF1E222A),
            primaryAccent = Color(0xFFFF5252),
            borderAccent = Color(0xFFD32F2F),
            iconBackground = Color(0xFFD32F2F).copy(alpha = 0.2f)
        )
    }
}

fun resolveOverlayIcon(iconName: String): ImageVector {
    return when (iconName) {
        "Shield" -> Icons.Default.Security
        "Warning" -> Icons.Default.Warning
        "Phone" -> Icons.Default.Phone
        "Info" -> Icons.Default.Info
        else -> Icons.Default.Lock
    }
}

// =========================================================================
// DEDICATED PLACE FOR PASTING YOUR OWN LOCK SCREEN TEMPLATE
// =========================================================================
// Instructions:
// You can paste your custom Jetpack Compose UI layout or template code directly
// inside the `CustomLockScreenTemplate` Composable function below.
// If enabled/customized, this template renders directly on the Lock Screen overlay.
// =========================================================================

@Composable
fun CustomLockScreenTemplate(
    config: LostModeManager.LostOverlayConfig,
    themeColors: OverlayThemeColors,
    headerIcon: ImageVector
) {
    // [PASTE YOUR CUSTOM LOCK SCREEN TEMPLATE UI HERE]
    // Example default layout structure:
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Warning/Custom Icon Banner
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(themeColors.iconBackground)
                .border(2.dp, themeColors.borderAccent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = headerIcon,
                contentDescription = "Device Locked",
                tint = themeColors.primaryAccent,
                modifier = Modifier.size(44.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Dynamic Title
        Text(
            text = config.title.ifBlank { LockScreenConfig.LOCK_SCREEN_TITLE }.uppercase(),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = themeColors.primaryAccent,
            letterSpacing = 1.5.sp,
            textAlign = TextAlign.Center
        )

        // Dynamic Subtitle
        Text(
            text = config.subtitle.ifBlank { LockScreenConfig.LOCK_SCREEN_SUBTITLE },
            fontSize = 13.sp,
            color = Color.LightGray,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
        )

        // Dynamic Message Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = themeColors.cardColor),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, themeColors.borderAccent.copy(alpha = 0.6f))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = themeColors.primaryAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "MESSAGE FROM OWNER",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = themeColors.primaryAccent,
                        letterSpacing = 1.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = config.message.ifBlank { "This phone is lost. If found, please return it to the owner." },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Dynamic Reward & Account Details Card
        if (config.rewardNote.isNotBlank() || config.accountNumber.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2818)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.5.dp, Color(0xFF00E676))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (config.rewardNote.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Reward",
                                tint = Color(0xFFFFD54F),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = config.rewardNote,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFFFD54F),
                                letterSpacing = 1.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    if (config.accountNumber.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF101C11))
                                .border(1.dp, Color(0xFF00C853), RoundedCornerShape(10.dp))
                                .padding(vertical = 12.dp, horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "PAYMENT ACCOUNT NUMBER",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFB9F6CA),
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = config.accountNumber,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    letterSpacing = 1.5.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LostDeviceScreen(
    onUnlockSuccess: () -> Unit,
    onToggleSiren: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val config = remember { LostModeManager.getOverlayConfig(context) }
    val themeColors = remember(config.theme) { resolveOverlayTheme(config.theme) }
    val headerIcon = remember(config.icon) { resolveOverlayIcon(config.icon) }

    var enteredPin by remember { mutableStateOf("") }
    var showPinInput by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf(false) }
    var isSirenPlaying by remember { mutableStateOf(false) }

    // Intercept back button when in lost mode
    BackHandler(enabled = LostModeManager.isLostModeActive(context)) {
        ToastUtils.showSingleToast(context, "Device is locked. Enter owner PIN to unlock.")
    }

    DisposableEffect(Unit) {
        onDispose {
            onToggleSiren(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(themeColors.backgroundColor)
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Dedicated Lock Screen Template Component
            CustomLockScreenTemplate(
                config = config,
                themeColors = themeColors,
                headerIcon = headerIcon
            )

            Spacer(modifier = Modifier.height(16.dp))

            Spacer(modifier = Modifier.height(16.dp))

            // Owner Contact Information
            if (config.showContact && (config.ownerPhone.isNotBlank() || config.ownerEmail.isNotBlank())) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = themeColors.cardColor),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, themeColors.borderAccent.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "CONTACT OWNER",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = themeColors.primaryAccent,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        if (config.ownerPhone.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Phone,
                                    contentDescription = null,
                                    tint = themeColors.primaryAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = config.ownerPhone,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        if (config.ownerEmail.isNotBlank()) {
                            if (config.ownerPhone.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Email,
                                    contentDescription = null,
                                    tint = Color.LightGray,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = config.ownerEmail,
                                    fontSize = 14.sp,
                                    color = Color.LightGray
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
            }

            // Action: Toggle Siren Alarm (if enabled in config)
            if (config.showSiren) {
                Button(
                    onClick = {
                        isSirenPlaying = !isSirenPlaying
                        onToggleSiren(isSirenPlaying)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("toggle_siren_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSirenPlaying) Color(0xFFD32F2F) else themeColors.cardColor
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, themeColors.borderAccent.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = if (isSirenPlaying) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isSirenPlaying) "Stop Siren Alarm" else "Sound Loud Siren Alarm",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // Unlock section for owner
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = themeColors.cardColor),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Are you the owner?",
                            fontSize = 14.sp,
                            color = Color.LightGray
                        )
                        Button(
                            onClick = { showPinInput = !showPinInput },
                            colors = ButtonDefaults.buttonColors(containerColor = themeColors.borderAccent.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = if (showPinInput) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (showPinInput) "Hide PIN" else "Unlock Device",
                                fontSize = 13.sp,
                                color = Color.White
                            )
                        }
                    }

                    AnimatedVisibility(visible = showPinInput) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        ) {
                            OutlinedTextField(
                                value = enteredPin,
                                onValueChange = {
                                    enteredPin = it
                                    pinError = false
                                },
                                label = { Text("Enter 4-digit PIN", color = Color.Gray) },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                isError = pinError,
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("pin_unlock_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = themeColors.primaryAccent,
                                    unfocusedBorderColor = Color.Gray,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )

                            if (pinError) {
                                Text(
                                    text = "Incorrect PIN. Please try again.",
                                    color = Color(0xFFFF5252),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Button(
                                onClick = {
                                    val success = LostModeManager.unlockDevice(context, enteredPin)
                                    if (success) {
                                        ToastUtils.showSingleToast(context, "Device unlocked successfully!")
                                        onUnlockSuccess()
                                    } else {
                                        pinError = true
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("submit_unlock_pin_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = themeColors.primaryAccent),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Submit Unlock PIN", fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
