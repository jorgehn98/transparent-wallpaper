package com.jorgehn98.transparentwallpaper

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * Pinta los frames de la cámara en el surface del wallpaper.
 *
 * El frame llega como textura externa y se dibuja directamente: sin pasar por
 * bitmaps ni por CPU, que es lo único que aguanta 720p a 30 fps sin calentar
 * el teléfono de más. Todo el estado GL vive en el hilo de render.
 */
class GlCameraRenderer(
    private val windowSurface: Surface,
    bufferWidth: Int,
    bufferHeight: Int,
) {

    @Volatile
    private var bufferWidth = bufferWidth

    @Volatile
    private var bufferHeight = bufferHeight

    private var viewWidth = 0
    private var viewHeight = 0

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var textureId = 0

    private var aPosition = 0
    private var aTexCoord = 0
    private var uSampler = 0
    private var uTexMatrix = 0
    private var uTexScale = 0

    private val texMatrix = FloatArray(16)

    private var logged = false

    private val quadCoords = floats(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f,
    )

    private val texCoords = floats(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f,
    )

    lateinit var surfaceTexture: SurfaceTexture
        private set

    lateinit var cameraSurface: Surface
        private set

    fun create() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Sin display EGL" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize falló" }

        val configAttributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        check(
            EGL14.eglChooseConfig(eglDisplay, configAttributes, 0, configs, 0, 1, configCount, 0) &&
                configCount[0] > 0,
        ) { "Sin configuración EGL" }

        val contextAttributes = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE,
        )
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            configs[0],
            EGL14.EGL_NO_CONTEXT,
            contextAttributes,
            0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext falló" }

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            configs[0],
            windowSurface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface falló" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent falló"
        }

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uSampler = GLES20.glGetUniformLocation(program, "sTexture")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTexScale = GLES20.glGetUniformLocation(program, "uTexScale")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setDefaultBufferSize(bufferWidth, bufferHeight)
        cameraSurface = Surface(surfaceTexture)
    }

    fun setBufferSize(width: Int, height: Int) {
        if (width == bufferWidth && height == bufferHeight) return
        bufferWidth = width
        bufferHeight = height
        surfaceTexture.setDefaultBufferSize(width, height)
    }

    fun setViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewWidth = width
        viewHeight = height
    }

    fun draw() {
        if (eglSurface == EGL14.EGL_NO_SURFACE) return

        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)

        if (!logged) {
            logged = true
            android.util.Log.d(
                "GlCameraRenderer",
                "view=${viewWidth}x$viewHeight buffer=${bufferWidth}x$bufferHeight " +
                    "matrix=${texMatrix.joinToString(",")}",
            )
        }

        GLES20.glViewport(0, 0, viewWidth.coerceAtLeast(1), viewHeight.coerceAtLeast(1))
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val scale = cropScale()
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uSampler, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform2f(uTexScale, scale[0], scale[1])

        quadCoords.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quadCoords)
        GLES20.glEnableVertexAttribArray(aPosition)

        texCoords.position(0)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glEnableVertexAttribArray(aTexCoord)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun clear() {
        if (eglSurface == EGL14.EGL_NO_SURFACE) return
        GLES20.glViewport(0, 0, viewWidth.coerceAtLeast(1), viewHeight.coerceAtLeast(1))
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun release() {
        if (this::cameraSurface.isInitialized) {
            cameraSurface.release()
        }
        if (this::surfaceTexture.isInitialized) {
            surfaceTexture.release()
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglTerminate(eglDisplay)
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    /**
     * La cámara no tiene el mismo aspecto que la pantalla. En vez de estirar
     * la imagen, se recorta el centro para llenar el fondo.
     */
    private fun cropScale(): FloatArray {
        if (viewWidth <= 0 || viewHeight <= 0) return floatArrayOf(1f, 1f)

        // La cámara suele entregar el frame girado 90º y la matriz de textura
        // lo deshace. Si la matriz intercambia ejes, el aspecto visible es el
        // inverso del buffer.
        val swapped = abs(texMatrix[0]) < abs(texMatrix[1])
        val imageAspect = if (swapped) {
            bufferHeight.toFloat() / bufferWidth
        } else {
            bufferWidth.toFloat() / bufferHeight
        }
        val viewAspect = viewWidth.toFloat() / viewHeight

        return if (imageAspect > viewAspect) {
            floatArrayOf(viewAspect / imageAspect, 1f)
        } else {
            floatArrayOf(1f, imageAspect / viewAspect)
        }
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "No se pudo enlazar el programa GL: ${GLES20.glGetProgramInfoLog(program)}"
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "No se pudo compilar el shader: ${GLES20.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    private fun floats(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    private companion object {
        const val VERTEX_SHADER = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;

void main() {
    gl_Position = aPosition;
    vTexCoord = aTexCoord;
}
"""

        const val FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
precision mediump float;

uniform samplerExternalOES sTexture;
uniform mat4 uTexMatrix;
uniform vec2 uTexScale;
varying vec2 vTexCoord;

void main() {
    vec2 uv = (uTexMatrix * vec4(vTexCoord, 0.0, 1.0)).xy;
    uv = (uv - 0.5) * uTexScale + 0.5;
    gl_FragColor = texture2D(sTexture, uv);
}
"""
    }
}
