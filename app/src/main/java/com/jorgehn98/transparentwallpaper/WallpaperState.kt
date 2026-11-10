package com.jorgehn98.transparentwallpaper

import android.content.Context
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Shared state between the Activity and the wallpaper engine.
 *
 * Both live in the same process, so a listener list is enough to react
 * immediately; prefs cover process death and recreation.
 */
object WallpaperState {

    fun interface Listener {
        fun onStateChanged()
    }

    private const val PREFS = "wallpaper_state"
    private const val KEY_ACTIVE = "active"
    private const val KEY_FRONT_CAMERA = "front_camera"

    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    var frontCamera: Boolean = false
        private set

    fun load(context: Context) {
        val prefs = prefs(context)
        active = prefs.getBoolean(KEY_ACTIVE, false)
        frontCamera = prefs.getBoolean(KEY_FRONT_CAMERA, false)
    }

    fun setActive(context: Context, value: Boolean) {
        if (active == value) return
        active = value
        prefs(context).edit().putBoolean(KEY_ACTIVE, value).apply()
        notifyListeners()
    }

    fun setFrontCamera(context: Context, value: Boolean) {
        if (frontCamera == value) return
        frontCamera = value
        prefs(context).edit().putBoolean(KEY_FRONT_CAMERA, value).apply()
        notifyListeners()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun notifyListeners() {
        listeners.forEach { it.onStateChanged() }
    }
}
