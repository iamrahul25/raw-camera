package com.example.first_app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.first_app.R
import com.example.first_app.camera.CameraInfo
import com.example.first_app.camera.VideoSizeOption
import com.example.first_app.camera.ratioLabel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SizePickerSheet(
    info: CameraInfo,
    cameraName: String,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    fun choose(index: Int) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onSelect(index)
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetColor,
        contentColor = TextPrimary,
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Video size", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "$cameraName · ${ratioLabel(info.sensorRatio)} full sensor",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            itemsIndexed(info.videoSizes) { index, option ->
                SizeCard(option = option, selected = index == selected, onClick = { choose(index) })
            }
        }
    }
}

@Composable
private fun SizeCard(option: VideoSizeOption, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (option.recordable) 1f else 0.45f)
            .background(CardColor, shape)
            .border(1.5.dp, if (selected) Accent else Color.Transparent, shape)
            .clickable(enabled = option.recordable, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            option.label,
            color = if (selected) Accent else TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(76.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                "${option.size.width}×${option.size.height}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                option.encoder?.let { "${option.fps} fps · ${it.label} · ${it.bitrate / 1_000_000} Mbps" }
                    ?: "No hardware encoder",
                color = if (option.recordable) TextSecondary else Warning,
                fontSize = 12.sp,
            )
        }
        if (selected) {
            Box(
                Modifier
                    .size(22.dp)
                    .background(Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = "Selected", tint = Color.Black, modifier = Modifier.size(14.dp))
            }
        }
    }
}
