package com.intech.camera.camera

import android.content.Context
import android.graphics.*
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.*
import com.intech.camera.constant.CameraFacing
import com.intech.camera.frame.CameraFrame
import com.intech.camera.frame.FrameAnalyzer
import com.intech.camera.render.CameraRenderer
import com.intech.camera.logger
import com.intech.camera.util.async
import com.intech.camera.util.uiThread
import java.io.File
import java.lang.IllegalStateException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BlendCamera(
    context: Context,
    lifecycle: LifecycleOwner? = null,
    private val processor: IEffectProcessor?,
    private val config: CameraConfig
) : ICamera, LifecycleEventObserver {

    companion object {
        // 等待上一次的销毁结束之后才执行本次的初始化
        @JvmStatic
        private var shareInstanceLatch: CountDownLatch? = null
    }

    private val TAG = "${BlendCamera::class.java.simpleName}(${hashCode()})"

    private val start = System.currentTimeMillis()

    // 组件状态管理
    private var mCameraQueue: CopyOnWriteArrayList<Runnable> = CopyOnWriteArrayList()
    private var mInitialState: AtomicInteger = AtomicInteger(0) // 0 未初始化；1 初始化完成；-1 已经销毁

    // 相机管理
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var mCameraProvider: ProcessCameraProvider? = null
    private var mFrameAnalyzer: FrameAnalyzer? = null
    private val mCameraLifecycle: LifecycleOwner = lifecycle ?: CameraLifecycle()
    private var mOnFrameListener: OnFrameAvailableListener? = null

    // 预览管理
    private var mTextureView: TextureView? = null
    private var mPreviewExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // 渲染管理
    private var mGlExecutor: Executor = Executors.newSingleThreadExecutor()
    private var mTexId: Int = 0
    private var mSurfaceTexture: SurfaceTexture? = null
    private var mSurface: Surface? = null
    private var mRenderer: CameraRenderer? = null
    private var mRenderEventListener: RenderEventListener? = null

    // 同步机制，确保当相机可用并且OpenGL贴图已经生成的情况下才开始
    private val countDownLatch = CountDownLatch(1)

    private var mCachedFrame: CameraFrame? = null

    private var mCameraFacing = config.facing

    private var mEnableBeauty = true

    private val mSurfaceProvider = Provider@{ request: SurfaceRequest ->
        mGlExecutor.execute {
            // 阻塞直到从OpenGl创建了TextureId
            countDownLatch.await()
            logger.v(TAG, "onSurfaceRequested : size = ${request.resolution}, texId = $mTexId")

            uiThread {
                if (mRenderer?.isReleased() != false) {
                    request.willNotProvideSurface()
                    logger.e(TAG, "onSurfaceRequested : renderer is release, skipped")
                    return@uiThread
                }
                val surface = mSurface ?: run {
                    val surfaceTexture = SurfaceTexture(mTexId)
                    surfaceTexture.setDefaultBufferSize(
                        request.resolution.width,
                        request.resolution.height
                    )
                    mSurfaceTexture = surfaceTexture
                    val newSurface = Surface(surfaceTexture)
                    mSurface = newSurface
                    logger.v(TAG, "onSurfaceRequested : create new surface, surface = ${newSurface.hashCode()}")
                    newSurface
                }
                request.provideSurface(surface, mGlExecutor) {
                    logger.v(TAG, "onSurfaceRequested : finished, ${it.surface.hashCode()} code = ${it.resultCode}")
                }
            }
        }
    }

    init {
        logger.i(TAG, "initialize :: config = $config, ts = ${System.currentTimeMillis() - start}")
        mRenderEventListener = RenderEventListener(this)
        mCameraLifecycle.lifecycle.addObserver(this)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context.applicationContext)

        cameraProviderFuture.addListener({
            if (mCameraLifecycle.lifecycle.currentState == Lifecycle.State.DESTROYED) {
                logger.e(TAG, "onCameraProviderReady :: camera lifecycle is destroyed, ignore start")
                return@addListener
            }

            async {
                shareInstanceLatch?.await(3, TimeUnit.SECONDS)

                uiThread {
                    mCameraProvider = cameraProviderFuture.get() as ProcessCameraProvider

                    // 相机创建成功后，执行缓存队列
                    mInitialState.set(1)
                    logger.d(TAG, "onCameraProviderReady :: ts = ${System.currentTimeMillis() - start}")
                    mCameraQueue.forEach { it.run() }

                }

            }

        }, ContextCompat.getMainExecutor(context.applicationContext))
    }

    private fun bindUseCases() {
        try {
            if (mCameraLifecycle.lifecycle.currentState == Lifecycle.State.DESTROYED) {
                logger.e(TAG, "bindUseCases :: camera lifecycle is destroyed, ignore start")
                return
            }

            val cameraProvider = mCameraProvider ?: throw IllegalStateException("mCameraProvider shouldn't be null")
            mCameraProvider = cameraProvider

            // 相机选择器，选择前置相机
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(mCameraFacing.value)
                .build()

            // UseCase1: 视频帧分析器（FaceUnity双输入1 -- 原始YUV数据）
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(config.width, config.height))
                .build()

            val frameAnalyzer = FrameAnalyzer(lensFacing = mCameraFacing.value)
            frameAnalyzer.addFrameConsumer { frame ->
                mRenderer?.drawFrame(frame)
            }
            mFrameAnalyzer = frameAnalyzer
            imageAnalysis.setAnalyzer(cameraExecutor, frameAnalyzer)

            // UseCase2: 视频帧Surface预览（FaceUnity双输入2 -- Surface纹理直接输入）
            val preview = Preview.Builder()
                .setTargetResolution(Size(config.width, config.height))
                .build()

            // 绑定相机的各种UseCase到生命周期（相当于启动相机）
            cameraProvider.unbindAll()
            val camera =
                cameraProvider.bindToLifecycle(mCameraLifecycle, cameraSelector, imageAnalysis, preview)

            preview.setSurfaceProvider(mGlExecutor, mSurfaceProvider)

            logger.v(TAG, "bindUseCases :: camera = ${camera.cameraInfo.javaClass.name}, facing = $mCameraFacing, ts = ${System.currentTimeMillis() - start}")
        } catch (e: Exception) {
            logger.e(TAG, "bindUseCases :: exp = ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    override fun processFrame(frame: CameraFrame): Boolean {
        logger.trace(TAG, "processFrame :: texId = ${frame.texId}")
        mSurfaceTexture?.updateTexImage()
        val isRendered = if (processor != null && !frame.isOverlay && mEnableBeauty) {
            processor.render(frame)
            true
        } else false
        val clone = frame.copy()
        mCachedFrame = clone
        mPreviewExecutor.execute {
            mOnFrameListener?.onFrameAvailable(clone)
        }
        return isRendered
    }

    /**
     * 绑定纹理ID为到相机的预览上，作为FaceUnity的第二个输入源
     */
    override fun bindTexture(texId: Int) {
        mTexId = texId
        countDownLatch.countDown()
        logger.d(TAG, "bindTexture :: ts = ${System.currentTimeMillis() - start}")
    }


    override fun setPreview(textureView: TextureView?) = post {
        if (mIsRecycled.get()) {
            logger.w(TAG, "setPreview :: camera is recycled")
            return@post
        }
        logger.d(TAG, "setPreview :: success = ${textureView != null}, isAvailable = ${textureView?.isAvailable}, ts = ${System.currentTimeMillis() - start}")
        mTextureView = textureView
        mTextureView?.surfaceTextureListener = PreviewSurfaceCallback()
        val texture = textureView?.surfaceTexture
        if (textureView != null && textureView.isAvailable && texture != null) {
            logger.d(TAG, "setPreview :: TextureView is already available")
            shareInstanceLatch = CountDownLatch(1)
            startGlRenderer(texture, textureView.width, textureView.height)
            createCamera()
        }
    }

    private fun startGlRenderer(surface: SurfaceTexture, width: Int, height: Int) {
        logger.v(TAG, "startGlRenderer :: width = $width, height = $height")
        try {
            mRenderer = CameraRenderer(config.width, config.height)
            mRenderer?.setOnRenderEventListener(mRenderEventListener)
            mRenderer?.start(surface, width, height)
        } catch (e: Exception) {
            logger.e(TAG, "startGlRenderer", e)
        }
    }

    private fun destroyGlRenderer() {
        logger.v(TAG, "destroyGlRenderer ::")
        mRenderer?.destroy()
        mRenderer = null
        processor?.reset()
    }

    override fun setOnFrameAvailableListener(listener: OnFrameAvailableListener?) {
        mOnFrameListener = listener
    }

    override fun setOverlay(bitmap: Bitmap?) {
        logger.d(TAG, "setOverlay :: bitmap = ${bitmap != null}")
        mFrameAnalyzer?.setOverlay(bitmap)
    }

    override fun setCameraFacing(facing: CameraFacing) = post {
        if (facing != mCameraFacing) {
            logger.d(TAG, "setCameraFacing :: targetLensFacing = $mCameraFacing")
            mCameraFacing = facing
            bindUseCases()
        }
    }

    override fun getCameraFacing(): CameraFacing {
        return mCameraFacing
    }

    override fun enableFaceBeauty(enable: Boolean) {
        logger.v(TAG, "enableBeauty :: enable = $enable")
        mEnableBeauty = enable
    }

    override fun startWhenPrepared() {
        if (mCameraLifecycle is CameraLifecycle) {
            logger.d(TAG, "startWhenPrepared ::")
            mCameraLifecycle.start()
        }
    }

    override fun pause() {
        if (mCameraLifecycle is CameraLifecycle) {
            logger.d(TAG, "pause ::")
            mCameraLifecycle.pause()
        }
    }

    override fun resume() {
        if (mCameraLifecycle is CameraLifecycle) {
            logger.d(TAG, "resume :: ts = ${System.currentTimeMillis() - start}")
            mCameraLifecycle.resume()
        }
    }

    override fun stop() {
        if (mCameraLifecycle is CameraLifecycle) {
            logger.d(TAG, "stop ::")
            mCameraLifecycle.destroy()
        }
    }

    private val mIsRecycled = AtomicBoolean(false)

    override fun isRecycled(): Boolean {
        return mIsRecycled.get()
    }

    fun destroyCamera() = post {
        logger.d(TAG, "destroyCamera ::")
        mCameraProvider?.unbindAll()
        mFrameAnalyzer?.destroy()
        mFrameAnalyzer = null
    }

    fun createCamera() = post {
        logger.d(TAG, "createCamera ::")
        bindUseCases()
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when(event) {
            Lifecycle.Event.ON_DESTROY -> recycle()
            // May do something else
            else -> {}
        }
    }

    private fun recycle() {
        logger.i(TAG, "recycle ::")
        if (mIsRecycled.get()) return
        mIsRecycled.set(true)
        mCameraQueue.clear()
        mInitialState.set(-1)
        mTextureView?.surfaceTextureListener = null
        processor?.release()
        mSurfaceTexture = null
        mSurface = null
    }

    override fun getProcessor(): IEffectProcessor? = processor

    override fun takePhoto(path: String, cb: (success: Boolean, width: Int, height: Int) -> Unit) {
        mRenderer?.getCurrentFrame { width, height, rotation, buffer ->
            if (buffer != null) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                val file = File(path)
                if (file.exists()) file.delete()
                val fos = file.outputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
                fos.flush()
                fos.close()
                bitmap.recycle()

                // 设置Exif信息
                val exif = ExifInterface(file.absolutePath)
                // 设置翻转角度，避免图像像素旋转的耗时
                when (rotation) {
                    90 -> {
                        if (mCameraFacing == CameraFacing.FRONT) {
                            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_TRANSPOSE.toString())
                        } else {
                            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                        }
                    }
                    180 -> exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_FLIP_VERTICAL.toString())
                    270 -> {
                        if (mCameraFacing == CameraFacing.FRONT) {
                            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_TRANSVERSE.toString())
                        } else {
                            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_270.toString())
                        }
                    }
                }

                exif.saveAttributes()
                logger.i(TAG, "takePhoto :: success, path = $path, rotation = $rotation")
                uiThread {
                    cb(true, width, height)
                }
            } else {
                logger.e(TAG, "takePhoto :: failed, buffer is null")
                uiThread {
                    cb(false, 0, 0)
                }
            }
        } ?: uiThread {
            logger.e(TAG, "takePhoto :: failed, mRenderer is null")
            cb(false, 0, 0)
        }
    }

    /**
     * 确保操作在相机可用时才会执行，如已经关闭则不会执行
     */
    private fun post(action: Runnable) {
        when (mInitialState.get()) {
            1 -> {
                action.run()
            }
            0 -> {
                mCameraQueue.add(action)
            }
            else -> {
                logger.w(TAG, "post :: camera is destroyed, action will not be execute")
            }
        }
    }

    inner class PreviewSurfaceCallback :
        TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            logger.d(TAG, "onSurfaceTextureAvailable :: width = $width, height = $height, ts = ${System.currentTimeMillis() - start}")
            shareInstanceLatch = CountDownLatch(1)
            startGlRenderer(surface, width, height)
            createCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            logger.d(TAG, "onSurfaceTextureSizeChanged :: width = $width, height = $height")
            mRenderer?.setViewPort(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            logger.d(TAG, "onSurfaceTextureDestroyed ::")
            destroyCamera()
            destroyGlRenderer()
            surface.release()
            shareInstanceLatch?.countDown()
            shareInstanceLatch = null
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            // Do Nothing
        }

    }

    inner class RenderEventListener(private val camera: ICamera) : CameraRenderer.OnRenderEventListener {

        override fun onTextureCreated(texId: Int) {
            logger.v(TAG, "onTextureCreated :: texId = $texId")
            camera.bindTexture(texId)
        }

        override fun onPreRender(frame: CameraFrame): CameraRenderer.RenderOutput? {
            logger.trace(TAG, "onPreRender :: texId = ${frame.texId}")
            val isRendered = camera.processFrame(frame)
            return if (isRendered) CameraRenderer.RenderOutput(frame.texId, frame.isOesTexture, frame.bytes) else null
        }
    }

    internal class CameraLifecycle : LifecycleOwner {
        private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)

        init {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }

        fun start() {
            try {
                if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            } catch (e: Exception) {
            }
        }

        fun pause() {
            try {
                if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
                lifecycleRegistry.currentState = Lifecycle.State.CREATED
            } catch (e: Exception) {
            }
        }

        fun resume() {
            try {
                if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            } catch (e: Exception) {
            }
        }

        fun destroy() {
            try {
                if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
                lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            } catch (e: Exception) {
            }
        }

        override fun getLifecycle(): Lifecycle = lifecycleRegistry
    }

}