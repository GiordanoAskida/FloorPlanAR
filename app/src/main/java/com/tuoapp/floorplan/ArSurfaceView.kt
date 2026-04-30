package com.tuoapp.floorplan

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.Surface
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    val lastFrame = AtomicReference<Frame?>(null)
    var onFrameCallback: ((Frame) -> Unit)? = null
    private val renderer = CameraRenderer()

    var session: Session? = null
        set(value) { field = value; renderer.pendingSession = value }

    init {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun resume() = onResume()
    fun pause()  = onPause()

    inner class CameraRenderer : Renderer {
        var pendingSession: Session? = null
        private var activeSession: Session? = null
        private var cameraTexId = -1
        private var program = 0
        private var posAttr = 0
        private var texAttr = 0
        private var texUniform = 0
        private var vpW = 1; private var vpH = 1

        private val quadCoords    = floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)
        private val quadTexCoords = FloatArray(8)
        private lateinit var coordsBuf: FloatBuffer

        private val VERT = "attribute vec4 a_Pos; attribute vec2 a_Tex; varying vec2 v_Tex;
" +
                           "void main() { gl_Position = a_Pos; v_Tex = a_Tex; }"
        private val FRAG = "#extension GL_OES_EGL_image_external : require
" +
                           "precision mediump float;
" +
                           "uniform samplerExternalOES u_Tex; varying vec2 v_Tex;
" +
                           "void main() { gl_FragColor = texture2D(u_Tex, v_Tex); }"

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            cameraTexId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,     GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,     GLES20.GL_CLAMP_TO_EDGE)
            program    = buildProg(VERT, FRAG)
            posAttr    = GLES20.glGetAttribLocation(program, "a_Pos")
            texAttr    = GLES20.glGetAttribLocation(program, "a_Tex")
            texUniform = GLES20.glGetUniformLocation(program, "u_Tex")
            coordsBuf  = ByteBuffer.allocateDirect(quadCoords.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
                .also { it.put(quadCoords); it.position(0) }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            vpW = width; vpH = height
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            if (pendingSession != null) {
                activeSession = pendingSession; pendingSession = null
                activeSession?.setCameraTextureName(cameraTexId)
            }
            val s = activeSession ?: return
            try {
                s.setCameraTextureName(cameraTexId)
                s.setDisplayGeometry(Surface.ROTATION_0, vpW, vpH)
                val frame = s.update()
                lastFrame.set(frame)
                frame.transformCoordinates2d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadCoords,
                    Coordinates2d.TEXTURE_NORMALIZED, quadTexCoords
                )
                val texBuf = ByteBuffer.allocateDirect(quadTexCoords.size * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer()
                    .also { it.put(quadTexCoords); it.position(0) }
                GLES20.glDisable(GLES20.GL_DEPTH_TEST)
                GLES20.glDepthMask(false)
                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
                GLES20.glUniform1i(texUniform, 0)
                GLES20.glEnableVertexAttribArray(posAttr)
                GLES20.glVertexAttribPointer(posAttr, 2, GLES20.GL_FLOAT, false, 0, coordsBuf)
                GLES20.glEnableVertexAttribArray(texAttr)
                GLES20.glVertexAttribPointer(texAttr, 2, GLES20.GL_FLOAT, false, 0, texBuf)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(posAttr)
                GLES20.glDisableVertexAttribArray(texAttr)
                GLES20.glDepthMask(true)
                GLES20.glEnable(GLES20.GL_DEPTH_TEST)
                (context as? MainActivity)?.runOnUiThread { onFrameCallback?.invoke(frame) }
            } catch (_: Exception) {}
        }

        private fun shader(type: Int, src: String) =
            GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }

        private fun buildProg(v: String, f: String) =
            GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, v))
                GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, f))
                GLES20.glLinkProgram(it)
            }
    }
}