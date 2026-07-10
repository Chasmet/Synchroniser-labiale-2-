package com.chasmet.lipsync.media

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal class TextureRender {
    private val triangleVertices: FloatBuffer = ByteBuffer
        .allocateDirect(TRIANGLE_VERTICES_DATA.size * FLOAT_SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(TRIANGLE_VERTICES_DATA)
            position(0)
        }

    private val stMatrix = FloatArray(16)
    private var program = 0
    var textureId: Int = -1
        private set

    private var aPositionHandle = 0
    private var aTextureHandle = 0
    private var uMvpMatrixHandle = 0
    private var uStMatrixHandle = 0
    private var uMouthCenterHandle = 0
    private var uMouthSizeHandle = 0
    private var uOpenHandle = 0
    private var uWidthHandle = 0
    private var uRoundHandle = 0
    private val mvpMatrix = FloatArray(16)

    init {
        Matrix.setIdentityM(stMatrix, 0)
        Matrix.setIdentityM(mvpMatrix, 0)
    }

    fun surfaceCreated() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        check(program != 0) { "Impossible de créer le programme OpenGL" }

        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uStMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uMouthCenterHandle = GLES20.glGetUniformLocation(program, "uMouthCenter")
        uMouthSizeHandle = GLES20.glGetUniformLocation(program, "uMouthSize")
        uOpenHandle = GLES20.glGetUniformLocation(program, "uOpen")
        uWidthHandle = GLES20.glGetUniformLocation(program, "uWidth")
        uRoundHandle = GLES20.glGetUniformLocation(program, "uRound")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        checkGlError("glBindTexture")
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_NEAREST.toFloat()
        )
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        checkGlError("glTexParameter")
    }

    fun drawFrame(surfaceTexture: SurfaceTexture, mouth: MouthRegion, viseme: VisemeFrame) {
        checkGlError("début drawFrame")
        surfaceTexture.getTransformMatrix(stMatrix)

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        checkGlError("glUseProgram")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        triangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(
            aPositionHandle,
            3,
            GLES20.GL_FLOAT,
            false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES,
            triangleVertices
        )
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        triangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(
            aTextureHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES,
            triangleVertices
        )
        GLES20.glEnableVertexAttribArray(aTextureHandle)

        GLES20.glUniformMatrix4fv(uMvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uStMatrixHandle, 1, false, stMatrix, 0)
        GLES20.glUniform2f(uMouthCenterHandle, mouth.centerX, mouth.centerY)
        GLES20.glUniform2f(uMouthSizeHandle, mouth.width, mouth.height)
        GLES20.glUniform1f(uOpenHandle, viseme.openness)
        GLES20.glUniform1f(uWidthHandle, viseme.width)
        GLES20.glUniform1f(uRoundHandle, viseme.roundness)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays")
        GLES20.glFinish()
    }

    fun checkGlError(operation: String) {
        var error = GLES20.glGetError()
        if (error == GLES20.GL_NO_ERROR) return
        val errors = StringBuilder()
        while (error != GLES20.GL_NO_ERROR) {
            if (errors.isNotEmpty()) errors.append(", ")
            errors.append("0x").append(Integer.toHexString(error))
            error = GLES20.glGetError()
        }
        error("$operation : erreur OpenGL $errors")
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val result = GLES20.glCreateProgram()
        checkGlError("glCreateProgram")
        if (result == 0) return 0
        GLES20.glAttachShader(result, vertexShader)
        checkGlError("glAttachShader vertex")
        GLES20.glAttachShader(result, pixelShader)
        checkGlError("glAttachShader fragment")
        GLES20.glLinkProgram(result)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(result)
            GLES20.glDeleteProgram(result)
            error("Échec du lien OpenGL : $log")
        }
        return result
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        val shader = GLES20.glCreateShader(shaderType)
        checkGlError("glCreateShader")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Échec de compilation OpenGL : $log")
        }
        return shader
    }

    private companion object {
        const val FLOAT_SIZE_BYTES = 4
        const val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES
        const val TRIANGLE_VERTICES_DATA_POS_OFFSET = 0
        const val TRIANGLE_VERTICES_DATA_UV_OFFSET = 3

        val TRIANGLE_VERTICES_DATA = floatArrayOf(
            -1.0f, -1.0f, 0f, 0f, 0f,
            1.0f, -1.0f, 0f, 1f, 0f,
            -1.0f, 1.0f, 0f, 0f, 1f,
            1.0f, 1.0f, 0f, 1f, 1f
        )

        const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            uniform vec2 uMouthCenter;
            uniform vec2 uMouthSize;
            uniform float uOpen;
            uniform float uWidth;
            uniform float uRound;

            void main() {
                vec2 delta = vTextureCoord - uMouthCenter;
                vec2 safeSize = max(uMouthSize, vec2(0.001));
                vec2 ellipse = delta / safeSize;
                float distanceFromMouth = dot(ellipse, ellipse);
                float influence = 1.0 - smoothstep(0.50, 1.12, distanceFromMouth);

                float horizontalScale = 1.0 + influence * (uWidth * 0.18 - uRound * 0.12);
                float verticalScale = 1.0 + influence * uOpen * 0.62;
                vec2 warped = uMouthCenter + vec2(
                    delta.x / horizontalScale,
                    delta.y / verticalScale
                );

                vec4 color = texture2D(sTexture, warped);

                vec2 innerScale = vec2(
                    safeSize.x * (0.43 - uRound * 0.08),
                    safeSize.y * (0.18 + uOpen * 0.46)
                );
                vec2 innerEllipse = delta / max(innerScale, vec2(0.001));
                float innerMask = (1.0 - smoothstep(0.72, 1.0, dot(innerEllipse, innerEllipse)))
                    * influence * uOpen;
                vec3 mouthShadow = vec3(0.10, 0.015, 0.025);
                color.rgb = mix(color.rgb, mouthShadow, innerMask * 0.46);

                float teethBand = 1.0 - smoothstep(
                    0.06,
                    0.18,
                    abs(delta.y + safeSize.y * 0.08)
                );
                float teethWidth = 1.0 - smoothstep(
                    safeSize.x * 0.25,
                    safeSize.x * 0.47,
                    abs(delta.x)
                );
                float teethMask = teethBand * teethWidth * influence
                    * uWidth * (1.0 - uRound) * uOpen * 0.38;
                color.rgb = mix(color.rgb, vec3(0.93, 0.90, 0.84), teethMask);

                gl_FragColor = color;
            }
        """
    }
}
