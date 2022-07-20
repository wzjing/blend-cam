package com.intech.camera.util

import android.opengl.EGL14
import android.opengl.GLES20
import com.intech.camera.logger

object ShaderUtil {
    private val TAG = this::class.java.simpleName

    private fun loadShader(shaderType: Int, source: String?): Int {
        // create a vertex shader type (GLES20.GL_VERTEX_SHADER)
        // or a fragment shader type (GLES20.GL_FRAGMENT_SHADER)
        var shader = GLES20.glCreateShader(shaderType)
        if (shader != 0) {
            //添加代码到shader
            GLES20.glShaderSource(shader, source)
            //编译shader
            GLES20.glCompileShader(shader)
            val compile = IntArray(1)
            //检测是否编译成功
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compile, 0)
            if (compile[0] != GLES20.GL_TRUE) {
                logger.e(TAG, "loadShader :: shader compile error " + (compile[0].toString() + ", type = ") + "$shaderType " + GLES20.glGetError())
                GLES20.glDeleteShader(shader)
                shader = 0
            }
        } else {
            logger.e(TAG, "loadShader :: error, code = ${EGL14.eglGetError()}")
            EGL14.EGL_SUCCESS
        }
        return shader
    }

    fun createProgram(vertexSource: String?, fragmentSource: String?): Int {
        //获取vertex shader
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        GlUtil.checkGlError("$TAG.createProgram :: compile vertex shader")
        if (vertexShader == 0) {
            logger.e(TAG, "createProgram :: vertex shader compile failed")
            return 0
        }
        //获取fragment shader
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        GlUtil.checkGlError("$TAG.createProgram :: compile fragmetn shader")
        if (fragmentShader == 0) {
            logger.e(TAG, "createProgram :: fragment shader compile failed")
            return 0
        }
        //创建一个空的渲染程序
        var program = GLES20.glCreateProgram()
        GlUtil.checkGlError("$TAG.createProgram :: start")
        if (program != 0) {
            //添加vertexShader到渲染程序
            GLES20.glAttachShader(program, vertexShader)
            GlUtil.checkGlError("$TAG.attachVertexShader")
            //添加fragmentShader到渲染程序
            GLES20.glAttachShader(program, fragmentShader)
            GlUtil.checkGlError("$TAG.attachFragmentShader")
            //关联为可执行渲染程序
            GLES20.glLinkProgram(program)
            GlUtil.checkGlError("$TAG.linkProgram")
            val linkStatus = IntArray(1)
            //检测是否关联成功
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                logger.e(TAG, "createProgram :: link program error, ${GLES20.glGetShaderInfoLog(program)}")
                GLES20.glDeleteProgram(program)
                program = 0
            }
            logger.i(TAG, "createProgram :: success = ${GLES20.glIsProgram(program)}")
        } else {
            logger.e(TAG, "createProgram :: failed to create program, error = ${EGL14.eglGetError()}")
        }
        GlUtil.checkGlError("$TAG.createProgram :: end")
        return program
    }
}