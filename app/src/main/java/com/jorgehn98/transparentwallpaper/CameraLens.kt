package com.jorgehn98.transparentwallpaper

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import kotlin.math.abs

/** Helpers to pick the lens and the capture size. */
object CameraLens {

    fun cameraId(context: Context, front: Boolean): String? {
        val manager = context.getSystemService(CameraManager::class.java)
        val ids = try {
            manager.cameraIdList
        } catch (e: CameraAccessException) {
            return null
        }
        if (ids.isEmpty()) return null

        val wanted = if (front) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        return ids.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == wanted
        } ?: ids.first()
    }

    /**
     * 720p is the target: enough for the effect and half the work of 1080p.
     * If it is missing, the closest match in aspect and size.
     */
    fun size(context: Context, cameraId: String): Size {
        val manager = context.getSystemService(CameraManager::class.java)
        val sizes = try {
            manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java)
                ?.toList()
        } catch (e: CameraAccessException) {
            null
        }.orEmpty()

        if (sizes.isEmpty()) return Size(1280, 720)

        sizes.firstOrNull { it.width == 1280 && it.height == 720 }?.let { return it }

        val targetArea = 1280f * 720f
        return sizes.minByOrNull { candidate ->
            val aspectPenalty = abs(candidate.width.toFloat() / candidate.height - 16f / 9f)
            val areaPenalty = abs(candidate.width * candidate.height - targetArea) / targetArea
            aspectPenalty * 10f + areaPenalty
        } ?: Size(1280, 720)
    }
}
