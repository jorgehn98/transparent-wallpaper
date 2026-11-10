package com.jorgehn98.transparentwallpaper

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder

/**
 * Live wallpaper that draws the camera in real time.
 *
 * The engine only draws while it is visible and the camera is enabled from the
 * app. If another app opens the camera, the system takes it away: we close and
 * retry when it is free again.
 */
class CameraWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = CameraEngine()

    private inner class CameraEngine : Engine(), WallpaperState.Listener {

        private val mainHandler = Handler(Looper.getMainLooper())

        private var renderThread: HandlerThread? = null
        private var renderHandler: Handler? = null
        private var renderer: GlCameraRenderer? = null

        @Volatile
        private var visible = false

        private var drawPending = false

        private var camera: CameraController? = null
        private var currentCameraId: String? = null
        private var retriesLeft = 0

        private val cameraManager: CameraManager? =
            getSystemService(CameraManager::class.java)

        private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                if (cameraId != currentCameraId) return
                mainHandler.post { startCamera() }
            }

            override fun onCameraUnavailable(cameraId: String) {
                // If we own it, the close arrives through onDisconnected.
                if (cameraId != currentCameraId || camera != null) return
                mainHandler.post { camera = null }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            WallpaperState.load(this@CameraWallpaperService)
            WallpaperState.addListener(this)
            runCatching {
                cameraManager?.registerAvailabilityCallback(
                    mainExecutor,
                    availabilityCallback,
                )
            }
        }

        override fun onDestroy() {
            WallpaperState.removeListener(this)
            runCatching { cameraManager?.unregisterAvailabilityCallback(availabilityCallback) }
            stopCamera(clearFrame = false)
            stopRender()
            if (!isPreview) {
                CameraForegroundService.stop(this@CameraWallpaperService)
            }
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            this.visible = visible
            if (!visible) {
                stopCamera(clearFrame = false)
            } else {
                applyState()
            }
        }

        override fun onStateChanged() {
            retriesLeft = RETRY_ATTEMPTS
            applyState()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            startRender(holder.surface)
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            renderHandler?.post { renderer?.setViewport(width, height) }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopCamera(clearFrame = false)
            stopRender()
        }

        private fun applyState() {
            if (!visible) {
                stopCamera(clearFrame = false)
                return
            }
            if (!WallpaperState.active) {
                stopCamera(clearFrame = true)
                return
            }
            stopCamera(clearFrame = false)
            startCamera()
        }

        private fun startCamera() {
            if (!visible || !WallpaperState.active || camera != null) return
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            val activeRenderer = renderer ?: return
            val cameraId = CameraLens.cameraId(
                this@CameraWallpaperService,
                WallpaperState.frontCamera,
            ) ?: return

            currentCameraId = cameraId
            val size = CameraLens.size(this@CameraWallpaperService, cameraId)
            activeRenderer.setBufferSize(size.width, size.height)

            val controller = CameraController(
                this@CameraWallpaperService,
                cameraId,
                activeRenderer.cameraSurface,
            )
            controller.onLost = { reason ->
                Log.w(TAG, reason)
                mainHandler.post { onCameraLost(controller, reason) }
            }
            camera = controller
            controller.open()
        }

        private fun onCameraLost(controller: CameraController, reason: String) {
            if (camera !== controller) return
            camera = null
            controller.close()
            if (!visible || !WallpaperState.active) return

            // The camera can take a moment to become free (for example, right
            // after we close it). Bounded retries.
            if (retriesLeft > 0) {
                retriesLeft--
                mainHandler.postDelayed({ startCamera() }, RETRY_DELAY_MS)
            } else {
                Log.w(TAG, "Sin cámara: $reason")
            }
        }

        private fun stopCamera(clearFrame: Boolean) {
            camera?.close()
            camera = null
            currentCameraId = null
            if (clearFrame) {
                val current = renderer
                renderHandler?.post { current?.clear() }
            }
        }

        private fun startRender(surface: Surface) {
            if (renderThread != null) return

            val thread = HandlerThread("wallpaper-gl")
            thread.start()
            val handler = Handler(thread.looper)
            renderThread = thread
            renderHandler = handler

            handler.post {
                try {
                    val created = GlCameraRenderer(surface, 1280, 720)
                    created.create()
                    created.surfaceTexture.setOnFrameAvailableListener(
                        { requestDraw() },
                        handler,
                    )
                    renderer = created
                    mainHandler.post { applyState() }
                } catch (e: Exception) {
                    Log.e(TAG, "No se pudo crear el renderer GL", e)
                }
            }
        }

        private fun stopRender() {
            val thread = renderThread
            val handler = renderHandler
            val current = renderer
            renderThread = null
            renderHandler = null
            renderer = null

            if (thread != null && handler != null) {
                handler.post {
                    current?.release()
                    thread.quitSafely()
                }
            }
        }

        private fun requestDraw() {
            if (drawPending) return
            drawPending = true
            renderHandler?.post {
                drawPending = false
                renderer?.draw()
            }
        }
    }

    private companion object {
        const val TAG = "CameraWallpaper"
        const val RETRY_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 400L
    }
}
