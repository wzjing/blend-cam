package com.intech.camera.frame

/**
 * 见[FrameAnalyzer]
 */
fun interface IFrameConsumer {
    /**
     * 消费相机的原始帧数据，[frame]已经被转换成了标准的YUV格式
     */
    fun onFrameGenerated(frame: CameraFrame)
}