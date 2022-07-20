package com.intech.camera.render.texture

import android.opengl.GLES20
import com.intech.camera.logger
import com.intech.camera.util.GlUtil
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 直接绘制Nv21格式的数据
 */
class Nv21Texture : BaseTexture(Nv21Texture.VERTEX_SHADER, Nv21Texture.FRAGMENT_SHADER) {

    companion object {
        val VERTEX_SHADER = """uniform mat4 uMVPMatrix;
attribute vec4 aPosition;
attribute vec4 aTextureCoord;
varying vec2 vTextureCoord;
void main() {
    gl_Position = uMVPMatrix * aPosition;
    vTextureCoord = aTextureCoord.xy;
}
    """.trimIndent()
        val FRAGMENT_SHADER = """precision mediump float;
varying vec2 vTextureCoord;
uniform sampler2D texture_y;
uniform sampler2D texture_uv;

void main() {
    float y, u, v;
    y = texture2D(texture_y, vTextureCoord).r;
    v = texture2D(texture_uv, vTextureCoord).g- 0.5;
    u = texture2D(texture_uv, vTextureCoord).a- 0.5;
    vec3 rgb;
    rgb.r = y + 1.370705 * v;
    rgb.g = y - 0.337633 * u - 0.698001 * v;
    rgb.b = y + 1.732446 * u;
    
    gl_FragColor = vec4(rgb, 1.0);
}
    """.trimIndent()

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
    private var sampler_y = 0
    private var sampler_uv = 0
    private var textureArray = IntArray(2)

    //YUV数据
    private var texWidth = 0
    private var texHeight = 0
    private var texRotation = 0
    private var yBuffer: ByteBuffer? = null
    private var uvBuffer: ByteBuffer? = null

    private var mDrawable2d: Drawable2d = Drawable2d(Drawable2d.Prefab.FULL_RECTANGLE)

    /**
     * 初始化参数，必需在onSurfaceChange中调用
     *
     * @param width     视窗的宽度
     * @param height    视窗的高度
     */
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
        textureArray = IntArray(2)
        //创建3个纹理
        GLES20.glGenTextures(2, textureArray, 0)

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
        logger.d(TAG, "constructor :: program = $mProgramHandle, textures = ${textureArray.contentToString()}")
    }

    private fun getLocations() {
        //获取顶点坐标字段
        vMatrix = GLES20.glGetUniformLocation(mProgramHandle, "uMVPMatrix")
        GlUtil.checkLocation(vMatrix, "$TAG.uMVPMatrix")

        vertexPos = GLES20.glGetAttribLocation(mProgramHandle, "aPosition")
        GlUtil.checkLocation(vertexPos, "$TAG.aPosition")

        fragmentPos = GLES20.glGetAttribLocation(mProgramHandle, "aTextureCoord")
        GlUtil.checkLocation(fragmentPos, "$TAG.aTextureCoord")

        sampler_y = GLES20.glGetUniformLocation(mProgramHandle, "texture_y")
        GlUtil.checkLocation(sampler_y, "$TAG.texture_y")

        sampler_uv = GLES20.glGetUniformLocation(mProgramHandle, "texture_uv")
        GlUtil.checkLocation(sampler_uv, "$TAG.texture_uv")

        GlUtil.checkGlError("$TAG.getLocations :: end")
    }

    private fun setFrame(width: Int, height: Int, rotation: Int, buffer: Buffer) {
        texWidth = width
        texHeight = height
        texRotation = rotation

        buffer.rewind()
        // Copy Y Buffer
        val yBufferSize = (width) * height
        val yBuffer = ByteBuffer.allocate(yBufferSize)
        System.arraycopy(buffer.array(), 0, yBuffer.array(), 0, width * height)
        this.yBuffer = yBuffer

        // Copy UV Buffer
        val uvBuffer = ByteBuffer.allocate(yBufferSize / 2)
        System.arraycopy(buffer.array(), width * height, uvBuffer.array(), 0, width * height / 2)
        this.uvBuffer = uvBuffer
    }

    private val mMvpMatrix = FloatArray(16)

    override fun draw(
        width: Int,
        height: Int,
        rotation: Int,
        buffer: Buffer,
        texId: Int,
        mvpMatrix: FloatArray
    ) {
        setFrame(width, height, rotation, buffer)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // 清空画布
        GLES20.glClearColor(0.2f, .2f, .2f, 1f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_STENCIL_BUFFER_BIT)

        GLES20.glUseProgram(mProgramHandle)
        GlUtil.checkGlError("useProgram")

        GLES20.glUniformMatrix4fv(vMatrix, 1, false, mvpMatrix, 0)
        GLES20.glEnableVertexAttribArray(vertexPos)
        GLES20.glVertexAttribPointer(
            vertexPos,
            COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            Drawable2d.VERTEXTURE_STRIDE,
            mDrawable2d.vertexArray
        )
        GlUtil.checkGlError("glVertexAttribPointer")

        GLES20.glEnableVertexAttribArray(fragmentPos)
        GLES20.glVertexAttribPointer(
            fragmentPos,
            COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            Drawable2d.TEXTURE_COORD_STRIDE,
            mDrawable2d.texCoordArray
        )

        //激活纹理0来绑定Y数据
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureArray[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_LUMINANCE,
            width,
            height,
            0,
            GLES20.GL_LUMINANCE,
            GLES20.GL_UNSIGNED_BYTE,
            yBuffer
        )
        GlUtil.checkGlError("glTexImg2D(Y)")

        //激活纹理1来绑定UV数据
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureArray[1])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_LUMINANCE_ALPHA,
            width / 2,
            height / 2,
            0,
            GLES20.GL_LUMINANCE_ALPHA,
            GLES20.GL_UNSIGNED_BYTE,
            uvBuffer
        )
        GlUtil.checkGlError("glTexImg2D(UV)")

        //给fragment_shader里面yuv变量设置值   0 1 2 标识纹理x
        GLES20.glUniform1i(sampler_y, 0)
        GLES20.glUniform1i(sampler_uv, 1)
        GlUtil.checkGlError("setTextureIds")

        //绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, mDrawable2d.vertexCount)
        GlUtil.checkGlError("drawArrays")

        // 回收
        GLES20.glDisableVertexAttribArray(fragmentPos)
        GLES20.glDisableVertexAttribArray(vertexPos)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glUseProgram(0)
        yBuffer?.clear()
        uvBuffer?.clear()
        yBuffer = null
        uvBuffer = null
        vertexBuffer.flip()
        textureBuffer.flip()


        logger.trace(TAG, "draw :: width = $width, height = $height, rotation = $rotation")
    }
}