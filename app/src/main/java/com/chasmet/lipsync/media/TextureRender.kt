package com.chasmet.lipsync.media

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Rend la vidéo décodée et fusionne la prédiction générative autour de la bouche.
 *
 * Les coordonnées du visage et de la bouche restent dans le repère logique de la
 * trame décodée. La matrice SurfaceTexture n'est utilisée que pour échantillonner
 * la texture vidéo. Cela évite les inversions verticales qui plaçaient parfois la
 * bouche générée sur le front.
 */
internal class TextureRender(
    private val outputWidth: Int,
    private val outputHeight: Int,
    rotationDegrees: Int,
    private val viewportX: Int,
    private val viewportY: Int,
    private val viewportWidth: Int,
    private val viewportHeight: Int
) {
    private val triangleVertices: FloatBuffer = ByteBuffer
        .allocateDirect(TRIANGLE_VERTICES_DATA.size * FLOAT_SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(TRIANGLE_VERTICES_DATA)
            position(0)
        }

    private val stMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var program = 0
    private var cropProgram = 0

    var textureId: Int = -1
        private set

    private var generatedTextureId = -1
    private var cropTextureId = -1
    private var cropFramebufferId = -1

    private val faceCropBuffer = ByteBuffer
        .allocateDirect(Wav2LipEngine.IMAGE_SIZE * Wav2LipEngine.IMAGE_SIZE * RGBA_CHANNELS)
        .order(ByteOrder.nativeOrder())

    private var aPositionHandle = 0
    private var aTextureHandle = 0
    private var uMvpMatrixHandle = 0
    private var uStMatrixHandle = 0
    private var uMouthCenterHandle = 0
    private var uMouthSizeHandle = 0
    private var uOpenHandle = 0
    private var uWidthHandle = 0
    private var uRoundHandle = 0
    private var uClosureHandle = 0
    private var uSourceTextureHandle = 0
    private var uGeneratedFaceHandle = 0
    private var uHasGeneratedHandle = 0
    private var uFaceCenterHandle = 0
    private var uFaceSizeHandle = 0
    private var uGeneratedStrengthHandle = 0

    private var cropPositionHandle = 0
    private var cropTextureHandle = 0
    private var cropSourceTextureHandle = 0
    private var cropCenterHandle = 0
    private var cropSizeHandle = 0
    private var cropStMatrixHandle = 0

    init {
        Matrix.setIdentityM(stMatrix, 0)
        Matrix.setRotateM(
            mvpMatrix,
            0,
            -rotationDegrees.toFloat(),
            0f,
            0f,
            1f
        )
    }

    fun surfaceCreated() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        check(program != 0) { "Impossible de créer le programme OpenGL" }
        cropProgram = createProgram(CROP_VERTEX_SHADER, CROP_FRAGMENT_SHADER)
        check(cropProgram != 0) { "Impossible de créer le lecteur de visage OpenGL" }

        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uStMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uMouthCenterHandle = GLES20.glGetUniformLocation(program, "uMouthCenter")
        uMouthSizeHandle = GLES20.glGetUniformLocation(program, "uMouthSize")
        uOpenHandle = GLES20.glGetUniformLocation(program, "uOpen")
        uWidthHandle = GLES20.glGetUniformLocation(program, "uWidth")
        uRoundHandle = GLES20.glGetUniformLocation(program, "uRound")
        uClosureHandle = GLES20.glGetUniformLocation(program, "uClosure")
        uSourceTextureHandle = GLES20.glGetUniformLocation(program, "sTexture")
        uGeneratedFaceHandle = GLES20.glGetUniformLocation(program, "uGeneratedFace")
        uHasGeneratedHandle = GLES20.glGetUniformLocation(program, "uHasGenerated")
        uFaceCenterHandle = GLES20.glGetUniformLocation(program, "uFaceCenter")
        uFaceSizeHandle = GLES20.glGetUniformLocation(program, "uFaceSize")
        uGeneratedStrengthHandle = GLES20.glGetUniformLocation(program, "uGeneratedStrength")

        cropPositionHandle = GLES20.glGetAttribLocation(cropProgram, "aPosition")
        cropTextureHandle = GLES20.glGetAttribLocation(cropProgram, "aTextureCoord")
        cropSourceTextureHandle = GLES20.glGetUniformLocation(cropProgram, "sTexture")
        cropCenterHandle = GLES20.glGetUniformLocation(cropProgram, "uCropCenter")
        cropSizeHandle = GLES20.glGetUniformLocation(cropProgram, "uCropSize")
        cropStMatrixHandle = GLES20.glGetUniformLocation(cropProgram, "uSTMatrix")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        checkGlError("glBindTexture")
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR.toFloat()
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

        generatedTextureId = createRgbaTexture(withStorage = true)
        cropTextureId = createRgbaTexture(withStorage = true)

        val framebuffers = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        cropFramebufferId = framebuffers[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, cropFramebufferId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            cropTextureId,
            0
        )
        check(
            GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) ==
                GLES20.GL_FRAMEBUFFER_COMPLETE
        ) { "Tampon de lecture du visage incomplet" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        checkGlError("création des textures génératives")
    }

    fun readFaceCrop(surfaceTexture: SurfaceTexture, face: FaceRegion): ByteBuffer {
        checkGlError("début lecture visage")
        surfaceTexture.getTransformMatrix(stMatrix)
        faceCropBuffer.clear()

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, cropFramebufferId)
        GLES20.glViewport(0, 0, Wav2LipEngine.IMAGE_SIZE, Wav2LipEngine.IMAGE_SIZE)
        GLES20.glUseProgram(cropProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(cropSourceTextureHandle, 0)
        GLES20.glUniformMatrix4fv(cropStMatrixHandle, 1, false, stMatrix, 0)
        GLES20.glUniform2f(cropCenterHandle, face.centerX, face.centerY)
        GLES20.glUniform2f(cropSizeHandle, face.width, face.height)
        setTriangleAttributes(cropPositionHandle, cropTextureHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glReadPixels(
            0,
            0,
            Wav2LipEngine.IMAGE_SIZE,
            Wav2LipEngine.IMAGE_SIZE,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            faceCropBuffer
        )
        GLES20.glFinish()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        faceCropBuffer.position(0)
        checkGlError("lecture visage")
        return faceCropBuffer.duplicate().apply { position(0) }
    }

    fun drawFrame(
        surfaceTexture: SurfaceTexture,
        mouth: MouthRegion,
        viseme: VisemeFrame,
        face: FaceRegion,
        generatedFace: GeneratedFace?,
        faceConfidence: Float
    ) {
        checkGlError("début drawFrame")
        surfaceTexture.getTransformMatrix(stMatrix)

        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glViewport(viewportX, viewportY, viewportWidth, viewportHeight)

        GLES20.glUseProgram(program)
        checkGlError("glUseProgram")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uSourceTextureHandle, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, generatedTextureId)
        GLES20.glUniform1i(uGeneratedFaceHandle, 1)
        if (generatedFace != null) {
            generatedFace.rgba.position(0)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                0,
                0,
                Wav2LipEngine.IMAGE_SIZE,
                Wav2LipEngine.IMAGE_SIZE,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                generatedFace.rgba
            )
        }

        setTriangleAttributes(aPositionHandle, aTextureHandle)
        GLES20.glUniformMatrix4fv(uMvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uStMatrixHandle, 1, false, stMatrix, 0)
        GLES20.glUniform2f(uMouthCenterHandle, mouth.centerX, mouth.centerY)
        GLES20.glUniform2f(uMouthSizeHandle, mouth.width, mouth.height)
        GLES20.glUniform1f(uOpenHandle, viseme.openness.coerceIn(0f, 1f))
        GLES20.glUniform1f(uWidthHandle, viseme.width.coerceIn(0f, 1f))
        GLES20.glUniform1f(uRoundHandle, viseme.roundness.coerceIn(0f, 1f))
        GLES20.glUniform1f(uClosureHandle, viseme.closure.coerceIn(0f, 1f))
        GLES20.glUniform2f(uFaceCenterHandle, face.centerX, face.centerY)
        GLES20.glUniform2f(uFaceSizeHandle, face.width, face.height)
        GLES20.glUniform1f(uHasGeneratedHandle, if (generatedFace != null) 1f else 0f)

        val generatedStrength = if (generatedFace == null) {
            0f
        } else {
            (0.90f + generatedFace.audioActivity * 0.06f) *
                ((faceConfidence - 0.20f) / 0.60f).coerceIn(0.72f, 1f)
        }
        GLES20.glUniform1f(uGeneratedStrengthHandle, generatedStrength.coerceIn(0f, 0.97f))

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays")
        GLES20.glFinish()
    }

    private fun setTriangleAttributes(positionHandle: Int, textureHandle: Int) {
        triangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(
            positionHandle,
            3,
            GLES20.GL_FLOAT,
            false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES,
            triangleVertices
        )
        GLES20.glEnableVertexAttribArray(positionHandle)

        triangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(
            textureHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES,
            triangleVertices
        )
        GLES20.glEnableVertexAttribArray(textureHandle)
    }

    private fun createRgbaTexture(withStorage: Boolean): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
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
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        if (withStorage) {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                Wav2LipEngine.IMAGE_SIZE,
                Wav2LipEngine.IMAGE_SIZE,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                null
            )
        }
        return textures[0]
    }

    fun release() {
        if (cropFramebufferId > 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(cropFramebufferId), 0)
            cropFramebufferId = -1
        }
        val textures = intArrayOf(textureId, generatedTextureId, cropTextureId)
            .filter { it > 0 }
            .toIntArray()
        if (textures.isNotEmpty()) GLES20.glDeleteTextures(textures.size, textures, 0)
        if (program > 0) GLES20.glDeleteProgram(program)
        if (cropProgram > 0) GLES20.glDeleteProgram(cropProgram)
        program = 0
        cropProgram = 0
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
        const val RGBA_CHANNELS = 4
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
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vLogicalCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vLogicalCoord = aTextureCoord.xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vLogicalCoord;
            uniform mat4 uSTMatrix;
            uniform samplerExternalOES sTexture;
            uniform sampler2D uGeneratedFace;
            uniform vec2 uMouthCenter;
            uniform vec2 uMouthSize;
            uniform vec2 uFaceCenter;
            uniform vec2 uFaceSize;
            uniform float uOpen;
            uniform float uWidth;
            uniform float uRound;
            uniform float uClosure;
            uniform float uHasGenerated;
            uniform float uGeneratedStrength;

            vec2 sourceCoord(vec2 logicalCoord) {
                return (uSTMatrix * vec4(logicalCoord, 0.0, 1.0)).xy;
            }

            void main() {
                vec2 safeMouthSize = max(uMouthSize, vec2(0.001));
                vec2 mouthDelta = vLogicalCoord - uMouthCenter;
                vec2 mouthEllipse = mouthDelta / safeMouthSize;
                float distanceFromMouth = dot(mouthEllipse, mouthEllipse);
                float influence = 1.0 - smoothstep(0.30, 1.00, distanceFromMouth);
                influence *= 1.0 - uHasGenerated;

                float activity = max(uOpen, max(uWidth * 0.64, uRound * 0.54));
                float silentClosure = 1.0 - smoothstep(0.025, 0.16, activity);
                float closure = max(uClosure, silentClosure * 0.76);
                float speechOpen = smoothstep(0.035, 0.82, uOpen);

                float horizontalTarget = 1.0 + uWidth * 0.20 - uRound * 0.13;
                horizontalTarget = mix(horizontalTarget, 0.94, closure * 0.36);
                float verticalTarget = mix(0.78, 1.38, speechOpen);
                verticalTarget = mix(verticalTarget, 0.69, closure);
                float horizontalScale = mix(1.0, horizontalTarget, influence);
                float verticalScale = mix(1.0, verticalTarget, influence);
                vec2 warpedLogical = uMouthCenter + vec2(
                    mouthDelta.x / horizontalScale,
                    mouthDelta.y / verticalScale
                );

                vec4 color = texture2D(sTexture, sourceCoord(warpedLogical));

                vec2 innerSize = vec2(
                    safeMouthSize.x * (0.30 - uRound * 0.035),
                    safeMouthSize.y * (0.10 + speechOpen * 0.24)
                );
                vec2 innerEllipse = mouthDelta / max(innerSize, vec2(0.001));
                float innerMask = 1.0 - smoothstep(
                    0.52,
                    1.0,
                    dot(innerEllipse, innerEllipse)
                );
                float naturalShade = innerMask * speechOpen * (1.0 - closure) * 0.10;
                naturalShade *= 1.0 - uHasGenerated;
                color.rgb *= 1.0 - naturalShade;

                vec2 safeFaceSize = max(uFaceSize, vec2(0.001));
                vec2 faceLocal = (vLogicalCoord - (uFaceCenter - safeFaceSize * 0.5)) /
                    safeFaceSize;
                float insideFace = step(0.0, faceLocal.x) * step(faceLocal.x, 1.0) *
                    step(0.0, faceLocal.y) * step(faceLocal.y, 1.0);
                vec4 generated = texture2D(
                    uGeneratedFace,
                    clamp(faceLocal, vec2(0.0), vec2(1.0))
                );

                // Le masque est défini autour de la bouche détectée, jamais autour
                // d'une hauteur fixe du visage. Il reste donc correct même si la
                // texture vidéo inverse l'axe vertical ou porte une rotation.
                vec2 coreSize = max(
                    vec2(safeMouthSize.x * 2.15, safeMouthSize.y * 2.45),
                    vec2(safeFaceSize.x * 0.16, safeFaceSize.y * 0.105)
                );
                vec2 coreDelta = mouthDelta / max(coreSize, vec2(0.001));
                float mouthCore = 1.0 - smoothstep(0.56, 1.02, dot(coreDelta, coreDelta));

                vec2 supportSize = max(
                    vec2(safeMouthSize.x * 3.00, safeMouthSize.y * 3.25),
                    vec2(safeFaceSize.x * 0.24, safeFaceSize.y * 0.16)
                );
                vec2 supportDelta = mouthDelta / max(supportSize, vec2(0.001));
                float mouthSupport = 1.0 - smoothstep(0.52, 1.04, dot(supportDelta, supportDelta));

                float generatedMask = uHasGenerated * uGeneratedStrength * insideFace *
                    max(mouthCore, mouthSupport * 0.52);
                color = mix(color, generated, clamp(generatedMask, 0.0, 0.97));

                gl_FragColor = color;
            }
        """

        const val CROP_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vLogicalCoord;
            void main() {
                gl_Position = aPosition;
                vLogicalCoord = aTextureCoord.xy;
            }
        """

        const val CROP_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vLogicalCoord;
            uniform mat4 uSTMatrix;
            uniform samplerExternalOES sTexture;
            uniform vec2 uCropCenter;
            uniform vec2 uCropSize;
            void main() {
                vec2 logicalCoord = uCropCenter +
                    (vLogicalCoord - vec2(0.5)) * uCropSize;
                vec2 sampledCoord = (uSTMatrix * vec4(logicalCoord, 0.0, 1.0)).xy;
                gl_FragColor = texture2D(
                    sTexture,
                    clamp(sampledCoord, vec2(0.0), vec2(1.0))
                );
            }
        """
    }
}
