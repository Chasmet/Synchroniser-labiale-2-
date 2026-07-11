package com.chasmet.lipsync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chasmet.lipsync.media.MediaFileUtils
import com.chasmet.lipsync.media.OutputAspectRatio
import com.chasmet.lipsync.media.ProcessingStage
import com.chasmet.lipsync.media.SelectedMedia

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LipSyncTheme {
                LipSyncApp(
                    onKeepScreenOn = { enabled ->
                        if (enabled) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun LipSyncApp(
    viewModel: LipSyncViewModel = viewModel(),
    onKeepScreenOn: (Boolean) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showRatioDialog by rememberSaveable { mutableStateOf(false) }
    var selectedRatioName by rememberSaveable {
        mutableStateOf(OutputAspectRatio.PORTRAIT_9_16.name)
    }
    val selectedRatio = OutputAspectRatio.valueOf(selectedRatioName)

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::selectVideo) }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::selectAudio) }

    DisposableEffect(state.isProcessing) {
        onKeepScreenOn(state.isProcessing)
        onDispose { onKeepScreenOn(false) }
    }

    Scaffold(containerColor = Color.Transparent) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            AppColors.Background,
                            AppColors.BackgroundMid,
                            AppColors.Background
                        )
                    )
                )
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Header()
                Spacer(Modifier.height(22.dp))
                PreviewCard(state)
                Spacer(Modifier.height(14.dp))

                FilePickerCard(
                    icon = Icons.Default.Movie,
                    title = "Ajouter une vidéo",
                    media = state.video,
                    accent = AppColors.Purple,
                    enabled = !state.isProcessing,
                    onClick = { videoPicker.launch(arrayOf("video/*")) }
                )
                Spacer(Modifier.height(10.dp))
                FilePickerCard(
                    icon = Icons.Default.AudioFile,
                    title = "Ajouter un MP3",
                    media = state.audio,
                    accent = AppColors.Blue,
                    enabled = !state.isProcessing,
                    onClick = { videoPicker.hashCode(); audioPicker.launch(arrayOf("audio/mpeg", "audio/*")) }
                )
                Spacer(Modifier.height(10.dp))

                AudioStartCard(
                    durationMs = state.audio?.durationMs ?: 0L,
                    valueSeconds = state.audioStartSeconds,
                    enabled = state.audio != null && !state.isProcessing,
                    onValueChange = viewModel::setAudioStart
                )
                Spacer(Modifier.height(10.dp))
                InformationCard()
                Spacer(Modifier.height(16.dp))

                AnimatedVisibility(state.isProcessing || state.status.stage == ProcessingStage.DONE) {
                    ProcessingCard(state)
                }
                if (state.isProcessing || state.status.stage == ProcessingStage.DONE) {
                    Spacer(Modifier.height(16.dp))
                }

                Button(
                    onClick = { showRatioDialog = true },
                    enabled = state.canStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Purple,
                        disabledContainerColor = AppColors.Disabled
                    )
                ) {
                    if (state.isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color.White,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Synchronisation en cours")
                    } else {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Choisir le format et lancer",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }

                state.resultUri?.let { uri ->
                    Spacer(Modifier.height(12.dp))
                    ResultActions(
                        onPreview = { openVideo(context, uri) },
                        onShare = { shareVideo(context, uri) }
                    )
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    "Maillage 478 points • garde dentaire personnelle • 100 % local",
                    color = AppColors.TextMuted,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    if (showRatioDialog) {
        RatioSelectionDialog(
            selected = selectedRatio,
            onSelect = { selectedRatioName = it.name },
            onDismiss = { showRatioDialog = false },
            onConfirm = {
                showRatioDialog = false
                viewModel.startSynchronization(selectedRatio)
            }
        )
    }

    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            icon = { Icon(Icons.Default.ErrorOutline, contentDescription = null) },
            title = { Text("Synchronisation interrompue") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::clearError) { Text("Fermer") }
            }
        )
    }
}

@Composable
private fun RatioSelectionDialog(
    selected: OutputAspectRatio,
    onSelect: (OutputAspectRatio) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF202020)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Proportions",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Choisis le format final avant la synchronisation labiale",
                    color = Color(0xFF9EA4AE),
                    fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RatioOption(
                        ratio = OutputAspectRatio.PORTRAIT_9_16,
                        selected = selected == OutputAspectRatio.PORTRAIT_9_16,
                        onClick = { onSelect(OutputAspectRatio.PORTRAIT_9_16) }
                    )
                    RatioOption(
                        ratio = OutputAspectRatio.LANDSCAPE_16_9,
                        selected = selected == OutputAspectRatio.LANDSCAPE_16_9,
                        onClick = { onSelect(OutputAspectRatio.LANDSCAPE_16_9) }
                    )
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    "La vidéo complète est conservée sans étirement. Des bandes peuvent apparaître si son format d'origine est différent.",
                    color = Color(0xFF9EA4AE),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Lancer en ${selected.label}",
                        fontWeight = FontWeight.Bold
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("Annuler", color = Color(0xFFB8BECA))
                }
            }
        }
    }
}

@Composable
private fun RatioOption(
    ratio: OutputAspectRatio,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .width(130.dp)
            .clip(shape)
            .background(if (selected) Color(0xFF2D2D2D) else Color.Transparent)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) Color.White else Color(0xFF54575D),
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(if (ratio == OutputAspectRatio.PORTRAIT_9_16) 48.dp else 92.dp)
                .height(if (ratio == OutputAspectRatio.PORTRAIT_9_16) 86.dp else 52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) Color(0xFFD5D5D5) else Color(0xFF45484E))
        )
        Spacer(Modifier.height(12.dp))
        Text(
            ratio.label,
            color = if (selected) Color.White else Color(0xFFA8ADB6),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            if (ratio == OutputAspectRatio.PORTRAIT_9_16) "Vertical" else "Horizontal",
            color = Color(0xFF8F96A3),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun Header() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(AppColors.Purple, AppColors.Blue))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "LipSync AI Studio",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 27.sp
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Synchronisation labiale générative hors ligne",
            color = AppColors.TextMuted,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun PreviewCard(state: LipSyncUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(AppColors.Card, AppColors.CardHighlight))),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (state.video == null) Icons.Default.Movie else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = AppColors.Blue,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = state.video?.displayName ?: "Aucune vidéo sélectionnée",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                state.video?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${MediaFileUtils.formatDuration(it.durationMs)} • ${MediaFileUtils.formatSize(it.sizeBytes)}",
                        color = AppColors.TextMuted,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilePickerCard(
    icon: ImageVector,
    title: String,
    media: SelectedMedia?,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    media?.displayName ?: "Touchez pour choisir un fichier",
                    color = AppColors.TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (media != null) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AppColors.Success)
            }
        }
    }
}

@Composable
private fun AudioStartCard(
    durationMs: Long,
    valueSeconds: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit
) {
    val maxSeconds = (durationMs / 1_000f - 1f).coerceAtLeast(1f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = AppColors.Blue)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Début de l'audio", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(formatSeconds(valueSeconds), color = AppColors.TextMuted, fontSize = 12.sp)
                }
            }
            Slider(
                value = valueSeconds.coerceIn(0f, maxSeconds),
                onValueChange = onValueChange,
                valueRange = 0f..maxSeconds,
                enabled = enabled
            )
        }
    }
}

@Composable
private fun InformationCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.ContentCut, contentDescription = null, tint = AppColors.Purple)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "Découpage intelligent : 30 secondes",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Le choix 9:16 ou 16:9 apparaît avant le lancement",
                    color = AppColors.TextMuted,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ProcessingCard(state: LipSyncUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.status.stage == ProcessingStage.DONE) {
                AppColors.Success.copy(alpha = 0.13f)
            } else AppColors.Card
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (state.status.stage == ProcessingStage.DONE) {
                        Icons.Default.CheckCircle
                    } else Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = if (state.status.stage == ProcessingStage.DONE) {
                        AppColors.Success
                    } else AppColors.Purple
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.status.stage.label, color = Color.White, fontWeight = FontWeight.Bold)
                    Text(state.status.message, color = AppColors.TextMuted, fontSize = 12.sp)
                }
                Text(
                    "${(state.status.progress * 100).toInt()} %",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { state.status.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
                color = if (state.status.stage == ProcessingStage.DONE) {
                    AppColors.Success
                } else AppColors.Purple,
                trackColor = AppColors.Disabled
            )
        }
    }
}

@Composable
private fun ResultActions(onPreview: () -> Unit, onShare: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onPreview,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Prévisualiser la vidéo")
        }
        Button(
            onClick = onShare,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.CardHighlight)
        ) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Partager")
        }
    }
}

private fun openVideo(context: android.content.Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Lire la vidéo"))
}

private fun shareVideo(context: android.content.Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Partager la vidéo"))
}

private fun formatSeconds(seconds: Float): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val minutes = total / 60
    val remaining = total % 60
    return "%02d:%02d".format(minutes, remaining)
}

private object AppColors {
    val Background = Color(0xFF050A14)
    val BackgroundMid = Color(0xFF091426)
    val Card = Color(0xFF101B2D)
    val CardHighlight = Color(0xFF172844)
    val Purple = Color(0xFF7C4DFF)
    val Blue = Color(0xFF2F80ED)
    val Success = Color(0xFF22C55E)
    val Disabled = Color(0xFF2B3545)
    val TextMuted = Color(0xFF9AA9BF)
}

@Composable
private fun LipSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = AppColors.Purple,
            secondary = AppColors.Blue,
            background = AppColors.Background,
            surface = AppColors.Card,
            onPrimary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = { Surface(color = Color.Transparent) { content() } }
    )
}
