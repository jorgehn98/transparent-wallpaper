# AGENTS.md

Project notes for anyone (human or agent) changing this repo.

## What this is

A single-module Android app that sets a live wallpaper drawing the camera feed in real time. Portrait phone use, personal scale, no analytics, no network.

- `minSdk` 30, `targetSdk` 36, Kotlin, no AndroidX, no Compose, no CameraX.
- Package: `com.jorgehn98.transparentwallpaper`.

## Commands

```sh
# Build
JAVA_HOME=~/.local/jdk21 ./gradlew assembleDebug

# Lint
JAVA_HOME=~/.local/jdk21 ./gradlew lintDebug

# Install on the connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Watch the wallpaper logs
adb logcat -s CameraWallpaper CameraController GlCameraRenderer
```

This machine only ships JDK 25, which Gradle 8.14 does not support. Use the Temurin 21 installed at `~/.local/jdk21`. The SDK path belongs in `local.properties` (gitignored).

## Architecture

| File | Role |
| --- | --- |
| `CameraWallpaperService` | `WallpaperService` + `Engine`. Owns the render thread, the camera lifecycle and the visibility logic. |
| `GlCameraRenderer` | EGL + GLES 2 setup, external texture, crop and draw. All GL state is confined to the render thread. |
| `CameraController` | Camera2 open/session/close on its own `HandlerThread`; reports disconnects. |
| `CameraForegroundService` | Foreground service of type `camera`. Does not touch the camera; it only keeps the process foreground. |
| `WallpaperState` | `SharedPreferences` + in-process listeners shared by the Activity and the engine. |
| `MainActivity` | Permission flow, set-wallpaper flow and the on/off button. |

## Invariants and gotchas

- **The wallpaper process is not foreground for camera purposes.** Without a `camera`-type foreground service Android 9+ refuses the camera. It must be started from a visible Activity, never from the wallpaper itself (Android 12+ background start restriction).
- **The camera has a single owner.** When another app takes it, `CameraDevice.StateCallback.onDisconnected` fires and the device is dead. Close the controller, mark it lost and wait for `CameraManager.AvailabilityCallback.onCameraAvailable`; never try to keep the session.
- **The frame arrives rotated and the texture matrix already fixes it.** On the tested device `getTransformMatrix` returns a 90 degree rotation. Derive the visible aspect from the matrix: if `abs(m[0]) < abs(m[1])` the axes are swapped and the aspect is `height / width`. Using the raw buffer aspect causes a vertical stretch.
- **720p at 30 fps is a deliberate budget.** Do not raise the resolution or frame rate without measuring CPU and heat. Keep the draw path free of CPU copies: external texture straight to the surface.
- **Stop the camera whenever the engine is not visible** (`onVisibilityChanged(false)`, screen off, another app in front). It is the main power saving.
- **Do not try to restore the previous wallpaper.** A third-party app cannot read a static wallpaper (`getDrawable` needs `MANAGE_EXTERNAL_STORAGE`). The button opens the system picker instead.
- **Keep the app small.** No new dependencies unless they remove more code than they add.

## Testing

Only meaningful on a real device; emulator camera behaviour differs. The checklist that found the real bugs:

1. Home screen shows the feed, correctly proportioned.
2. Lock screen shows the feed (OEM dependent).
3. Opening the stock camera app: the wallpaper releases, and reconnects after going back.
4. Screen off: the camera closes (`dumpsys media.camera` shows no active client).
5. Turn off: camera released, foreground service stopped, black wallpaper.
6. Front/back toggle changes the camera id.
7. Screen rotation to landscape (if the launcher rotates).

Useful shell checks:

```sh
adb shell dumpsys media.camera | sed -n '/Active Camera Clients/,/Allowed user IDs/p'
adb shell dumpsys activity services com.jorgehn98.transparentwallpaper
```
