package com.chasmet.lipsync.media

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface

internal class OutputSurface(
    outputWidth: Int,
    outputHeight: Int,
    rotationDegrees: Int
) : SurfaceTexture.OnFrameAvailableListener {
    private val frameSyncObject = Object()
    private var frameAvailable = false
    private val textureRender = TextureRender(outputWidth, outputHeight, rotationDegrees)
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

    fun drawImage(mouth: MouthRegion, viseme: VisemeFrame) {
        textureRender.drawFrame(surfaceTexture, mouth, viseme)
    }

    fun release() {
        surface.release()
        surfaceTexture.release()
    }
}
