package com.chasmet.lipsync.media

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface

internal class OutputSurface(
    outputWidth: Int,
    outputHeight: Int,
    rotationDegrees: Int,
    viewportX: Int,
    viewportY: Int,
    viewportWidth: Int,
    viewportHeight: Int
) : SurfaceTexture.OnFrameAvailableListener {
    private val frameSyncObject = Object()
    private var frameAvailable = false
    private val generatedFaceCorrector = GeneratedFaceRgbaCorrector()
    private val textureRender = TextureRender(
        outputWidth = outputWidth,
        outputHeight = outputHeight,
        rotationDegrees = rotationDegrees,
        viewportX = viewportX,
        viewportY = viewportY,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight
    )
    private val surfaceTexture: SurfaceTexture
    val surface: Surface

    init {
        textureRender.surfaceCreated()
        surfaceTexture = SurfaceTexture(textureRender.textureId)
        surfaceTexture.setOnFrameAvailableListener(this, Handler(Looper.getMainLooper()))
        surface = Surface(surfaceTexture)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        synchronized(frameSyncObject) {
            frameAvailable = true
            frameSyncObject.notifyAll()
        }
    }

    fun awaitNewImage() {
        val timeoutMs = 5_000L
        synchronized(frameSyncObject) {
            while (!frameAvailable) {
                try {
                    frameSyncObject.wait(timeoutMs)
                    if (!frameAvailable) error("Délai dépassé pendant le décodage vidéo")
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw RuntimeException(interrupted)
                }
            }
            frameAvailable = false
        }
        textureRender.checkGlError("avant updateTexImage")
        surfaceTexture.updateTexImage()
    }

    fun readFaceCrop(face: FaceRegion) = textureRender.readFaceCrop(surfaceTexture, face)

    fun drawImage(
        mouth: MouthRegion,
        viseme: VisemeFrame,
        face: FaceRegion,
        generatedFace: GeneratedFace?,
        faceConfidence: Float
    ) {
        val safeGeneratedFace = generatedFace
            ?.takeIf { MouthPlacementGuard.isSafe(mouth, face) }
            ?.let { generatedFaceCorrector.apply(it, viseme) }
        textureRender.drawFrame(
            surfaceTexture = surfaceTexture,
            mouth = mouth,
            viseme = viseme,
            face = face,
            generatedFace = safeGeneratedFace,
            faceConfidence = faceConfidence * MouthPlacementGuard.confidence(mouth, face)
        )
    }

    fun release() {
        runCatching { textureRender.release() }
        surface.release()
        surfaceTexture.release()
    }
}
