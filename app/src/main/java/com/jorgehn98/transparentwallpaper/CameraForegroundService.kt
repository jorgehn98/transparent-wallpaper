package com.jorgehn98.transparentwallpaper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Mantiene el proceso en primer plano mientras el wallpaper usa la cámara.
 *
 * Desde Android 9 el sistema corta la cámara a las apps en segundo plano, y el
 * proceso de un wallpaper no cuenta como primer plano aunque tenga surface
 * visible. El servicio no toca la cámara: solo ancla el proceso.
 */
class CameraForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Red de seguridad: si el servicio muere, la cámara debe apagarse.
        WallpaperState.setActive(this, false)
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "camera_wallpaper"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.jorgehn98.transparentwallpaper.STOP"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, CameraForegroundService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraForegroundService::class.java))
        }
    }
}
