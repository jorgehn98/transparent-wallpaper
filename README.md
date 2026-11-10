# Transparent Wallpaper

An Android live wallpaper that puts the camera on your screen: whatever is in front of the phone shows up behind the icons, as if the display were transparent. It is a toy for short sessions, not something to leave running all day.

![Status](https://img.shields.io/badge/status-v0.1.1-blue)

Tested on a Galaxy S24 Ultra running Android 17, on both the home and lock screens. Other manufacturers may not show live wallpapers on the lock screen.

## Usage

1. Open the app and tap **Set wallpaper**. Android opens the live wallpaper picker; confirm there.
2. Back in the app, tap **Turn camera on**. The first time it asks for camera and notification permissions.
3. Go to the home screen: whatever the camera sees is now the background.
4. To stop it, open the app and tap **Turn off**.

The wallpaper stays set and turns black while the camera is off. Android does not let an app read the previous wallpaper, so to get yours back use **Change system wallpaper** and pick it again. If it was another live wallpaper, the system offers it in the same picker.

## How it works

Three pieces, no dependencies beyond the SDK:

- `CameraWallpaperService` is the live wallpaper. It draws camera frames on its surface with OpenGL ES 2: each frame arrives as an external texture (`GL_TEXTURE_EXTERNAL_OES`) and is drawn directly, with no bitmaps and no CPU copies.
- `CameraController` opens the camera with Camera2 at 720p and 30 fps and feeds frames to that texture. If another app takes the camera, the system ends the session and the wallpaper retries once it is free.
- `CameraForegroundService` keeps the process in the foreground while the camera is on. Since Android 9 a background app loses the camera, and a wallpaper process does not count as foreground even with a visible surface.

The camera only opens when you enable it and the wallpaper is visible; it closes when the screen turns off or another app comes to the front.

## Requirements

- Android 11 (API 30) or newer.
- A front or rear camera.
- For the lock screen, an OEM that honours live wallpapers there. Pixel and Samsung usually do; HyperOS and some Oppo/vivo builds do not.

## Build

You need JDK 17 or newer and the Android SDK (API 36). Create `local.properties` with the SDK path:

```properties
sdk.dir=/path/to/Android/Sdk
```

```sh
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

To install it on a device connected over USB with debugging enabled:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The release build is minified but unsigned: configure your own signing in `app/build.gradle.kts` if you want to distribute it.

## Known limitations

- The camera has a single owner. While the wallpaper holds it, the camera app or the flashlight may fail or freeze the wallpaper. That is expected: closing them reconnects the wallpaper.
- The Android 12+ privacy dot stays on the whole time. The system draws it and it cannot be hidden without root.
- It drains battery and heats up. That is why it stays at 720p instead of 1080p, and why the camera closes as soon as the wallpaper is not visible.
- The illusion is not perfect: the camera is not where your eyes are, so the parallax does not quite match.
- Pointing at a bright screen in a dark room blows out the exposure. That is the camera's auto-exposure, not the renderer.
- Orientation is handled for a portrait wallpaper. If the device rotates to landscape and the launcher follows, the image can end up rotated.

## Privacy

The app does not request internet access and sends nothing anywhere. Camera frames are drawn and discarded; they are never stored or shared.

## License

MIT. See [LICENSE](LICENSE).
