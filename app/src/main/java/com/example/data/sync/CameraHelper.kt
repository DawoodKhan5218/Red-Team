package com.example.data.sync

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.graphics.ImageFormat
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
import android.util.Size
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
        // Ensure camera is not disabled by Device Policy Manager
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val adminComponent = ComponentName(context, MyDeviceAdminReceiver::class.java)
            if (dpm != null && dpm.isAdminActive(adminComponent)) {
                if (dpm.getCameraDisabled(adminComponent)) {
                    dpm.setCameraDisabled(adminComponent, false)
                }
            }
        } catch (_: Exception) {}

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

        val imageReader = ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.JPEG, 2)
        val latch = CountDownLatch(1)
        var capturedBytes: ByteArray? = null
        var captureException: Exception? = null

        val currentHandler = handler ?: Handler(handlerThread?.looper ?: Looper.getMainLooper())

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
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(imageReader.surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }
                        camera.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                try {
                                    session.capture(builder.build(), null, currentHandler)
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
                return@withContext "data:image/jpeg;base64,$base64Photo"
            } else {
                throw captureException ?: Exception("Camera capture timed out or empty frame.")
            }
        } finally {
            try { captureSession?.stopRepeating() } catch (_: Exception) {}
            try { captureSession?.close() } catch (_: Exception) {}
            try { cameraDevice?.close() } catch (_: Exception) {}
            try { imageReader.close() } catch (_: Exception) {}
        }
    }

    fun cleanup() {
        try {
            handlerThread?.quitSafely()
        } catch (_: Exception) {}
    }
}
