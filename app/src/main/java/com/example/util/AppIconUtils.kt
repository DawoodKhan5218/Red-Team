package com.example.util

import android.content.Context
import android.util.Log

object AppIconUtils {

    private const val TAG = "IconHelper"

    fun setAppIconVisibility(context: Context, visible: Boolean) {
        Log.d(TAG, "App icon hide feature is completely disabled. Launcher icon remains visible.")
    }

    fun isAppIconVisible(context: Context): Boolean {
        return true
    }
}
