package com.saas.x11manager.embeddedvnc

import android.opengl.GLES20.GL_BLEND
import android.opengl.GLES20.GL_COLOR_BUFFER_BIT
import android.opengl.GLES20.GL_ONE_MINUS_SRC_ALPHA
import android.opengl.GLES20.GL_SRC_ALPHA
import android.opengl.GLES20.glBlendFunc
import android.opengl.GLES20.glClear
import android.opengl.GLES20.glClearColor
import android.opengl.GLES20.glDisable
import android.opengl.GLES20.glEnable
import android.opengl.GLES20.glViewport
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.gaurav.avnc.ui.vnc.gl.Cursor
import com.gaurav.avnc.ui.vnc.gl.Frame
import com.gaurav.avnc.ui.vnc.gl.Program
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** OpenGL host for AVNC's framebuffer/cursor textures, without AVNC Activity/UI. */
internal class EmbeddedVncRenderer(
    private val session: EmbeddedVncSession
) : GLSurfaceView.Renderer {
    private val projection = FloatArray(16)
    private lateinit var program: Program
    private lateinit var frame: Frame
    private lateinit var cursor: Cursor

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glClearColor(0f, 0f, 0f, 1f)
        frame = Frame()
        cursor = Cursor()
        program = Program()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glViewport(0, 0, width, height)
        session.frameState.setViewportSize(width.toFloat(), height.toFloat())
        session.frameState.setWindowSize(width.toFloat(), height.toFloat())
    }

    override fun onDrawFrame(gl: GL10?) {
        glClear(GL_COLOR_BUFFER_BIT)
        glDisable(GL_BLEND)

        val client = session.client ?: return
        if (!session.isScreenEnabled || !client.connected || client.frameBufferUpdatesPaused.get()) return

        val state = session.frameState.getSnapshot()
        if (state.vpWidth <= 0f || state.vpHeight <= 0f || state.fbWidth <= 0f || state.fbHeight <= 0f) return

        Matrix.setIdentityM(projection, 0)
        Matrix.orthoM(projection, 0, 0f, state.vpWidth, -state.vpHeight, 0f, -1f, 1f)
        Matrix.translateM(projection, 0, state.frameX, -state.frameY, 0f)
        Matrix.scaleM(projection, 0, state.scale, -state.scale, 1f)

        program.useProgram()
        program.setUniforms(projection)
        frame.updateFbSize(state.fbWidth, state.fbHeight)
        frame.bind(program)
        client.uploadFrameTexture()
        frame.draw()
        program.validate()

        val ci = client.cursorInfo
        if (ci.width > 0 && ci.height > 0) {
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            cursor.update(client.pointerX.toFloat(), client.pointerY.toFloat(), ci, frame)
            cursor.bind(program)
            client.uploadCursorTexture()
            cursor.draw()
            glDisable(GL_BLEND)
        }
    }
}
