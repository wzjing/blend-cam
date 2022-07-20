package com.intech.camera.util

import android.opengl.GLES20
import android.opengl.GLU
import android.opengl.Matrix
import com.intech.camera.logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Some OpenGL utility functions.
 */
object GlUtil {
    private val TAG = this::class.java.simpleName

    /**
     * Identity matrix for general use.  Don't modify or life will get weird.
     */
    private val IDENTITY_MATRIX: FloatArray = FloatArray(16)
    private const val SIZEOF_FLOAT = 4

    init {
        Matrix.setIdentityM(IDENTITY_MATRIX, 0)
    }

    /**
     * Creates a new program from the supplied vertex and fragment shaders.
     *
     * @return A handle to the program, or 0 on failure.
     */
    fun createProgram(vertexSource: String?, fragmentSource: String?): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) {
            logger.e(TAG, "createProgram :: compile vertex shader failed")
            return 0
        }
        val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (pixelShader == 0) {
            logger.e(TAG, "createProgram :: compile fragment shader failed")
            return 0
        }
        var program = GLES20.glCreateProgram()
        checkGlError("glCreateProgram")
        if (program == 0) {
            logger.e(TAG, "createProgram :: Could not create program")
        }
        GLES20.glAttachShader(program, vertexShader)
        checkGlError("glAttachShader")
        GLES20.glAttachShader(program, pixelShader)
        checkGlError("glAttachShader")
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            logger.e(TAG, "createProgram :: Could not link program, error = ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            program = 0
        }
        return program
    }

    /**
     * Compiles the provided shader source.
     *
     * @return A handle to the shader, or 0 on failure.
     */
    private fun loadShader(shaderType: Int, source: String?): Int {
        var shader = GLES20.glCreateShader(shaderType)
        checkGlError("glCreateShader type=$shaderType")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            logger.e(TAG, "loadShader :: Could not compile shader $shaderType:")
            logger.e(TAG, " " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            shader = 0
        }
        return shader
    }

    /**
     * Checks to see if a GLES error has been raised.
     */
    fun checkGlError(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            logger.e(TAG, "glError :: op = $op, error = 0x${error.toString(16)}(${GLU.gluErrorString(error)})")
        }
    }

    /**
     * Checks to see if the location we obtained is valid.  GLES returns -1 if a label
     * could not be found, but does not set the GL error.
     *
     *
     * Throws a RuntimeException if the location is invalid.
     */
    fun checkLocation(location: Int, label: String) {
        if (location < 0) {
            logger.e(TAG, "Unable to locate '$label'($location) in program, error = ${GLU.gluErrorString(GLES20.glGetError())}")
        }
    }

    /**
    * Allocates a direct float buffer, and populates it with the float array data.
    */
    fun createFloatBuffer(coords: FloatArray): FloatBuffer {
        // Allocate a direct ByteBuffer, using 4 bytes per float, and copy coords into it.
        val bb = ByteBuffer.allocateDirect(coords.size * SIZEOF_FLOAT)
        bb.order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(coords)
        fb.position(0)
        return fb
    }


    /**
     * Creates a texture object suitable for use with this program.
     *
     *
     * On exit, the texture will be bound.
     */
    fun createTextureObject(textureTarget: Int): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        checkGlError("glGenTextures")
        val texId = textures[0]
        GLES20.glBindTexture(textureTarget, texId)
        checkGlError("glBindTexture $texId")
        GLES20.glTexParameterf(textureTarget, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(textureTarget, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGlError("glTexParameter")
        return texId
    }

    fun deleteTextures(textureId: IntArray?) {
        if (textureId != null && textureId.size > 0) {
            GLES20.glDeleteTextures(textureId.size, textureId, 0)
        }
    }

    /**
     * 调整Texture的绘制以使其在viewPort上绘制不变形，类似于centerCrop，返回一个mvp矩阵
     */
    fun createMvpMatrix(
        viewWidth: Number,
        viewHeight: Number,
        textureWidth: Number,
        textureHeight: Number
    ): FloatArray {
        val scale = viewWidth.toFloat() * textureHeight.toFloat() / viewHeight.toFloat() / textureWidth.toFloat()
        val mvp = IDENTITY_MATRIX.copyOf(IDENTITY_MATRIX.size)
        // 初始化一个基础的矩阵，会清除原有的变换
//        Matrix.setIdentityM(mvp, 0)
        // 执行比例缩放
        Matrix.scaleM(mvp, 0, if (scale > 1) 1f else 1f / scale, if (scale > 1) scale else 1f, 1f)
        return mvp
    }

    private val eglErrors = mapOf(
        0x3000 to "EGL_SUCCESS",
        0x3001 to "EGL_NOT_INITIALIZED",
        0x3002 to "EGL_BAD_ACCESS",
        0x3003 to "EGL_BAD_ALLOC",
        0x3004 to "EGL_BAD_ATTRIBUTE",
        0x3005 to "EGL_BAD_CONFIG",
        0x3006 to "EGL_BAD_CONTEXT",
        0x3007 to "EGL_BAD_CURRENT_SURFACE",
        0x3008 to "EGL_BAD_DISPLAY",
        0x3009 to "EGL_BAD_MATCH",
        0x300A to "EGL_BAD_NATIVE_PIXMAP",
        0x300B to "EGL_BAD_NATIVE_WINDOW",
        0x300C to "EGL_BAD_PARAMETER",
        0x300D to "EGL_BAD_SURFACE",
        0x300E to "EGL_CONTEXT_LOST",
        0x3020 to "EGL_BUFFER_SIZE",
        0x3021 to "EGL_ALPHA_SIZE",
        0x3022 to "EGL_BLUE_SIZE",
        0x3023 to "EGL_GREEN_SIZE",
        0x3024 to "EGL_RED_SIZE",
        0x3025 to "EGL_DEPTH_SIZE",
        0x3026 to "EGL_STENCIL_SIZE",
        0x3027 to "EGL_CONFIG_CAVEAT",
        0x3028 to "EGL_CONFIG_ID",
        0x3029 to "EGL_LEVEL",
        0x302A to "EGL_MAX_PBUFFER_HEIGHT",
        0x302B to "EGL_MAX_PBUFFER_PIXELS",
        0x302C to "EGL_MAX_PBUFFER_WIDTH",
        0x302D to "EGL_NATIVE_RENDERABLE",
        0x302E to "EGL_NATIVE_VISUAL_ID",
        0x302F to "EGL_NATIVE_VISUAL_TYPE",
        0x3031 to "EGL_SAMPLES",
        0x3032 to "EGL_SAMPLE_BUFFERS",
        0x3033 to "EGL_SURFACE_TYPE",
        0x3034 to "EGL_TRANSPARENT_TYPE",
        0x3035 to "EGL_TRANSPARENT_BLUE_VALUE",
        0x3036 to "EGL_TRANSPARENT_GREEN_VALUE",
        0x3037 to "EGL_TRANSPARENT_RED_VALUE",
        0x3038 to "EGL_NONE",
        0x3039 to "EGL_BIND_TO_TEXTURE_RGB",
        0x303A to "EGL_BIND_TO_TEXTURE_RGBA",
        0x303B to "EGL_MIN_SWAP_INTERVAL",
        0x303C to "EGL_MAX_SWAP_INTERVAL",
        0x303D to "EGL_LUMINANCE_SIZE",
        0x303E to "EGL_ALPHA_MASK_SIZE",
        0x303F to "EGL_COLOR_BUFFER_TYPE",
        0x3040 to "EGL_RENDERABLE_TYPE",
        0x3041 to "EGL_MATCH_NATIVE_PIXMAP",
        0x3042 to "EGL_CONFORMANT",
        0x3050 to "EGL_SLOW_CONFIG",
        0x3051 to "EGL_NON_CONFORMANT_CONFIG",
        0x3052 to "EGL_TRANSPARENT_RGB",
        0x308E to "EGL_RGB_BUFFER",
        0x308F to "EGL_LUMINANCE_BUFFER",
        0x305C to "EGL_NO_TEXTURE",
        0x305D to "EGL_TEXTURE_RGB",
        0x305E to "EGL_TEXTURE_RGBA",
        0x305F to "EGL_TEXTURE_2D",
        0x0001 to "EGL_PBUFFER_BIT",
        0x0002 to "EGL_PIXMAP_BIT",
        0x0004 to "EGL_WINDOW_BIT",
        0x0020 to "EGL_VG_COLORSPACE_LINEAR_BIT",
        0x0040 to "EGL_VG_ALPHA_FORMAT_PRE_BIT",
        0x0200 to "EGL_MULTISAMPLE_RESOLVE_BOX_BIT",
        0x0400 to "EGL_SWAP_BEHAVIOR_PRESERVED_BIT",
        0x0001 to "EGL_OPENGL_ES_BIT",
        0x0002 to "EGL_OPENVG_BIT",
        0x0004 to "EGL_OPENGL_ES2_BIT",
        0x0008 to "EGL_OPENGL_BIT",
        0x3053 to "EGL_VENDOR",
        0x3054 to "EGL_VERSION",
        0x3055 to "EGL_EXTENSIONS",
        0x308D to "EGL_CLIENT_APIS",
        0x3056 to "EGL_HEIGHT",
        0x3057 to "EGL_WIDTH",
        0x3058 to "EGL_LARGEST_PBUFFER",
        0x3080 to "EGL_TEXTURE_FORMAT",
        0x3081 to "EGL_TEXTURE_TARGET",
        0x3082 to "EGL_MIPMAP_TEXTURE",
        0x3083 to "EGL_MIPMAP_LEVEL",
        0x3086 to "EGL_RENDER_BUFFER",
        0x3087 to "EGL_VG_COLORSPACE",
        0x3088 to "EGL_VG_ALPHA_FORMAT",
        0x3090 to "EGL_HORIZONTAL_RESOLUTION",
        0x3091 to "EGL_VERTICAL_RESOLUTION",
        0x3092 to "EGL_PIXEL_ASPECT_RATIO",
        0x3093 to "EGL_SWAP_BEHAVIOR",
        0x3099 to "EGL_MULTISAMPLE_RESOLVE",
        0x3084 to "EGL_BACK_BUFFER",
        0x3085 to "EGL_SINGLE_BUFFER",
        0x3089 to "EGL_VG_COLORSPACE_sRGB",
        0x308A to "EGL_VG_COLORSPACE_LINEAR",
        0x308B to "EGL_VG_ALPHA_FORMAT_NONPRE",
        0x308C to "EGL_VG_ALPHA_FORMAT_PRE",
        10000 to "EGL_DISPLAY_SCALING",
        0x3094 to "EGL_BUFFER_PRESERVED",
        0x3095 to "EGL_BUFFER_DESTROYED",
        0x3096 to "EGL_OPENVG_IMAGE",
        0x3097 to "EGL_CONTEXT_CLIENT_TYPE",
        0x3098 to "EGL_CONTEXT_CLIENT_VERSION",
        0x309A to "EGL_MULTISAMPLE_RESOLVE_DEFAULT",
        0x309B to "EGL_MULTISAMPLE_RESOLVE_BOX",
        0x30A0 to "EGL_OPENGL_ES_API",
        0x30A1 to "EGL_OPENVG_API",
        0x30A2 to "EGL_OPENGL_API",
        0x3059 to "EGL_DRAW",
        0x305A to "EGL_READ",
        0x305B to "EGL_CORE_NATIVE_ENGINE",
    )

    fun getEglError(code: Int): String = eglErrors[code] ?: "unknown"
}