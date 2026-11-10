package com.jorgehn98.transparentwallpaper

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var primary: Button
    private lateinit var changeWallpaper: Button
    private lateinit var frontCamera: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        primary = findViewById(R.id.primary)
        changeWallpaper = findViewById(R.id.change_wallpaper)
        frontCamera = findViewById(R.id.front_camera)

        primary.setOnClickListener { onPrimary() }
        changeWallpaper.setOnClickListener { openSystemWallpaperPicker() }
        frontCamera.setOnCheckedChangeListener { _, checked ->
            WallpaperState.setFrontCamera(this, checked)
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        WallpaperState.load(this)
        // Si el proceso se reinició con la cámara marcada como activa, el
        // servicio en primer plano puede haberse quedado por el camino.
        if (WallpaperState.active && isOurWallpaperSet()) {
            CameraForegroundService.start(this)
        }
        refresh()
    }

    private fun onPrimary() {
        if (!isOurWallpaperSet()) {
            openWallpaperPicker()
            return
        }

        if (WallpaperState.active) {
            CameraForegroundService.stop(this)
            WallpaperState.setActive(this, false)
            refresh()
            return
        }

        if (!hasCameraPermission()) {
            requestPermissions(requiredPermissions(), REQUEST_PERMISSIONS)
            return
        }

        startCameraWallpaper()
    }

    private fun startCameraWallpaper() {
        CameraForegroundService.start(this)
        WallpaperState.setActive(this, true)
        refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return

        if (hasCameraPermission()) {
            startCameraWallpaper()
        } else {
            Toast.makeText(this, R.string.error_camera_permission, Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    private fun refresh() {
        val isSet = isOurWallpaperSet()
        val active = WallpaperState.active

        if (frontCamera.isChecked != WallpaperState.frontCamera) {
            frontCamera.isChecked = WallpaperState.frontCamera
        }

        when {
            !isSet -> {
                status.text = getString(R.string.status_not_set)
                primary.text = getString(R.string.action_set_wallpaper)
            }
            active -> {
                status.text = getString(R.string.status_active)
                primary.text = getString(R.string.action_stop)
            }
            else -> {
                status.text = getString(R.string.status_inactive)
                primary.text = getString(R.string.action_start)
            }
        }
        changeWallpaper.isEnabled = isSet
    }

    private fun isOurWallpaperSet(): Boolean =
        WallpaperManager.getInstance(this).getWallpaperInfo()?.component?.packageName == packageName

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.CAMERA)
        }

    private fun openWallpaperPicker() {
        val component = ComponentName(this, CameraWallpaperService::class.java)
        val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)

        try {
            startActivity(direct)
        } catch (e: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (e2: Exception) {
                Toast.makeText(this, R.string.error_no_picker, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openSystemWallpaperPicker() {
        val candidates = listOf(
            Intent(Intent.ACTION_SET_WALLPAPER),
            Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER),
            Intent(Settings.ACTION_DISPLAY_SETTINGS),
        )
        for (candidate in candidates) {
            try {
                startActivity(candidate)
                return
            } catch (e: Exception) {
                // Probamos el siguiente.
            }
        }
        Toast.makeText(this, R.string.error_no_picker, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1
    }
}
