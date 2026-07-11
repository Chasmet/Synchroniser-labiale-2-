package com.chasmet.lipsync

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chasmet.lipsync.media.AudioAacTranscoder
import com.chasmet.lipsync.media.AudioVisemeAnalyzer
import com.chasmet.lipsync.media.FaceTrackAnalyzer
import com.chasmet.lipsync.media.MediaFileUtils
import com.chasmet.lipsync.media.Mp4Assembler
import com.chasmet.lipsync.media.OutputAspectRatio
import com.chasmet.lipsync.media.ProcessingStage
import com.chasmet.lipsync.media.ProcessingStatus
import com.chasmet.lipsync.media.SelectedMedia
import com.chasmet.lipsync.media.VideoLipSyncProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.min

data class LipSyncUiState(
    val video: SelectedMedia? = null,
    val audio: SelectedMedia? = null,
    val audioStartSeconds: Float = 0f,
    val status: ProcessingStatus = ProcessingStatus(),
    val isProcessing: Boolean = false,
    val resultUri: Uri? = null,
    val errorMessage: String? = null
) {
    val canStart: Boolean
        get() = video != null && audio != null && !isProcessing
}

class LipSyncViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(LipSyncUiState())
    val uiState: StateFlow<LipSyncUiState> = _uiState.asStateFlow()
    private var processingJob: Job? = null

    fun selectVideo(uri: Uri) {
        inspectMedia(uri, isVideo = true)
    }

    fun selectAudio(uri: Uri) {
        inspectMedia(uri, isVideo = false)
    }

    fun setAudioStart(seconds: Float) {
        val maxStart = ((_uiState.value.audio?.durationMs ?: 0L) / 1_000f - 1f)
            .coerceAtLeast(0f)
        _uiState.update {
            it.copy(audioStartSeconds = seconds.coerceIn(0f, maxStart))
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun startSynchronization(outputAspectRatio: OutputAspectRatio) {
        if (processingJob?.isActive == true) return
        val current = _uiState.value
        val video = current.video ?: return
        val audio = current.audio ?: return
        val context = getApplication<Application>()

        processingJob = viewModelScope.launch {
            val temporaryFiles = mutableListOf<File>()
            try {
                _uiState.update {
                    it.copy(
                        isProcessing = true,
                        resultUri = null,
                        errorMessage = null,
                        status = ProcessingStatus(
                            ProcessingStage.PREPARING,
                            0.02f,
                            "Format ${outputAspectRatio.label} • copie des fichiers"
                        )
                    )
                }

                val resultUri = withContext(Dispatchers.IO) {
                    val videoFile = MediaFileUtils.copyToCache(context, video, "source_video_")
                        .also(temporaryFiles::add)
                    val audioFile = MediaFileUtils.copyToCache(context, audio, "source_audio_")
                        .also(temporaryFiles::add)

                    _uiState.update {
                        it.copy(
                            status = ProcessingStatus(
                                ProcessingStage.FACE_ANALYSIS,
                                0.08f,
                                "Recherche de la bouche sur plusieurs images"
                            )
                        )
                    }
                    val mouth = FaceTrackAnalyzer().analyze(videoFile)

                    val startUs = (current.audioStartSeconds * 1_000_000L).toLong()
                    _uiState.update {
                        it.copy(
                            status = ProcessingStatus(
                                ProcessingStage.AUDIO_ANALYSIS,
                                0.16f,
                                "Analyse par le modèle personnel v2"
                            )
                        )
                    }
                    val timeline = AudioVisemeAnalyzer(context).analyze(audioFile, startUs)
                    val videoDurationUs = video.durationMs * 1_000L
                    val usableDurationUs = min(videoDurationUs, timeline.durationUs)
                        .coerceAtLeast(100_000L)
                    val totalBlocks = ceil(usableDurationUs / 30_000_000.0)
                        .toInt()
                        .coerceAtLeast(1)

                    val videoOnly = File.createTempFile("lipsync_video_", ".mp4", context.cacheDir)
                        .also(temporaryFiles::add)
                    _uiState.update {
                        it.copy(
                            status = ProcessingStatus(
                                ProcessingStage.VIDEO_RENDER,
                                0.20f,
                                "${outputAspectRatio.label} • bloc 1 sur $totalBlocks",
                                1,
                                totalBlocks
                            )
                        )
                    }
                    VideoLipSyncProcessor().process(
                        inputVideo = videoFile,
                        outputVideoOnly = videoOnly,
                        timeline = timeline,
                        mouthRegion = mouth,
                        outputAspectRatio = outputAspectRatio
                    ) { localProgress, block, blocks ->
                        val globalProgress = 0.20f + localProgress * 0.58f
                        _uiState.update {
                            it.copy(
                                status = ProcessingStatus(
                                    ProcessingStage.VIDEO_RENDER,
                                    globalProgress,
                                    "${outputAspectRatio.label} • bloc $block sur $blocks",
                                    block,
                                    blocks
                                )
                            )
                        }
                    }

                    _uiState.update {
                        it.copy(
                            status = ProcessingStatus(
                                ProcessingStage.AUDIO_TRANSCODE,
                                0.81f,
                                "Conversion locale du MP3"
                            )
                        )
                    }
                    val audioM4a = File.createTempFile("lipsync_audio_", ".m4a", context.cacheDir)
                        .also(temporaryFiles::add)
                    AudioAacTranscoder().transcode(
                        inputAudio = audioFile,
                        outputM4a = audioM4a,
                        startUs = startUs,
                        maxDurationUs = usableDurationUs
                    )

                    _uiState.update {
                        it.copy(
                            status = ProcessingStatus(
                                ProcessingStage.ASSEMBLY,
                                0.90f,
                                "Vérification finale en ${outputAspectRatio.label}"
                            )
                        )
                    }
                    val finalFile = File.createTempFile("lipsync_final_", ".mp4", context.cacheDir)
                        .also(temporaryFiles::add)
                    Mp4Assembler().combine(
                        videoOnly = videoOnly,
                        audioM4a = audioM4a,
                        outputMp4 = finalFile,
                        maxDurationUs = usableDurationUs,
                        outputAspectRatio = outputAspectRatio
                    )

                    _uiState.update {
                        it.copy(
                            status = ProcessingStatus(
                                ProcessingStage.EXPORT,
                                0.97f,
                                "Enregistrement dans la galerie"
                            )
                        )
                    }
                    MediaFileUtils.saveVideoToGallery(context, finalFile)
                }

                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        resultUri = resultUri,
                        status = ProcessingStatus(
                            ProcessingStage.DONE,
                            1f,
                            "Vidéo ${outputAspectRatio.label} vérifiée et enregistrée dans Movies/LipSync AI"
                        )
                    )
                }
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        errorMessage = error.message
                            ?: "La synchronisation a échoué sur ce téléphone",
                        status = ProcessingStatus(
                            ProcessingStage.ERROR,
                            it.status.progress,
                            "Échec du traitement"
                        )
                    )
                }
            } finally {
                withContext(Dispatchers.IO) {
                    temporaryFiles.forEach { file -> runCatching { file.delete() } }
                }
            }
        }
    }

    private fun inspectMedia(uri: Uri, isVideo: Boolean) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    runCatching {
                        getApplication<Application>().contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    MediaFileUtils.inspect(getApplication<Application>(), uri)
                }
            }.onSuccess { media ->
                _uiState.update { state ->
                    if (isVideo) state.copy(video = media, resultUri = null)
                    else state.copy(
                        audio = media,
                        audioStartSeconds = 0f,
                        resultUri = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "Impossible de lire ce fichier")
                }
            }
        }
    }
}
