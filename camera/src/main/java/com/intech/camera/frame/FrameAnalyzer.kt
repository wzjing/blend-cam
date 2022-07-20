package com.intech.camera.frame

import android.graphics.Bitmap
import android.graphics.ImageFormat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.intech.camera.constant.CameraFacing
import com.intech.camera.constant.PreviewFormat
import com.intech.camera.logger
import com.intech.camera.util.ImageConverter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 相机帧采集器
 */
class FrameAnalyzer(var lensFacing: Int) : ImageAnalysis.Analyzer {

    private val TAG = this::class.java.simpleName

    private var mOverlay: ByteArray? = null
    private var mOverlayWidth: Int = 0
    private var mOverlayHeight: Int = 0

    private val mFrameConsumers = CopyOnWriteArrayList<IFrameConsumer>()
    private var mFrameIndex: Int = 0

    fun setOverlay(bitmap: Bitmap?) {
        if (bitmap != null) {
            mOverlay = ImageConverter.bitmapToNv21(bitmap)
            mOverlayWidth = bitmap.width + bitmap.width % 8
            mOverlayHeight = bitmap.height
            logger.d(TAG, "setOverlay :: add overlay : format = ${bitmap.config}, buffer remain = ${mOverlay?.size}(${bitmap.width * bitmap.height * 4})")
            bitmap.recycle()
        } else {
            logger.d(TAG, "setOverlay :: remove overlay")
            mOverlay = null
            mOverlayWidth = 0
            mOverlayHeight = 0
        }
    }

    override fun analyze(image: ImageProxy) {
        val overlay: ByteArray? = mOverlay
        val rotation: Int
        val width: Int
        val height: Int
        val format: PreviewFormat

        val bytes: ByteArray = if (overlay == null) {
            format = PreviewFormat.NV21
            width = image.width
            height = image.height
            rotation = image.imageInfo.rotationDegrees
            logger.trace(TAG, "analyze :: CAMERA : width = $width, height = $height，rotation = $rotation, facing = ${lensFacing}, raw format = 0x${image.format.toString(16)}")

            when (image.format) {
                ImageFormat.NV21 -> {
                    // 对于NV21格式，可以直接输出
                    getAllBytes(image)
                }
                ImageFormat.YUV_420_888 -> {
                    // 此格式相当于NV12，需要手动反转UV为VU
                    val nv21Bytes = ByteArray(width * height * 3 / 2)
                    nv12ToNv21(image, nv21Bytes)
                    nv21Bytes
                }
                else -> {
                    // 目前不可能存在其他格式，如果存在则报错
                    throw RuntimeException("UnSupport Image Format 0x${image.format.toString(16)}")
                }
            }
        } else {
            format = PreviewFormat.NV21
            width = mOverlayWidth
            height = mOverlayHeight
            rotation = 0
            logger.trace(TAG, "analyze : OVERLAY : width = $mOverlayWidth, height = $mOverlayHeight")
            overlay
        }

        val frame = CameraFrame(
            index = mFrameIndex++,
            timestamp = System.currentTimeMillis(),
            width = width,
            height = height,
            rotation = rotation,
            facing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) CameraFacing.FRONT else CameraFacing.BACK,
            format = format,
            bytes = bytes,
            isOverlay = mOverlay != null
        )

        mFrameConsumers.forEach {
            it.onFrameGenerated(frame)
        }
        image.close()
    }

    fun addFrameConsumer(consumer: IFrameConsumer) {
        mFrameConsumers.add(consumer)
    }

    fun removeFrameConsumer(consumer: IFrameConsumer) {
        mFrameConsumers.remove(consumer)
    }

    fun removeAllConsumer() {
        mFrameConsumers.clear()
    }

    fun destroy() {
        removeAllConsumer()
        mOverlay = null
    }

    private fun getAllBytes(image: ImageProxy): ByteArray {
        // Y Buffer
        val yPlane = image.planes[0]
        val ySize = yPlane.buffer.remaining()
        val yBytes = ByteArray(ySize)
        yPlane.buffer.get(yBytes)

        // UV Buffer (Stored in two planes)
        val uvPlane1 = image.planes[1]
        val uvSize1 = uvPlane1.buffer.remaining()
        val uvBytes1 = ByteArray(uvSize1)
        uvPlane1.buffer.get(uvBytes1)

        val uvPlane2 = image.planes[2]
        val uvSize2 = uvPlane2.buffer.remaining()
        val uvBytes2 = ByteArray(uvSize2)
        uvPlane2.buffer.get(uvBytes2)

        return yBytes + uvBytes1 + uvBytes2
    }

    private fun nv12ToNv21(image: ImageProxy, yuvDataBuffer: ByteArray) {
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        val planes = image.planes
        val yDataBuffer = ByteArray(planes[0].rowStride)
        var channelOffset = 0
        var outputStride = 1
        for (i in planes.indices) {
            when (i) {
                0 -> {
                    channelOffset = 0
                    outputStride = 1
                }
                1 -> {
                    channelOffset = width * height + 1
                    outputStride = 2
                }
                2 -> {
                    channelOffset = width * height
                    outputStride = 2
                }
                else -> {
                }
            }
            val buffer = planes[i].buffer
            val rowStride = planes[i].rowStride
            val pixelStride = planes[i].pixelStride
            val shift = if (i == 0) 0 else 1
            val w = width shr shift
            val h = height shr shift
            buffer.position(rowStride * (crop.top shr shift) + pixelStride * (crop.left shr shift))
            for (row in 0 until h) {
                var length: Int
                if (pixelStride == 1 && outputStride == 1) {
                    length = w
                    buffer[yuvDataBuffer, channelOffset, length]
                    channelOffset += length
                } else {
                    length = (w - 1) * pixelStride + 1
                    buffer[yDataBuffer, 0, length]
                    for (col in 0 until w) {
                        yuvDataBuffer[channelOffset] = yDataBuffer[col * pixelStride]
                        channelOffset += outputStride
                    }
                }
                if (row < h - 1) {
                    buffer.position(buffer.position() + rowStride - length)
                }
            }
        }
    }

}