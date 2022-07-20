package com.intech.camera.util

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.Matrix
import com.intech.camera.logger
import com.intech.camera.render.texture.OesTexture
import com.intech.camera.render.texture.Simple2dTexture
import java.nio.ByteBuffer
import kotlin.math.floor

object ImageConverter {

    private val TAG = this::class.java.simpleName

    @JvmStatic
    fun bitmapToNv21(bitmap: Bitmap): ByteArray {
        val inputWidth = bitmap.width
        val inputHeight = bitmap.height
        val argb = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(argb, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        val outWidth = inputWidth + inputWidth % 8
        val nv21 = ByteArray(outWidth * inputHeight * 3 / 2)
        logger.v(TAG, "encodeNv21 :: nv21 = ${nv21.size}, argb = ${argb.size}, width = $inputWidth/$outWidth, height = $inputHeight, config = ${bitmap.byteCount}")
        encodeNv21(nv21, argb, inputWidth, inputHeight, outWidth)
        return nv21
    }

    private fun encodeNv21(nv21: ByteArray, argb: IntArray, width: Int, height: Int, outWidth: Int) {
        var yIndex = 0
        var uvIndex = outWidth * height
        var index = 0
        var color = 0
        for (j in 0 until height) {
            for (i in 0 until outWidth) {
                color = if (i < width) argb[index++] else color
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)

                val y = (( 66 * r + 129 * g +  25 * b + 128) shr 8) + 16

                nv21[yIndex++] = y.normalize().toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    try {
                        val u = ((-38 * r - 74  * g + 112 * b + 128) shr 8) + 128
                        val v = ((112 * r - 94  * g -  18 * b + 128) shr 8) + 128
                        nv21[uvIndex++] = v.normalize().toByte()
                        nv21[uvIndex++] = u.normalize().toByte()
                    } catch (e: Exception) {
                        logger.e(TAG, "encodeNv21 :: error, j = $j, i = $i, yIndex = $yIndex, uvIndex = $uvIndex")
                        throw e
                    }
                }
            }
        }
        logger.v(TAG, "encodeNv21 :: width = $width, height = $height, expected = ${width * height}/${width * height * 3 / 2}, actual = $yIndex/$uvIndex")
    }

    fun crop(srcWidth: Int, srcHeight: Int, targetRation: Float): Pair<Int, Int> {
        val srcRatio = srcWidth / srcHeight

        val width: Int
        val height: Int

        if (targetRation > srcRatio) {
            width = srcWidth
            height = (width / targetRation).toInt()
        } else {
            height = srcHeight
            width = (srcWidth * targetRation).toInt()
        }
        return width to height
    }

    /**
     * 将纹理的像素拷贝到ByteBuffer，用于生成Bitmap等
     */
    fun readPixelBuffer(textureId: Int, isOes: Boolean, isFrontCamera: Boolean, srcWidth: Int, srcHeight: Int, srcRotation: Int, width: Int, height: Int): ByteBuffer? {
        if (textureId == -1) {
            return null
        }


        val mCaptureBuffer = ByteBuffer.allocateDirect(height * height * 4)

        mCaptureBuffer.position(0)

        val mvpMatrix = FloatArray(16) { 0f }
        Matrix.setIdentityM(mvpMatrix, 0)

        val srcRatio = srcWidth / srcHeight.toFloat()
        val desRatio = width / height.toFloat()

        var xScale = 1.0f
        var yScale = 1.0f
        if (srcRatio < desRatio) {
            yScale = desRatio / srcRatio
            Matrix.scaleM(mvpMatrix, 0, xScale, yScale, 1f)
        } else {
            xScale = srcRatio / desRatio
            Matrix.scaleM(mvpMatrix, 0, xScale, yScale, 1f)
        }

        if (isOes) {
            Matrix.rotateM(mvpMatrix, 0, srcRotation.toFloat(), 0f, 0f, 1f)
            if (!isFrontCamera) Matrix.rotateM(mvpMatrix, 0, 180f, 1f, 0f, 0f)
            OesTexture().drawOffScreen(width, height, srcRotation, ByteBuffer.allocate(0), textureId, mvpMatrix, mCaptureBuffer)
        } else {

            val vPadding: Int
            val hPadding: Int
            val targetWidth: Int
            val targetHeight: Int
            if (srcRotation == 90 || srcRotation == 270) {
                hPadding = if (height < srcHeight) floor((srcHeight - height) / 2.0).toInt() else 0
                vPadding = if (width < srcWidth) floor((srcWidth - width) / 2.0).toInt() else 0
                targetWidth = height
                targetHeight = width
            } else {
                hPadding = if (width < srcWidth) floor((srcWidth - width) / 2.0).toInt() else 0
                vPadding = if (height < srcHeight) floor((srcHeight - height) / 2.0).toInt() else 0
                targetWidth = width
                targetHeight = height
            }

            readPixelBufferFromSimple2dTexture(textureId, srcWidth, srcHeight, targetWidth, targetHeight, hPadding,vPadding, mCaptureBuffer)
        }
        logger.v(TAG, "readPixelBuffer :: isOes = $isOes, input = $srcWidth x $srcHeight ($srcRotation), output = $width x $height srcRatio = $srcRatio, desRatio = $desRatio, scale = [$xScale, $yScale] mvp = ${mvpMatrix.contentToString()}")

        return mCaptureBuffer
    }

    private fun readPixelBufferFromSimple2dTexture(textureId: Int, srcWidth: Int, srcHeight: Int, width: Int, height: Int, hPadding: Int, vPadding: Int, target: ByteBuffer) {
        val frameBuffer = IntArray(1)
        GLES20.glGenFramebuffers(1, frameBuffer, 0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameterf(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameterf(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameterf(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE.toFloat()
        )
        GLES20.glTexParameterf(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE.toFloat()
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffer[0])
        GLES20.glViewport(0, 0, srcWidth, srcHeight)

        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            textureId,
            0
        )


        GLES20.glReadPixels(
            hPadding,
            vPadding,
            width,
            height,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            target
        )

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(1, frameBuffer, 0)
    }

    private fun Int.normalize() = if (this < 0) 0 else if(this > 255) 255 else this
}