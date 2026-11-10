# Fondo transparente

Live wallpaper para Android que pone la cámara en el fondo de pantalla: lo que hay delante del móvil se ve detrás de los iconos, como si la pantalla fuese transparente. Es un juguete para ratos, no una app para llevar siempre encendida.

![Estado](https://img.shields.io/badge/estado-v0.1.0-blue)

## Uso

1. Abre la app y pulsa **Poner el fondo**. Android abre el selector de fondos animados: confirma ahí.
2. Vuelve a la app y pulsa **Activar cámara**. La primera vez pedirá permiso de cámara y de notificaciones.
3. Ve al inicio: lo que vea la cámara estará de fondo.
4. Para apagarla, abre la app y pulsa **Desactivar**.

El fondo se queda puesto y en negro cuando la cámara está apagada. Android no permite a una app leer el fondo anterior, así que para recuperar el que tenías usa **Cambiar fondo del sistema** y elige el que quieras. Si el anterior era otro fondo animado, el sistema lo ofrece en el mismo selector.

## Cómo funciona

Tres piezas, sin dependencias externas más allá del SDK:

- `CameraWallpaperService` es el live wallpaper. Dibuja los frames de la cámara en su surface con OpenGL ES 2: el frame llega como textura externa (`GL_TEXTURE_EXTERNAL_OES`) y se pinta directamente, sin pasar por bitmaps ni por CPU.
- `CameraController` abre la cámara con Camera2 en 720p y 30 fps, y manda los frames a la textura. Si otra app se lleva la cámara, el sistema corta la sesión y el wallpaper reintenta cuando vuelve a estar libre.
- `CameraForegroundService` mantiene el proceso en primer plano mientras la cámara está encendida. Desde Android 9 una app en segundo plano pierde la cámara, y el proceso de un wallpaper no cuenta como primer plano aunque tenga un surface visible.

La cámara solo se abre cuando la activas y el wallpaper está visible; al apagar la pantalla o al pasar a otra app se cierra.

## Requisitos

- Android 11 (API 30) o superior.
- Cámara trasera o delantera.
- Para bloquear la pantalla hace falta un fabricante que respete el live wallpaper en el lockscreen. En Pixel y Samsung suele funcionar; en HyperOS y algunas capas de Oppo/vivo no.

## Compilar

Necesitas JDK 17 o superior y el SDK de Android (API 36). Crea `local.properties` con la ruta del SDK:

```properties
sdk.dir=/ruta/a/Android/Sdk
```

```sh
./gradlew assembleDebug
```

El APK sale en `app/build/outputs/apk/debug/app-debug.apk`.

Para instalarlo en un móvil conectado por USB con depuración activada:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

El build de release está minificado pero sin firmar: si quieres distribuirlo, configura tu propia firma en `app/build.gradle.kts`.

## Limitaciones conocidas

- La cámara es de un solo dueño. Mientras el fondo la tiene abierta, la app de cámara y la linterna pueden no funcionar o hacer que el fondo se congele. Es lo esperado: al cerrarla, el fondo se reconecta.
- El punto verde de privacidad de Android 12+ se queda encendido todo el rato. El sistema lo dibuja y no se puede ocultar sin root.
- Consume batería y calienta. Es la razón de quedarse en 720p en vez de 1080p, y de cerrar la cámara en cuanto el wallpaper no se ve.
- La ilusión no es perfecta: la cámara no está donde están tus ojos, así que el paralaje no coincide del todo.

## Privacidad

La app no pide permiso de internet y no envía nada a ningún sitio. Los frames de la cámara se dibujan y se descartan; no se guardan ni se comparten.

## Licencia

MIT. Mira [LICENSE](LICENSE).
