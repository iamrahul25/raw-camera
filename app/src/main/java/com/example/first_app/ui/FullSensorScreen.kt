package com.example.first_app.ui

import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.first_app.CameraActions
import com.example.first_app.CameraUiState
import com.example.first_app.camera.CameraInfo
import com.example.first_app.camera.VideoSizeOption
import com.example.first_app.camera.describe
import com.example.first_app.camera.ratioLabel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val RecordRed = Color(0xFFE53935)

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
    var showReport by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        CameraChips(state.cameras, state.selectedCamera, enabled = !recording, onSelect = actions::selectCamera)

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
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
            Text(
                "${option.size.width}×${option.size.height} · ${ratioLabel(option.ratio)} · " +
                    "${(option.sensorCoverage * 100).roundToInt()}% sensor",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .background(Color(0x99000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        ControlPanel(
            state = state,
            info = info,
            option = option,
            recording = recording,
            actions = actions,
            onShowSizes = { showSizes = true },
            onShowReport = { showReport = true },
        )
    }

    if (showSizes) {
        SizePickerDialog(
            info = info,
            selected = state.selectedSize,
            onSelect = {
                actions.selectSize(it)
                showSizes = false
            },
            onDismiss = { showSizes = false },
        )
    }
    if (showReport) {
        AlertDialog(
            onDismissRequest = { showReport = false },
            confirmButton = { TextButton(onClick = { showReport = false }) { Text("Close") } },
            title = { Text("Sensor report") },
            text = {
                Text(
                    info.report(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
        )
    }
}

@Composable
private fun CameraChips(cameras: List<CameraInfo>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cameras.forEachIndexed { index, camera ->
            FilterChip(
                selected = index == selected,
                onClick = { onSelect(index) },
                enabled = enabled,
                label = { Text(camera.label) },
            )
        }
    }
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
            .background(Color(0x99000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(RecordRed, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text("%02d:%02d".format(seconds / 60, seconds % 60), color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ControlPanel(
    state: CameraUiState,
    info: CameraInfo,
    option: VideoSizeOption,
    recording: Boolean,
    actions: CameraActions,
    onShowSizes: () -> Unit,
    onShowReport: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val sensorMp = info.activeArray.width() * info.activeArray.height() / 1_000_000f
        InfoLine(
            "Sensor",
            "${info.activeArray.width()}×${info.activeArray.height()} · %.1f MP · %s".format(
                sensorMp, ratioLabel(info.sensorRatio),
            ),
        )
        InfoLine(
            "Video",
            option.encoder?.let { "${option.fps} fps · ${it.label} · ${it.bitrate / 1_000_000} Mbps" }
                ?: "No hardware encoder for this size",
        )
        InfoLine("FOV", "${(option.sensorCoverage * 100).roundToInt()}% of sensor · stabilization off")

        ToggleRow(
            "Disable lens distortion correction",
            checked = state.disableDistortionCorrection,
            enabled = !recording && info.distortionCorrectionModes.isNotEmpty(),
            onChange = actions::setDisableDistortionCorrection,
        )
        ToggleRow("Record audio", checked = state.recordAudio, enabled = !recording, onChange = actions::setRecordAudio)

        state.status?.let {
            Text(it, color = Color(0xFFB0BEC5), fontSize = 12.sp, maxLines = 2)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(onClick = onShowSizes, enabled = !recording) { Text("Size") }
            RecordButton(
                recording = recording,
                enabled = !state.busy && option.recordable,
                onClick = actions::toggleRecording,
            )
            OutlinedButton(onClick = onShowReport) { Text("Report") }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(label, color = Color(0xFF90A4AE), fontSize = 13.sp, modifier = Modifier.width(56.dp))
        Text(value, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .border(4.dp, if (enabled) Color.White else Color.Gray, CircleShape)
            .padding(8.dp)
            .clip(if (recording) RoundedCornerShape(8.dp) else CircleShape)
            .background(if (enabled) RecordRed else Color.DarkGray)
            .clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun SizePickerDialog(
    info: CameraInfo,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Video size") },
        text = {
            LazyColumn {
                itemsIndexed(info.videoSizes) { index, option ->
                    val color = when {
                        !option.recordable -> Color.Gray
                        option.coversFullSensor -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        option.describe(),
                        color = color,
                        fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = option.recordable) { onSelect(index) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        },
    )
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
