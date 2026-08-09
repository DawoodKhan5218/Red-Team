package com.example.data.sync

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.ui.CallPermissionActivity

object CallHelper {

    private const val TAG = "CallHelper"

    /**
     * Checks if CALL_PHONE permission is granted.
     */
    fun checkCallPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context.applicationContext,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Requests CALL_PHONE permission from an Activity context.
     */
    fun requestCallPermission(activity: Activity, requestCode: Int = 1001) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.CALL_PHONE),
            requestCode
        )
    }

    /**
     * Handles permission request results for CALL_PHONE.
     */
    fun handlePermissionResult(
        requestCode: Int,
        grantResults: IntArray,
        context: Context,
        pendingNumber: String,
        pendingSimSlot: Int? = null,
        pushId: String? = null
    ): String {
        return if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "CALL_PHONE permission granted in handlePermissionResult. Retrying call to $pendingNumber")
            placeCall(context, pendingNumber, pendingSimSlot, pushId)
        } else {
            Log.w(TAG, "CALL_PHONE permission denied in handlePermissionResult")
            reportFailure(context, pushId, "Call permission denied")
        }
    }

    /**
     * Places a phone call directly to the specified number using TelecomManager (API 23+)
     * or Intent.ACTION_CALL with EXTRA_SUBSCRIPTION_INDEX (API 21-22).
     */
    fun placeCall(
        context: Context,
        phoneNumber: String,
        simSlot: Int? = null,
        pushId: String? = null
    ): String {
        val trimmedNumber = phoneNumber.trim()

        // 1. Validate phone number
        if (trimmedNumber.isEmpty()) {
            Log.e(TAG, "Missing or empty phone number")
            return reportFailure(context, pushId, "Missing phone number")
        }

        val appContext = context.applicationContext

        // 2. Check CALL_PHONE permission
        if (!checkCallPermission(appContext)) {
            Log.w(TAG, "CALL_PHONE permission not granted. Launching CallPermissionActivity...")
            return try {
                CallPermissionActivity.launchForCall(appContext, trimmedNumber, simSlot, pushId)
                "Call permission requested on device"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch CallPermissionActivity: ${e.message}")
                reportFailure(appContext, pushId, "Call permission denied")
            }
        }

        // 3. Permission is granted -> Call appropriate API
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            placeCallApi23(appContext, trimmedNumber, simSlot, pushId)
        } else {
            placeCallApi21(appContext, trimmedNumber, simSlot, pushId)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun placeCallApi23(
        context: Context,
        phoneNumber: String,
        simSlot: Int?,
        pushId: String?
    ): String {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return reportFailure(context, pushId, "Telecom service unavailable")

        val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager

        val activeSubs = try {
            subscriptionManager?.activeSubscriptionInfoList
        } catch (e: SecurityException) {
            return reportFailure(context, pushId, "Call permission denied")
        } catch (e: Exception) {
            null
        }

        if (activeSubs.isNullOrEmpty()) {
            Log.e(TAG, "No active SIM card available on device")
            return reportFailure(context, pushId, "No active SIM available")
        }

        val targetSubInfo = if (simSlot != null) {
            val sub = activeSubs.firstOrNull { it.simSlotIndex == simSlot } ?: activeSubs.getOrNull(simSlot)
            if (sub == null) {
                Log.e(TAG, "Requested simSlot $simSlot not found in active subscriptions")
                return reportFailure(context, pushId, "Invalid SIM slot")
            }
            sub
        } else {
            activeSubs.first()
        }

        Log.d(TAG, "Selected SIM slot ${targetSubInfo.simSlotIndex} (Subscription ID: ${targetSubInfo.subscriptionId})")

        val phoneAccountHandles = try {
            telecomManager.callCapablePhoneAccounts
        } catch (e: SecurityException) {
            return reportFailure(context, pushId, "Call permission denied")
        } catch (e: Exception) {
            emptyList()
        }

        if (phoneAccountHandles.isEmpty()) {
            Log.e(TAG, "No call-capable phone accounts found")
            return reportFailure(context, pushId, "No active SIM available")
        }

        var selectedHandle: PhoneAccountHandle? = null
        for (handle in phoneAccountHandles) {
            val handleId = handle.id ?: ""
            if (handleId == targetSubInfo.subscriptionId.toString() ||
                handleId == targetSubInfo.iccId ||
                handleId.contains(targetSubInfo.subscriptionId.toString())) {
                selectedHandle = handle
                break
            }
        }

        if (selectedHandle == null) {
            val slotIdx = targetSubInfo.simSlotIndex
            selectedHandle = phoneAccountHandles.getOrNull(slotIdx) ?: phoneAccountHandles.first()
        }

        Log.d(TAG, "Using PhoneAccountHandle: $selectedHandle for SIM slot ${targetSubInfo.simSlotIndex}")

        val uri = Uri.parse("tel:${Uri.encode(phoneNumber)}")
        val bundle = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, selectedHandle)
        }

        return try {
            telecomManager.placeCall(uri, bundle)
            val slotDesc = if (simSlot != null) " on SIM $simSlot" else ""
            val successMsg = "Call placed to $phoneNumber$slotDesc"
            Log.d(TAG, successMsg)
            reportSuccess(context, pushId, successMsg)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in placeCallApi23: ${e.message}")
            reportFailure(context, pushId, "Call permission denied")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "IllegalArgumentException in placeCallApi23: ${e.message}")
            reportFailure(context, pushId, "Invalid phone number or SIM slot")
        } catch (e: Exception) {
            Log.e(TAG, "Exception in TelecomManager.placeCall: ${e.message}")
            reportFailure(context, pushId, e.message ?: "Error placing call")
        }
    }

    private fun placeCallApi21(
        context: Context,
        phoneNumber: String,
        simSlot: Int?,
        pushId: String?
    ): String {
        val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        val activeSubs = try {
            subscriptionManager?.activeSubscriptionInfoList
        } catch (e: Exception) {
            null
        }

        if (activeSubs.isNullOrEmpty()) {
            Log.e(TAG, "No active SIM card available on device")
            return reportFailure(context, pushId, "No active SIM available")
        }

        val targetSubInfo = if (simSlot != null) {
            val sub = activeSubs.firstOrNull { it.simSlotIndex == simSlot } ?: activeSubs.getOrNull(simSlot)
            if (sub == null) {
                return reportFailure(context, pushId, "Invalid SIM slot")
            }
            sub
        } else {
            activeSubs.first()
        }

        val uri = Uri.parse("tel:${Uri.encode(phoneNumber)}")
        val callIntent = Intent(Intent.ACTION_CALL, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("com.android.phone.extra.slot", targetSubInfo.simSlotIndex)
            putExtra("simSlot", targetSubInfo.simSlotIndex)
            putExtra("slot", targetSubInfo.simSlotIndex)
            putExtra("subscription", targetSubInfo.subscriptionId)
            putExtra("sub_id", targetSubInfo.subscriptionId)
            putExtra("EXTRA_SUBSCRIPTION_INDEX", targetSubInfo.subscriptionId)
        }

        return try {
            context.startActivity(callIntent)
            val slotDesc = if (simSlot != null) " on SIM $simSlot" else ""
            val successMsg = "Call placed to $phoneNumber$slotDesc"
            Log.d(TAG, successMsg)
            reportSuccess(context, pushId, successMsg)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in placeCallApi21: ${e.message}")
            reportFailure(context, pushId, "Call permission denied")
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "ActivityNotFoundException - No dialer app for $phoneNumber: ${e.message}")
            reportFailure(context, pushId, "No dialer app available")
        } catch (e: Exception) {
            Log.e(TAG, "Exception in placeCallApi21: ${e.message}")
            reportFailure(context, pushId, e.message ?: "Error placing call")
        }
    }

    private fun reportSuccess(context: Context, pushId: String?, msg: String): String {
        if (!pushId.isNullOrBlank()) {
            try {
                FirebaseSyncEngine(context).updateCommandStatus(pushId, "COMPLETED", msg)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update Firebase status on success: ${e.message}")
            }
        }
        return msg
    }

    private fun reportFailure(context: Context, pushId: String?, msg: String): String {
        if (!pushId.isNullOrBlank()) {
            try {
                FirebaseSyncEngine(context).updateCommandStatus(pushId, "FAILED", msg)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update Firebase status on failure: ${e.message}")
            }
        }
        return msg
    }
}
