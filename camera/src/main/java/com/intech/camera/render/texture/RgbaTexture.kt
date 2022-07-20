package com.intech.camera.render.texture

import android.opengl.GLES20
import com.intech.camera.logger
import com.intech.camera.util.GlUtil
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 绘制RGBA格式的像素数据
 */
class RgbaTexture : BaseTexture(VERTEX_SHADER, FRAGMENT_SHADER) {

    companion object {
        const val VERTEX_SHADER = """
uniform mat4 vMatrix;
attribute vec4 vertex_Pos;
attribute vec2 fragment_Pos;
varying vec2 v_texCoord;
void main() {
    v_texCoord = fragment_Pos;
    gl_Position = vMatrix * vertex_Pos;
}
    """
        const val FRAGMENT_SHADER = """
precision mediump float;
varying vec2 v_texCoord;
uniform sampler2D texture_rgba;

void main() {
    gl_FragColor = texture2D(texture_rgba, v_texCoord);
}
    """

        //顶点坐标
        var vertexData = floatArrayOf( // in counterclockwise order:
            -1f, -1f, 0.0f,  // bottom left
            1f, -1f, 0.0f,  // bottom right
            -1f, 1f, 0.0f,  // top left
            1f, 1f, 0.0f
        )

        //纹理坐标
        var textureData = floatArrayOf( // in counterclockwise order:
            0f, 1f, 0.0f,  // bottom left
            1f, 1f, 0.0f,  // bottom right
            0f, 0f, 0.0f,  // top left
            1f, 0f, 0.0f
        )

        //每一次取点的时候取几个点
        const val COORDS_PER_VERTEX = 3
    }

    private val TAG = this::class.java.simpleName
    private val vertexCount = vertexData.size / COORDS_PER_VERTEX

    //每一次取的总的点 大小
    private val vertexStride = COORDS_PER_VERTEX * 4 // 4 bytes per vertex

    //位置
    private val vertexBuffer: FloatBuffer

    //纹理
    private val textureBuffer: FloatBuffer

    private var vMatrix = 0

    //顶点位置
    private var vertexPos = 0

    //纹理位置
    private var fragmentPos = 0

    //shader  yuv变量
    private var sampler_rgba = 0
    private var textureArray = IntArray(1)

    //YUV数据
    private var texWidth = 0
    private var texHeight = 0
    private var texRotation = 0
    private var rgbaBuffer: ByteBuffer? = null

    init {
        vertexBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertexData)
        vertexBuffer.position(0)
        textureBuffer = ByteBuffer.allocateDirect(textureData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(textureData)
        textureBuffer.position(0)

        getLocations()
        //创建3个纹理
        GLES20.glGenTextures(1, textureArray, 0)

        //绑定纹理
        for (id in textureArray) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            //环绕（超出纹理坐标范围）  （s==x t==y GL_REPEAT 重复）
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_REPEAT
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_REPEAT
            )
            //过滤（纹理像素映射到坐标点）  （缩小、放大：GL_LINEAR线性）
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
            )
        }
        logger.d(TAG, "constructor :: program = $mProgramHandle, textures = ${textureArray.joinToString { it.toString() }}")
    }

    private fun getLocations() {
        //获取顶点坐标字段
        vMatrix = GLES20.glGetUniformLocation(mProgramHandle, "vMatrix")
        GlUtil.checkLocation(vMatrix, "$TAG.vMatrix")
        vertexPos = GLES20.glGetAttribLocation(mProgramHandle, "vertex_Pos")
        GlUtil.checkLocation(vertexPos, "$TAG.vertex_Pos")
        //获取纹理坐标字段
        fragmentPos = GLES20.glGetAttribLocation(mProgramHandle, "fragment_Pos")
        GlUtil.checkLocation(fragmentPos, "$TAG.fragment_Pos")
        //获取yuv字段
        sampler_rgba = GLES20.glGetUniformLocation(mProgramHandle, "texture_rgba")
        GlUtil.checkLocation(sampler_rgba, "$TAG.texture_rgba")
    }

    private fun setFrame(width: Int, height: Int, rotation: Int, buffer: Buffer) {
        this.texWidth = width
        this.texHeight = height
        this.texRotation = rotation
        val localBuffer = ByteBuffer.allocate(width * height * 4)
        System.arraycopy(buffer.array(), 0, localBuffer.array(), 0, width * height * 4)
        this.rgbaBuffer = localBuffer
//        logger.v(TAG, "setFrame :: width =  $width, height = $height")
    }

    private val mvpMatrix = FloatArray(16)

    override fun draw(
        width: Int,
        height: Int,
        rotation: Int,
        buffer: Buffer,
        texId: Int,
        mvpMatrix: FloatArray
    ) {

        setFrame(width, height, rotation, buffer)

        if (texWidth > 0 && texHeight > 0) {

            // 开启ALPHA通道支持
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glEnable(GLES20.GL_BLEND)

            GLES20.glUseProgram(mProgramHandle)

//            Matrix.setIdentityM(mvpMatrix, 0)
//            val viewPortRatio = viewPortWidth / viewPortHeight.toFloat()
//            val texRatio =
//                if (texRotation == 270 || texRotation == 90) texHeight / texWidth.toFloat()
//                else texWidth / texHeight.toFloat()
//
//            // 调整图片比例，CropCenter模式
//            if (texRatio > viewPortRatio) {
//                // 图片比视窗更宽
//                val extendWidth = texRatio / viewPortRatio
//                Matrix.scaleM(mvpMatrix, 0, extendWidth, 1f, 1f)
//            } else {
//                // 图片比视窗更窄
//                val extendHeight = viewPortRatio / texRatio
//                Matrix.scaleM(mvpMatrix, 0, 1f, extendHeight, 1f)
//            }
//
//            when(texRotation) {
//                270 -> {
//                    // 旋转90度
//                    Matrix.rotateM(mvpMatrix, 0, 90f, 0f, 0f, 1f)
//                    // 水印本地预览不需要镜像
//                }
//                else -> {
//                    // 水印本地预览不需要镜像
//                }
//            }

            logger.trace(TAG, "draw :: width = $width, height = $height, rotation = $rotation")
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            // 清空画布
            GLES20.glClearColor(0.2f, .2f, .2f, 1f)
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_STENCIL_BUFFER_BIT)

            GLES20.glUniformMatrix4fv(vMatrix, 1, false, mvpMatrix, 0)
            GLES20.glEnableVertexAttribArray(vertexPos)
            GLES20.glVertexAttribPointer(
                vertexPos,
                COORDS_PER_VERTEX,
                GLES20.GL_FLOAT,
                false,
                vertexStride,
                vertexBuffer
            )
            GLES20.glEnableVertexAttribArray(fragmentPos)
            GLES20.glVertexAttribPointer(
                fragmentPos,
                COORDS_PER_VERTEX,
                GLES20.GL_FLOAT,
                false,
                vertexStride,
                textureBuffer
            )

            //激活纹理0来绑定RGBA数据
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureArray[0])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                texWidth,
                texHeight,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                rgbaBuffer
            )

            //给fragment_shader里面yuv变量设置值   0 1 2 标识纹理x
            GLES20.glUniform1i(sampler_rgba, 0)

            //绘制
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, vertexCount)

            // 回收
            GLES20.glDisableVertexAttribArray(fragmentPos)
            GLES20.glDisableVertexAttribArray(vertexPos)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GLES20.glDeleteProgram(0)
            GLES20.glDisable(GLES20.GL_BLEND)
            rgbaBuffer?.clear()
            rgbaBuffer = null
        } else {
            logger.e(TAG, "draw :: error, no frame")
        }
    }
}