package com.intech.camera.render.texture

import com.intech.camera.logger
import com.intech.camera.util.ShaderUtil
import java.nio.Buffer
import java.nio.ByteBuffer

abstract class BaseTexture(vertexShader: String, fragmentShader: String) {

    protected var mProgramHandle: Int = 0

    init {
        mProgramHandle = ShaderUtil.createProgram(vertexShader, fragmentShader)
        logger.v(this::class.java.simpleName, "constructor :: compiled shader($mProgramHandle)")
    }

    abstract fun draw(width: Int, height: Int, rotation: Int, buffer: Buffer, texId: Int, mvpMatrix: FloatArray)

    open fun drawOffScreen(width: Int, height: Int, rotation: Int, buffer: Buffer, texId: Int, mvpMatrix: FloatArray, byteBuffer: ByteBuffer) = Unit
}