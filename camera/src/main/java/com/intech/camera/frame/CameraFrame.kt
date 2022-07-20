package com.intech.camera.frame

import com.intech.camera.constant.CameraFacing
import com.intech.camera.constant.PreviewFormat

open class CameraFrame(
    val index: Int = 0,
    val timestamp: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    var bytes: ByteArray = ByteArray(0),
    var rotation: Int = 0,
    var facing: CameraFacing = CameraFacing.FRONT,
    val format: PreviewFormat = PreviewFormat.NV21,
    var texId: Int = 0,
    var isOesTexture: Boolean = true,
    var isOverlay: Boolean = false
) {

    fun isFirstFrame(): Boolean {
        return index == 0
    }

    fun copy(): CameraFrame {
        return CameraFrame(index, timestamp, width, height, if (bytes.isNotEmpty()) bytes.copyOf() else ByteArray(0), rotation, facing, format, texId, isOesTexture, isOverlay)
    }

    override fun toString(): String {
        return "VideoFrame(index = $index, ts = $timestamp, size = ${width}x$height, rotation = $rotation, format = $format, texId = $texId, isOesTexture = $isOesTexture, isOverlay = $isOverlay)"
    }
}