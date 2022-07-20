package com.intech.camera.render.texture

import android.graphics.Point
import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.intech.camera.logger
import com.intech.camera.util.GlUtil
import java.nio.Buffer
import java.nio.ByteBuffer

/**
 * 配合相芯绘制NV21格式的数据
 */
class OesTexture: BaseTexture(VERTEX_SHADER, FRAGMENT_SHADER) {

    companion object {
        const val VERTEX_SHADER = """
uniform mat4 uMVPMatrix;
attribute vec4 aPosition;
attribute vec2 aTextureCoord;
varying vec2 vTextureCoord;
void main() {
    gl_Position = uMVPMatrix * aPosition;
    vTextureCoord = aTextureCoord;
}
"""
        const val FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTextureCoord;
uniform samplerExternalOES sTexture;
void main() {
    gl_FragColor = texture2D(sTexture, vTextureCoord); 
}
"""
    }

    private val TAG = this::class.java.simpleName

    private var muMVPMatrixLoc = 0
    private var maPositionLoc = 0
    private var maTextureCoordLoc = 0


    private var mDrawable2d: Drawable2d = Drawable2d(Drawable2d.Prefab.FULL_RECTANGLE)

    init {
        getLocations()
        logger.d(TAG, "constructor :: program = $mProgramHandle")
    }

    private fun getLocations() {
        GlUtil.checkGlError("$TAG.createProgram")
        maPositionLoc = GLES20.glGetAttribLocation(mProgramHandle, "aPosition")
        GlUtil.checkLocation(mProgramHandle, "aPosition")
        maTextureCoordLoc = GLES20.glGetAttribLocation(mProgramHandle, "aTextureCoord")
        GlUtil.checkLocation(maTextureCoordLoc, "aTextureCoord")
        muMVPMatrixLoc = GLES20.glGetUniformLocation(mProgramHandle, "uMVPMatrix")
        GlUtil.checkLocation(muMVPMatrixLoc, "uMVPMatrix")
    }

    override fun draw(
        width: Int,
        height: Int,
        rotation: Int,
        buffer: Buffer,
        texId: Int,
        mvpMatrix: FloatArray
    ) {
        logger.trace(TAG, "draw :: width = $width, height = $height, rotation = $rotation, texId = $texId, mvpMat = ${mvpMatrix.contentToString()}\n")

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // 清空画布
        GLES20.glClearColor(0.2f, .2f, .2f, 1f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_STENCIL_BUFFER_BIT)

        // Select the program.
        GLES20.glUseProgram(mProgramHandle)
        GlUtil.checkGlError("glUseProgram")

        // Set the texture.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GlUtil.checkGlError("glActiveTexture")
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GlUtil.checkGlError("glBindTexture")

        // Copy the model / view / projection matrix over.
        GLES20.glUniformMatrix4fv(muMVPMatrixLoc, 1, false, mvpMatrix, 0)
        GlUtil.checkGlError("glUniformMatrix4fv : $muMVPMatrixLoc")

        // Enable the "aPosition" vertex attribute.
        GLES20.glEnableVertexAttribArray(maPositionLoc)
        GlUtil.checkGlError("glEnableVertexAttribArray maPositionLoc")

        // Connect vertexBuffer to "aPosition".
        GLES20.glVertexAttribPointer(
            maPositionLoc,
            Drawable2d.COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            Drawable2d.VERTEXTURE_STRIDE,
            mDrawable2d.vertexArray
        )
        GlUtil.checkGlError("glVertexAttribPointer")

        // Enable the "aTextureCoord" vertex attribute.
        GLES20.glEnableVertexAttribArray(maTextureCoordLoc)
        GlUtil.checkGlError("glEnableVertexAttribArray maTextureCoordLoc")

        // Connect texBuffer to "aTextureCoord".
        GLES20.glVertexAttribPointer(
            maTextureCoordLoc,
            2,
            GLES20.GL_FLOAT,
            false,
            Drawable2d.TEXTURE_COORD_STRIDE,
            mDrawable2d.texCoordArray
        )
        GlUtil.checkGlError("glVertexAttribPointer")

        // Draw the rect.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, mDrawable2d.vertexCount)
        GlUtil.checkGlError("glDrawArrays")

        // Done -- disable vertex array, texture, and program.
        GLES20.glDisableVertexAttribArray(maPositionLoc)
        GLES20.glDisableVertexAttribArray(maTextureCoordLoc)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glUseProgram(0)
    }

    private var mFrameBuffers: IntArray? = null
    private var mFrameBufferTextures: IntArray? = null

    private val FRAME_BUFFER_NUM = 1

    private var mFrameBufferShape = Point()

    fun initFrameBufferIfNeed(width: Int, height: Int) {
        var need = false
        if (mFrameBufferShape.x != width || mFrameBufferShape.y != height) {
            need = true
        }
        if (mFrameBuffers == null || mFrameBufferTextures == null) {
            need = true
        }
        if (need) {
            mFrameBuffers = IntArray(FRAME_BUFFER_NUM)
            mFrameBufferTextures = IntArray(FRAME_BUFFER_NUM)
            GLES20.glGenFramebuffers(FRAME_BUFFER_NUM, mFrameBuffers, 0)
            GLES20.glGenTextures(FRAME_BUFFER_NUM, mFrameBufferTextures, 0)
            bindFrameBuffer(mFrameBufferTextures!![0], mFrameBuffers!![0], width, height)
            mFrameBufferShape = Point(width, height)
        }

    }

    private fun bindFrameBuffer(textureId: Int, frameBuffer: Int, width: Int, height: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        );
        GLES20.glTexParameterf(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR.toFloat()
        );
        GLES20.glTexParameterf(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR.toFloat()
        );
        GLES20.glTexParameterf(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE.toFloat()
        );
        GLES20.glTexParameterf(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE.toFloat()
        );

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffer);
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, textureId, 0
        );

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    override fun drawOffScreen(
        width: Int,
        height: Int,
        rotation: Int,
        buffer: Buffer,
        texId: Int,
        mvpMatrix: FloatArray,
        byteBuffer: ByteBuffer
    ) {
        logger.v(TAG, "draw :: width = $width, height = $height, rotation = $rotation, texId = $texId, mvpMat = ${mvpMatrix.contentToString()}\n")

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        initFrameBufferIfNeed(width, height)

        GLES20.glViewport(0, 0, width, height)

        // Select the program.
        GLES20.glUseProgram(mProgramHandle)
        GlUtil.checkGlError("glUseProgram")

        // Set the texture.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GlUtil.checkGlError("glActiveTexture")
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GlUtil.checkGlError("glBindTexture")

        // Copy the model / view / projection matrix over.
        GLES20.glUniformMatrix4fv(muMVPMatrixLoc, 1, false, mvpMatrix, 0)
        GlUtil.checkGlError("glUniformMatrix4fv : $muMVPMatrixLoc")

        // Enable the "aPosition" vertex attribute.
        GLES20.glEnableVertexAttribArray(maPositionLoc)
        GlUtil.checkGlError("glEnableVertexAttribArray maPositionLoc")

        // Connect vertexBuffer to "aPosition".
        GLES20.glVertexAttribPointer(
            maPositionLoc,
            Drawable2d.COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            Drawable2d.VERTEXTURE_STRIDE,
            mDrawable2d.vertexArray
        )
        GlUtil.checkGlError("glVertexAttribPointer")

        // Enable the "aTextureCoord" vertex attribute.
        GLES20.glEnableVertexAttribArray(maTextureCoordLoc)
        GlUtil.checkGlError("glEnableVertexAttribArray maTextureCoordLoc")

        // Connect texBuffer to "aTextureCoord".
        GLES20.glVertexAttribPointer(
            maTextureCoordLoc,
            2,
            GLES20.GL_FLOAT,
            false,
            Drawable2d.TEXTURE_COORD_STRIDE,
            mDrawable2d.texCoordArray
        )
        GlUtil.checkGlError("glVertexAttribPointer")

        // Draw the rect.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, mDrawable2d.vertexCount)
        GlUtil.checkGlError("glDrawArrays")

        GLES20.glViewport(0, 0, width, height)

        GLES20.glReadPixels(
            0,
            0,
            width,
            height,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            byteBuffer
        )

        // Done -- disable vertex array, texture, and program.
        GLES20.glDisableVertexAttribArray(maPositionLoc)
        GLES20.glDisableVertexAttribArray(maTextureCoordLoc)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glUseProgram(0)
    }

}