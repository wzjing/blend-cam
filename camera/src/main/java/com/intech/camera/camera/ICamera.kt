package com.intech.camera.camera

import android.graphics.Bitmap
import android.view.TextureView
import com.intech.camera.constant.CameraFacing
import com.intech.camera.frame.CameraFrame

interface ICamera {

    /**
     * 当相机可用时，立即启动相机（只有设置预览后才可以启动相机）
     */
    fun startWhenPrepared()

    /**
     * 暂停相机，仅用于自定义Lifecycle模式下
     */
    fun pause()

    /**
     * 恢复相机，仅用于自定义Lifecycle模式下
     */
    fun resume()

    /**
     * 停止相机，仅用于自定义Lifecycle模式下
     */
    fun stop()

    /**
     * 相机是否已经被回收
     */
    fun isRecycled(): Boolean

    /**
     * 处理视频帧，仅内部使用
     *
     * @return 是否执行了渲染
     */
    fun processFrame(frame: CameraFrame): Boolean

    /**
     * 结束相机预览帧（美颜之后的数据）
     */
    fun setOnFrameAvailableListener(listener: OnFrameAvailableListener?)

    /**
     * 设置用于预览的TextureView，启动相机前必须先设置预览
     */
    fun setPreview(textureView: TextureView?)

    /**
     * 绑定纹理ID，仅内部使用
     */
    fun bindTexture(texId: Int)

    /**
     * 设置水印
     */
    fun setOverlay(bitmap: Bitmap?)

    fun getProcessor(): IEffectProcessor?

    fun takePhoto(path: String, cb: (success: Boolean, width: Int, height: Int) -> Unit )

    fun startRecord(path: String) = Unit

    fun stopRecord() = Unit

    fun setCameraFacing(facing: CameraFacing) = Unit

    /**
     * 获取当前相机类型，前置或后置
     */
    fun getCameraFacing(): CameraFacing

    fun enableFaceBeauty(enable: Boolean)
}

fun interface OnFrameAvailableListener {
    fun onFrameAvailable(frame: CameraFrame)
}