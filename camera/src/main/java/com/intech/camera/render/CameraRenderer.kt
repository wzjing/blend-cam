package com.intech.camera.render

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.SystemClock
import com.intech.camera.constant.CameraFacing
import com.intech.camera.frame.CameraFrame
import com.intech.camera.constant.PreviewFormat
import com.intech.camera.logger
import com.intech.camera.render.texture.*
import com.intech.camera.util.GlUtil
import com.intech.camera.util.ImageConverter
import com.intech.camera.util.async
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class CameraRenderer(
    private val imageWidth: Int,
    private val imageHeight: Int,
    private var mCameraFacing: CameraFacing = CameraFacing.FRONT
    ) {
    private val TAG = this::class.java.simpleName

    private val isStarted = AtomicBoolean(false)
    private val isRelease = AtomicBoolean(false)
    private val renderLock = Object()

    private val simple2dTexture: BaseTexture by lazy { Simple2dTexture() }
    private val oesTexture: BaseTexture by lazy { OesTexture() }
    private val nv21Texture: BaseTexture by lazy { Nv21Texture() }
    private val rgbaTexture: BaseTexture by lazy { RgbaTexture() }
    private var mOriginalTexId: Int = 0

    private var mMvpMatrix: FloatArray = FloatArray(16) { 0f }

    private var mViewportWidth: Int = 0
    private var mViewportHeight: Int = 0

    /**
     * OpenGl渲染线程
     */
    private var mGLThread = CameraGlThread()

    private var mRenderEventListener: OnRenderEventListener? = null

    // 缓存处理后的Texture，用于拍照
    private var mRenderedTexId: Int = 0
    private var mTexRotation: Int = 0

    fun start(surface: SurfaceTexture, width: Int, height: Int) {
        if (isStarted.get()) {
            return
        }
        isStarted.set(true)
        mViewportWidth = width
        mViewportHeight = height
        mGLThread.setSurface(surface)
        mGLThread.start()
        setup()
        logger.d(TAG, "start :: width = $width, height = $height")
    }

    private fun setup() = mGLThread.post(CameraGlThread.GLEventType.Config, "CameraRenderer.setup") {
        logger.d(TAG, "setup :: width = $mViewportWidth, height = $mViewportHeight")
        GlUtil.checkGlError("$TAG.setUp")
        setViewPort(mViewportWidth, mViewportHeight)
        GlUtil.checkGlError("$TAG.setViewPort")
        mOriginalTexId = GlUtil.createTextureObject(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        GlUtil.checkGlError("$TAG.createOesTexture")
        mRenderEventListener?.onTextureCreated(mOriginalTexId)

        mMvpMatrix =
            GlUtil.createMvpMatrix(mViewportWidth.toFloat(), mViewportHeight.toFloat(), imageWidth.toFloat(), imageHeight.toFloat())
    }

    fun setViewPort(width: Int, height: Int) = mGLThread.post(CameraGlThread.GLEventType.Config, "CameraRenderer.setViewPort") {
        logger.d(TAG, "setViewPort :: width = $width, height = $height")
        GLES20.glViewport(0, 0, width, height)
    }

    fun setOnRenderEventListener(listener: OnRenderEventListener?) {
        if (mOriginalTexId > 0) {
            listener?.onTextureCreated(mOriginalTexId)
        }
        mRenderEventListener = listener
    }

    /**
     * 在[CameraGlThread]的[run]方法的阻塞队列内执行
     */
    fun drawFrame(frame: CameraFrame) = mGLThread.post(CameraGlThread.GLEventType.Render, "CameraRenderer.drawFrame") {
        if (isRelease.get()) return@post
        synchronized(renderLock) {
            GlUtil.checkGlError("$TAG.drawFrame :: start")
            val start = SystemClock.elapsedRealtime()
            frame.texId = mOriginalTexId

            if (isRelease.get()) return@synchronized
            val output = mRenderEventListener?.onPreRender(frame)

            mRenderedTexId = output?.outTexId ?: mOriginalTexId
            mTexRotation = frame.rotation
            mCameraFacing = frame.facing

            // 部分SDK可能会在内部修改viewPort，此处需要重制一下
            GLES20.glViewport(0, 0, mViewportWidth, mViewportHeight)


            val width = frame.width
            val height = frame.height
            val format = frame.format
            val rotation = frame.rotation
            val buffer = ByteBuffer.wrap(frame.bytes)


            val textureRender: BaseTexture? = when(format) {
                PreviewFormat.NV21 -> {
                    if (!frame.isOverlay) {
                        if (frame.isOesTexture) {
                            oesTexture
                        } else {
                            simple2dTexture
                        }
                    } else {
                        nv21Texture
                    }
                }
                PreviewFormat.ARGB32 -> {
                    rgbaTexture
                }
            }

            val mvpMatrix = mMvpMatrix.copyOf()

            when (rotation) {
                90, 180, 270 -> {
                    // 顺时针旋转补角，使其正向
                    Matrix.rotateM(mvpMatrix, 0, 360 - rotation.toFloat(), 0f, 0f, 1f)
                }
            }

            if (!frame.isOverlay && frame.facing == CameraFacing.FRONT) Matrix.rotateM(mvpMatrix, 0, 180f, 1f, 0f, 0f)

            if (isRelease.get()) return@synchronized
            textureRender?.draw(
                width,
                height,
                rotation,
                buffer,
                mRenderedTexId,
                mvpMatrix
            )
            if (frame.index == 0) {
                logger.v(TAG, "drawFrame[First] :: render = ${textureRender?.let { it::class.java.simpleName }}, width = $width, height = $height, rotation = $rotation, facing = ${frame.facing}, format = $format, newTex = $mRenderedTexId, isOesTexture = ${frame.isOesTexture}, isOverlay = ${frame.isOverlay}, cost = ${SystemClock.elapsedRealtime() - start}")
            } else {
                logger.trace(TAG, "drawFrame :: render = ${textureRender?.let { it::class.java.simpleName }}, width = $width, height = $height, rotation = $rotation, facing = ${frame.facing}, format = $format, newTex = $mRenderedTexId, isOesTexture = ${frame.isOesTexture}, isOverlay = ${frame.isOverlay}, cost = ${SystemClock.elapsedRealtime() - start}")
            }
        }

    }

    fun destroy(cb: (() -> Unit) = {}) = synchronized(renderLock) {
        if (isRelease.get()) {
            logger.w(TAG, "destroy :: already destroyed")
            return@synchronized
        }
        isRelease.set(true)
        mRenderEventListener = null
        mGLThread.release()
        cb()
        logger.i(TAG, "destroy ::")
    }

    fun isReleased() = isRelease.get()

    fun getCurrentFrame(cb: (width: Int, height: Int, rotation: Int, ByteBuffer?) -> Unit) {
        var outWidth = 0
        var outHeight = 0
        var rotation = mTexRotation
        val isOesTex = mRenderedTexId == mOriginalTexId
        mGLThread.post(CameraGlThread.GLEventType.Compute) {
            val buffer: ByteBuffer? = if (mRenderedTexId >= 0) {
                synchronized(renderLock) {
                    val desSize = ImageConverter.crop(imageWidth, imageHeight, mViewportWidth / mViewportHeight.toFloat())
                    outWidth = desSize.first
                    outHeight = desSize.second
                    rotation = if (isOesTex) 0 else mTexRotation
                    logger.i(TAG, "getCurrentFrame :: texId = $mRenderedTexId, rotation = $mTexRotation, srcSize = $imageWidth x $imageHeight, det = $outWidth x $outHeight, viewPort = $mViewportWidth x $mViewportHeight")
                    ImageConverter.readPixelBuffer(mRenderedTexId, isOesTex, isFrontCamera = mCameraFacing == CameraFacing.FRONT, imageWidth, imageHeight, mTexRotation, outWidth, outHeight)
                }
            } else {
                logger.e(TAG, "getCurrentFrame :: not rendered yet")
                null
            }
            async {
                val targetWidth: Int
                val targetHeight: Int
                if (!isOesTex && (rotation == 90 || rotation == 270)) {
                    targetWidth = outHeight
                    targetHeight = outWidth
                } else {
                    targetWidth = outWidth
                    targetHeight = outHeight
                }
                cb(targetWidth, targetHeight, rotation, buffer)
            }
        }
    }

    interface OnRenderEventListener {
        /**
         * OpenGL初始化时，创建一张空纹理，可用于绑定到相机的预览上直接从GPU接收数据
         */
        fun onTextureCreated(texId: Int) = Unit

        /**
         * 自定义前处理，接收一个预览的相机帧，可执行自定义渲染，双输入模式
         *
         * @param frame 输入相机YUV数据，NV21格式
         *
         * @return 输出渲染结果
         */
        fun onPreRender(frame: CameraFrame): RenderOutput?
    }

    @Suppress("ArrayInDataClass")
    data class RenderOutput(
        val outTexId: Int = -1,
        val isOesTexture: Boolean = false,
        val outBuffer: ByteArray = ByteArray(0)
    )
}