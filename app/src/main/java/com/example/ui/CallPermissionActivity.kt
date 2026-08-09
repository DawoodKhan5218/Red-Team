package com.example.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.data.sync.CallHelper
import com.example.data.sync.FirebaseSyncEngine

class CallPermissionActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CallPermissionActivity"
        private const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        private const val EXTRA_SIM_SLOT = "extra_sim_slot"
        private const val EXTRA_PUSH_ID = "extra_push_id"

        fun launchForCall(
            context: Context,
            phoneNumber: String,
            simSlot: Int? = null,
            pushId: String? = null
        ) {
            val intent = Intent(context, CallPermissionActivity::class.java).apply {
                putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                if (simSlot != null) {
                    putExtra(EXTRA_SIM_SLOT, simSlot)
                }
                putExtra(EXTRA_PUSH_ID, pushId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }
    }

    private var phoneNumberToCall: String? = null
    private var simSlotToUse: Int? = null
    private var pushIdToUpdate: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val number = phoneNumberToCall ?: ""
        val simSlot = simSlotToUse
        val pushId = pushIdToUpdate
        if (isGranted) {
            Log.d(TAG, "CALL_PHONE permission granted by user. Placing call to $number (simSlot: $simSlot)")
            if (number.isNotBlank()) {
                CallHelper.placeCall(this, number, simSlot, pushId)
            }
        } else {
            Log.w(TAG, "CALL_PHONE permission denied by user.")
            if (!pushId.isNullOrBlank()) {
                try {
                    FirebaseSyncEngine(this).updateCommandStatus(pushId, "FAILED", "Call permission denied")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update Firebase status on permission denial: ${e.message}")
                }
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        phoneNumberToCall = intent.getStringExtra(EXTRA_PHONE_NUMBER)
        if (intent.hasExtra(EXTRA_SIM_SLOT)) {
            val slot = intent.getIntExtra(EXTRA_SIM_SLOT, -1)
            if (slot >= 0) {
                simSlotToUse = slot
            }
        }
        pushIdToUpdate = intent.getStringExtra(EXTRA_PUSH_ID)

        if (phoneNumberToCall.isNullOrBlank()) {
            Log.e(TAG, "CallPermissionActivity launched without valid phone number.")
            finish()
            return
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            Log.d(TAG, "CALL_PHONE permission already granted. Placing call...")
            CallHelper.placeCall(this, phoneNumberToCall!!, simSlotToUse, pushIdToUpdate)
            finish()
        } else {
            Log.d(TAG, "Requesting CALL_PHONE permission for $phoneNumberToCall...")
            permissionLauncher.launch(Manifest.permission.CALL_PHONE)
        }
    }
}
