package com.jorgehn98.transparentwallpaper

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface

/**
 * Opens the camera and sends frames to the wallpaper surface.
 *
 * The whole lifecycle runs on its own thread. When another app takes the
 * camera, the system calls onDisconnected and we have to close and wait; the
 * session cannot be kept paused.
 */
class CameraController(
    context: Context,
    private val cameraId: String,
    private val outputSurface: Surface,
) {

    /** Called when we lose the camera (another app took it) or something fails. */
    var onLost: ((String) -> Unit)? = null

    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera-wallpaper").apply { start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var closed = false

    private val fpsRange: Range<Int>? = runCatching {
        manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
    }.getOrNull()?.let { ranges ->
        ranges.firstOrNull { it == Range(30, 30) }
            ?: ranges.filter { it.upper == 30 }.minByOrNull { it.lower }
            ?: ranges.maxByOrNull { it.upper }
    }

    private val afMode: Int? = runCatching {
        val modes = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        modes?.firstOrNull { it == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE }
    }.getOrNull()

    fun open() {
        handler.post {
            if (closed || device != null) return@post
            try {
                manager.openCamera(cameraId, stateCallback, handler)
            } catch (e: SecurityException) {
                onLost?.invoke("Sin permiso de cámara")
            } catch (e: CameraAccessException) {
                onLost?.invoke("No se pudo abrir la cámara: ${e.reason}")
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        handler.post {
            runCatching { session?.close() }
            session = null
            runCatching { device?.close() }
            device = null
            thread.quitSafely()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (closed) {
                camera.close()
                return
            }
            device = camera
            createSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            device = null
            session = null
            if (!closed) onLost?.invoke("Otra app ha cogido la cámara")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            device = null
            session = null
            if (!closed) onLost?.invoke("Error de cámara ($error)")
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(camera: CameraDevice) {
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            request.addTarget(outputSurface)
            fpsRange?.let { request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            afMode?.let { request.set(CaptureRequest.CONTROL_AF_MODE, it) }
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

            camera.createCaptureSession(
                listOf(outputSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (closed) {
                            configured.close()
                            return
                        }
                        session = configured
                        try {
                            configured.setRepeatingRequest(request.build(), null, handler)
                        } catch (e: Exception) {
                            Log.e(TAG, "setRepeatingRequest falló", e)
                            onLost?.invoke("No se pudo arrancar la vista previa")
                        }
                    }

                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        if (!closed) onLost?.invoke("No se pudo configurar la cámara")
                    }
                },
                handler,
            )
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession falló", e)
            onLost?.invoke("No se pudo configurar la cámara")
        }
    }

    private companion object {
        const val TAG = "CameraController"
    }
}
