package com.intech.camera.render

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Build
import com.intech.camera.logger
import com.intech.camera.util.GlUtil
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class CameraGlThread : Thread() {

    private val TAG = this::class.java.simpleName

    companion object {
        const val PIXEL_BIT_WIDTH = 8
    }

    @Volatile
    private var isRelease: AtomicBoolean = AtomicBoolean(false)
    private var renderLock = Object()
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglConfig: EGLConfig
    private var eglSurface: EGLSurface? = null

    private val eventQueue = ArrayBlockingQueue<GLEvent>(1)

    init {
        name = "CameraGlThread"
        priority = MAX_PRIORITY
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2) { 0 }
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("EGL initialize error, code = ${EGL14.eglGetError().errorDetail()}")
        }

        val configAttrs = if (Build.VERSION.SDK_INT >= 26) {
            arrayOf(
                EGL14.EGL_ALPHA_SIZE, PIXEL_BIT_WIDTH,
                EGL14.EGL_BLUE_SIZE, PIXEL_BIT_WIDTH,
                EGL14.EGL_GREEN_SIZE, PIXEL_BIT_WIDTH,
                EGL14.EGL_RED_SIZE, PIXEL_BIT_WIDTH,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            )
        } else {
            arrayOf(
                EGL14.EGL_BUFFER_SIZE, 32,
                EGL14.EGL_ALPHA_SIZE, PIXEL_BIT_WIDTH,
                EGL14.EGL_BLUE_SIZE, PIXEL_BIT_WIDTH,
                EGL14.EGL_GREEN_SIZE, PIXEL_BIT_WIDTH,
                EGL14.EGL_RED_SIZE, PIXEL_BIT_WIDTH,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            )
        }.toIntArray()

        val numConfigs = IntArray(1) { 0 }
        val eglConfigs = Array<EGLConfig?>(1) { null }
        if (!EGL14.eglChooseConfig(eglDisplay, configAttrs, 0, eglConfigs, 0, eglConfigs.size, numConfigs, 0)) {
            throw RuntimeException("Choose EGL config failed, code = ${EGL14.eglGetError().errorDetail()}")
        }

        eglConfig = eglConfigs.first() ?: throw RuntimeException("EGL config is null, code = ${EGL14.eglGetError().errorDetail()}")

        val contextAttrs = arrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        ).toIntArray()

        eglContext =
            EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttrs, 0)


        if (eglConfigs == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("Unable to create EGLContext, code = ${EGL14.eglGetError().errorDetail()}")
        }

        checkEglError("init")
    }

    fun setSurface(texture: SurfaceTexture) {
        logger.i(TAG, "setTexture :: texture = $texture")
        val surfaceAttrs = arrayOf(EGL14.EGL_NONE).toIntArray()
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, texture, surfaceAttrs, 0)

        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("Unable to create EGLSurface :: eglSurface == $eglSurface, code = ${EGL14.eglGetError().errorDetail()}")
        }
        checkEglError("setTexture")
    }

    private fun renderFrame(transaction: () -> Unit) = synchronized(renderLock) {
        if (isRelease.get()) return@synchronized

        if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE && !EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            logger.e(TAG, "Unable to Render frame, set surface error, isRelease = ${isRelease.get()}, eglSurface = ${eglSurface != null}, code = ${EGL14.eglGetError().errorDetail()}")
        }

        transaction()

        if (isRelease.get()) return@synchronized
        if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE && !EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            logger.e(TAG, "Unable to Render frame, swap buffers failed, isRelease = ${isRelease.get()}, eglSurface = ${eglSurface != null}, code = ${EGL14.eglGetError().errorDetail()}")
        }
        checkEglError("renderFrame#after")
    }

    private var isDestroyed = false

    /**
     * 销毁GL环境，[run]方法结束后自动调用，禁止外部主动调用
     */
    private fun destroyGl() {
        synchronized(renderLock) {
            if (isDestroyed) {
                logger.w(TAG, "destroyGl :: already destroyed")
                return@synchronized
            }
            isDestroyed = true
            logger.i(TAG, "destroyGl ::")

            try {
                // 必须先解绑对象才能执行释放
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                if (eglSurface != null || eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglTerminate(eglDisplay)
                    eglDisplay = EGL14.EGL_NO_DISPLAY
                }
            } catch (e: Exception) {
                logger.e(TAG, "destroyGl ::", e)
            }

        }
    }

    override fun run() {
        // 循环读取队列事件
        while (!isRelease.get()) {
            val event = eventQueue.take()
            logger.trace(TAG, "run :: event  = ${event.type}")
            when (event.type) {
                GLEventType.Render -> renderFrame(event.transaction)
                GLEventType.Config, GLEventType.Compute -> {
                    if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                        throw RuntimeException("Unable to Render frame, op = ${event.type}, set surface error, code = ${EGL14.eglGetError().errorDetail()}")
                    }
                    event.transaction()
                }
                GLEventType.Destroy -> {
                    // Destroy情况忽略Surface问题
                    event.transaction()
                }
            }
        }
        destroyGl()
    }

    private fun enqueue(event: GLEvent) {
        eventQueue.offer(event)
    }

    /**
     * 释放OpenGL资源
     */
    fun release() = post(GLEventType.Destroy, "release") {
        if (isRelease.get()) {
            logger.w(TAG, "release :: already released")
        }
        isRelease.set(true)
        logger.i(TAG, "release ::")
        eventQueue.clear()
    }

    /**
     * 提供OpenGl环境，执行渲染操作
     */
    fun post(type: GLEventType, tag: String = "", transaction: () -> Unit) {
        enqueue(GLEvent(type, tag, transaction))
    }

    override fun interrupt() {
        super.interrupt()
        release()
    }

    private fun Int.errorDetail() = "$this(${GlUtil.getEglError(this)})"

    private fun checkEglError(tag: String) {
        val ret = EGL14.eglGetError()
        if (ret != EGL14.EGL_SUCCESS) {
            logger.e(TAG, "$tag :: error = ${ret.errorDetail()}")
        }
    }

    data class GLEvent(val type: GLEventType, val tag: String = "", val transaction: () -> Unit)

    enum class GLEventType {
        Config,
        Render,
        Compute,
        Destroy
    }
}