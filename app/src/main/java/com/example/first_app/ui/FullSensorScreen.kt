package com.example.first_app.ui

import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.TextureView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.first_app.CameraActions
import com.example.first_app.CameraUiState
import com.example.first_app.R
import com.example.first_app.camera.CameraInfo
import com.example.first_app.camera.SavedVideo
import com.example.first_app.camera.VideoSizeOption
import com.example.first_app.camera.friendlyName
import com.example.first_app.camera.ratioLabel
import com.example.first_app.camera.thumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun FullSensorScreen(state: CameraUiState, actions: CameraActions) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        when {
            !state.hasCameraPermission -> PermissionPrompt(actions::requestPermissions)
            state.cameras.isEmpty() -> CenteredText(state.status ?: "Reading camera capabilities…")
            else -> CameraContent(state, actions)
        }
    }
}

@Composable
private fun CameraContent(state: CameraUiState, actions: CameraActions) {
    val info = state.camera ?: return
    val option = state.option ?: return
    val recording = state.recordingSince != null
    var showSizes by remember { mutableStateOf(false) }
    val cameraName = state.cameras.friendlyName(state.selectedCamera)

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            CameraPreview(
                aspectRatio = info.uprightRatio(option.size),
                onAvailable = actions::onTextureAvailable,
                onDestroyed = actions::onTextureDestroyed,
            )
            state.recordingSince?.let { since ->
                RecordingTimer(since, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
            }
        }

        ControlPanel(
            state = state,
            info = info,
            option = option,
            cameraName = cameraName,
            recording = recording,
            actions = actions,
            onShowSizes = { showSizes = true },
        )
    }

    if (showSizes) {
        SizePickerSheet(
            info = info,
            cameraName = cameraName,
            selected = state.selectedSize,
            onSelect = actions::selectSize,
            onDismiss = { showSizes = false },
        )
    }
    state.viewingVideos?.let { VideoViewer(it, onDelete = actions::deleteVideo, onClose = actions::closeVideo) }
}

@Composable
private fun CameraPreview(
    aspectRatio: Float,
    onAvailable: (SurfaceTexture) -> Unit,
    onDestroyed: () -> Unit,
) {
    val available by rememberUpdatedState(onAvailable)
    val destroyed by rememberUpdatedState(onDestroyed)
    AndroidView(
        modifier = Modifier.aspectRatio(aspectRatio),
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) =
                        available(surface)

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        destroyed()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
            }
        },
    )
}

@Composable
private fun RecordingTimer(since: Long, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(since) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val seconds = ((now - since) / 1000).coerceAtLeast(0)
    Row(
        modifier
            .background(RecordRed, CircleShape)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(Color.White, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            "%02d:%02d".format(seconds / 60, seconds % 60),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun ControlPanel(
    state: CameraUiState,
    info: CameraInfo,
    option: VideoSizeOption,
    cameraName: String,
    recording: Boolean,
    actions: CameraActions,
    onShowSizes: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Pill(
            "Lens correction",
            icon = R.drawable.ic_crop_free,
            selected = !state.disableDistortionCorrection,
            enabled = !recording && info.distortionCorrectionModes.isNotEmpty(),
            onClick = { actions.setDisableDistortionCorrection(!state.disableDistortionCorrection) },
        )

        SpecCard(info, option, cameraName, enabled = !recording, onClick = onShowSizes)

        Text(
            state.status.orEmpty(),
            color = TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val buttonSize = ((maxWidth - BUTTON_GAP * (BOTTOM_BUTTONS + 1)) / BOTTOM_BUTTONS)
                .coerceIn(MIN_BUTTON, MAX_BUTTON)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                SquareButton(buttonSize, onClick = onShowSizes, enabled = !recording, contentDescription = "Video size") {
                    Text(option.label, color = TextPrimary, fontSize = (buttonSize.value * 0.21f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("${option.fps} fps", color = TextSecondary, fontSize = (buttonSize.value * 0.155f).sp, maxLines = 1)
                }
                SquareButton(
                    buttonSize,
                    onClick = { actions.setRecordAudio(!state.recordAudio) },
                    enabled = !recording,
                    contentDescription = if (state.recordAudio) "Mute audio" else "Unmute audio",
                ) {
                    Icon(
                        painterResource(if (state.recordAudio) R.drawable.ic_mic else R.drawable.ic_mic_off),
                        contentDescription = null,
                        tint = if (state.recordAudio) TextPrimary else RecordRed,
                        modifier = Modifier.size(buttonSize * 0.4f),
                    )
                }
                RecordButton(
                    size = buttonSize,
                    recording = recording,
                    enabled = !state.busy && option.recordable,
                    onClick = actions::toggleRecording,
                )
                SquareButton(
                    buttonSize,
                    onClick = { actions.selectCamera((state.selectedCamera + 1) % state.cameras.size) },
                    enabled = !recording && state.cameras.size > 1,
                    contentDescription = "Switch camera",
                ) {
                    Icon(
                        painterResource(R.drawable.ic_switch_camera),
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(buttonSize * 0.4f),
                    )
                }
                ViewButton(buttonSize, video = state.lastVideo, enabled = !recording, onClick = actions::viewVideos)
            }
        }
    }
}

private const val BOTTOM_BUTTONS = 5
private val BUTTON_GAP = 12.dp
private val MIN_BUTTON = 48.dp
private val MAX_BUTTON = 72.dp

private fun buttonShape(size: Dp) = RoundedCornerShape(size / 4)

@Composable
private fun SpecCard(info: CameraInfo, option: VideoSizeOption, cameraName: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardColor)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            val encoder = option.encoder
            Stat(option.label, "${option.size.width}×${option.size.height}", Modifier.weight(1f))
            StatDivider()
            Stat(ratioLabel(option.ratio), if (option.coversFullSensor) "full sensor" else "aspect", Modifier.weight(1f))
            StatDivider()
            Stat("${option.fps}", "fps", Modifier.weight(1f))
            StatDivider()
            Stat(
                encoder?.let { "${it.bitrate / 1_000_000}" } ?: "—",
                encoder?.let { "Mbps ${it.label}" } ?: "no encoder",
                Modifier.weight(1f),
                valueColor = if (encoder != null) TextPrimary else Warning,
            )
        }
        Box(
            Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(DividerColor),
        )
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(cameraName, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            val sensorMp = info.activeArray.width() * info.activeArray.height() / 1_000_000f
            Text(
                " · %.1f MP sensor".format(sensorMp),
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painterResource(R.drawable.ic_tune),
                contentDescription = "Video settings",
                tint = TextSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun StatDivider() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight(0.6f)
            .background(DividerColor),
    )
}

@Composable
private fun SquareButton(
    size: Dp,
    onClick: () -> Unit,
    contentDescription: String,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(buttonShape(size))
            .background(PillColor)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

@Composable
private fun ViewButton(size: Dp, video: SavedVideo?, enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumbnail by produceState<ImageBitmap?>(null, video?.uri) {
        value = video?.let { withContext(Dispatchers.IO) { it.thumbnail(context) }?.asImageBitmap() }
    }
    val shape = buttonShape(size)
    Box(
        Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(PillColor)
            .border(1.5.dp, if (thumbnail != null) TextPrimary else Color.Transparent, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "View recording" },
        contentAlignment = Alignment.Center,
    ) {
        thumbnail?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier
                .size(size * 0.4f)
                .background(Color(0x66000000), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_play), contentDescription = null, tint = TextPrimary, modifier = Modifier.size(size * 0.26f))
        }
    }
}

@Composable
private fun RecordButton(size: Dp, recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val idle = size - 14.dp
    val stop = size * 0.4f
    val innerSize by animateDpAsState(if (recording) stop else idle, label = "recordSize")
    val corner by animateDpAsState(if (recording) stop / 4 else idle / 2, label = "recordCorner")
    Box(
        Modifier
            .size(size)
            .border(3.dp, if (enabled) Color.White else Color.Gray, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = if (recording) "Stop recording" else "Start recording" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(innerSize)
                .background(if (enabled) RecordRed else Color.DarkGray, RoundedCornerShape(corner)),
        )
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Camera access is needed to record full-sensor video.", color = Color.White)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text("Grant access") }
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White)
    }
}
