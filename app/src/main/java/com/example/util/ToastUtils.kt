package com.example.util

import android.content.Context
import android.widget.Toast

object ToastUtils {
    private var activeToast: Toast? = null

    fun showSingleToast(context: Context, text: String, duration: Int = Toast.LENGTH_SHORT) {
        try {
            activeToast?.cancel()
            val newToast = Toast.makeText(context.applicationContext, text, duration)
            activeToast = newToast
            newToast.show()
        } catch (_: Exception) {}
    }
}
