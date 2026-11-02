package com.example.first_app.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.first_app.R
import com.example.first_app.camera.SavedVideo
import com.example.first_app.camera.VideoPreview
import com.example.first_app.camera.preview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Playback state of the page currently on screen; only that page owns a [MediaPlayer]. */
@Stable
private class Playback {
    var player by mutableStateOf<MediaPlayer?>(null)
    var ready by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var position by mutableIntStateOf(0)
    var duration by mutableIntStateOf(0)

    fun attach(mp: MediaPlayer) {
        player = mp
        ready = false
        playing = false
        position = 0
        duration = 0
    }

    fun detach(mp: MediaPlayer) {
        if (player === mp) {
            player = null
            ready = false
            playing = false
        }
    }

    fun toggle() {
        val mp = player ?: return
        if (!ready) return
        if (mp.isPlaying) mp.pause() else mp.start()
        playing = mp.isPlaying
    }

    fun seekTo(ms: Int) {
        if (ready) player?.seekTo(ms)
        position = ms
    }
}

/** Pinch zoom and pan of the page on screen, kept within the zoomed content's bounds. */
@Stable
private class Zoom {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var bounds = IntSize.Zero

    val zoomed: Boolean get() = scale > 1.01f

    fun transform(zoomChange: Float, panChange: Offset) {
        scale = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
        offset = clamp(offset + panChange)
    }

    fun toggle() {
        scale = if (zoomed) 1f else 2.5f
        offset = clamp(Offset.Zero)
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    private fun clamp(value: Offset): Offset {
        val maxX = bounds.width * (scale - 1f) / 2f
        val maxY = bounds.height * (scale - 1f) / 2f
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }
}

private const val MAX_ZOOM = 5f

@Composable
internal fun VideoViewer(videos: List<SavedVideo>, onDelete: (SavedVideo) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val currentVideos by rememberUpdatedState(videos)
        val pagerState = rememberPagerState { currentVideos.size }
        val playback = remember { Playback() }
        val zoom = remember { Zoom() }
        val previews = remember { mutableStateMapOf<Uri, VideoPreview>() }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var rotation by remember { mutableIntStateOf(0) }
        var seeking by remember { mutableStateOf(false) }
        var seekTarget by remember { mutableFloatStateOf(0f) }
        var confirmDelete by remember { mutableStateOf<SavedVideo?>(null) }
        val page = pagerState.currentPage.coerceAtMost(videos.lastIndex)
        val current = videos[page]

        LaunchedEffect(pagerState.settledPage) { zoom.reset() }

        LaunchedEffect(playback.player) {
            val mp = playback.player ?: return@LaunchedEffect
            while (true) {
                if (playback.ready && !seeking) {
                    playback.position = mp.currentPosition
                    playback.playing = mp.isPlaying
                }
                delay(200)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding(),
        ) {
            HorizontalPager(
                state = pagerState,
                key = { currentVideos[it].uri.toString() },
                userScrollEnabled = !zoom.zoomed,
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                VideoPage(
                    video = videos[index],
                    rotation = rotation,
                    active = index == pagerState.settledPage,
                    playback = playback,
                    zoom = zoom,
                    previews = previews,
                )
            }

            if (!zoom.zoomed && !playback.playing) {
                if (page > 0) {
                    PageArrow(R.drawable.ic_chevron_left, "Previous video", Modifier.align(Alignment.CenterStart)) {
                        scope.launch { pagerState.animateScrollToPage(page - 1) }
                    }
                }
                if (page < videos.lastIndex) {
                    PageArrow(R.drawable.ic_chevron_right, "Next video", Modifier.align(Alignment.CenterEnd)) {
                        scope.launch { pagerState.animateScrollToPage(page + 1) }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(R.drawable.ic_close, "Close", onClick = onClose)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        current.displayName,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val details = videoDetails(context, current, previews[current.uri])
                    if (details.isNotEmpty()) {
                        Text(details, color = TextPrimary.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val subtitle = listOfNotNull(
                        current.createdAt.takeIf { it > 0 }?.let {
                            DateUtils.formatDateTime(
                                context,
                                it,
                                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
                                    DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
                            )
                        },
                        if (videos.size > 1) "${page + 1} of ${videos.size}" else null,
                    ).joinToString(" · ")
                    if (subtitle.isNotEmpty()) {
                        Text(subtitle, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                CircleIconButton(R.drawable.ic_rotate, "Rotate") { rotation = (rotation + 90) % 360 }
                Spacer(Modifier.width(8.dp))
                CircleIconButton(R.drawable.ic_delete, "Delete") { confirmDelete = current }
            }

            if (zoom.zoomed) {
                Text(
                    "%.1f×".format(zoom.scale),
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 72.dp)
                        .background(Color(0x99000000), CircleShape)
                        .clickable(onClick = zoom::reset)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }

            if (!playback.playing) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000))
                        .clickable(enabled = playback.ready, onClick = playback::toggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_play),
                        contentDescription = "Play",
                        tint = TextPrimary,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
            ) {
                Slider(
                    value = if (seeking) seekTarget else playback.position.toFloat(),
                    onValueChange = {
                        seeking = true
                        seekTarget = it
                    },
                    onValueChangeFinished = {
                        playback.seekTo(seekTarget.toInt())
                        seeking = false
                    },
                    valueRange = 0f..playback.duration.coerceAtLeast(1).toFloat(),
                    enabled = playback.ready,
                    colors = SliderDefaults.colors(
                        thumbColor = TextPrimary,
                        activeTrackColor = Accent,
                        inactiveTrackColor = PillColor,
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircleIconButton(
                        if (playback.playing) R.drawable.ic_pause else R.drawable.ic_play,
                        if (playback.playing) "Pause" else "Play",
                        onClick = playback::toggle,
                    )
                    Text(
                        "${formatTime(if (seeking) seekTarget.toInt() else playback.position)} / ${formatTime(playback.duration)}",
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        confirmDelete?.let { video ->
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                containerColor = CardColor,
                title = { Text("Delete video?", color = TextPrimary) },
                text = { Text("${video.displayName} will be permanently deleted.", color = TextSecondary) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = null
                        onDelete(video)
                    }) { Text("Delete", color = RecordRed) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = null }) { Text("Cancel", color = TextPrimary) }
                },
            )
        }
    }
}

@Composable
private fun VideoPage(
    video: SavedVideo,
    rotation: Int,
    active: Boolean,
    playback: Playback,
    zoom: Zoom,
    previews: MutableMap<Uri, VideoPreview>,
) {
    val context = LocalContext.current
    val preview by produceState(previews[video.uri], video.uri) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { video.preview(context, maxSide = 720) }
                ?.also { previews[video.uri] = it }
        }
    }
    val thumbnail = remember(preview) { preview?.frame?.asImageBitmap() }
    val aspect = preview?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height }
        ?: thumbnail?.let { it.width.toFloat() / it.height }
        ?: (9f / 16)
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        zoom.transform(zoomChange, panChange)
    }

    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (active) {
                    Modifier
                        .onSizeChanged { zoom.bounds = it }
                        .transformable(transformState, canPan = { zoom.zoomed })
                        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoom.toggle() }) }
                        .graphicsLayer {
                            scaleX = zoom.scale
                            scaleY = zoom.scale
                            translationX = zoom.offset.x
                            translationY = zoom.offset.y
                        }
                } else {
                    Modifier
                },
            ),
    ) {
        RotatedFit(aspect, rotation) {
            thumbnail?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
            if (active) PlayerSurface(video, playback)
        }
    }
}

@Composable
private fun PageArrow(icon: Int, contentDescription: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .padding(horizontal = 8.dp)
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = contentDescription, tint = TextPrimary, modifier = Modifier.size(28.dp))
    }
}

/** Shows the first frame via the thumbnail underneath until the player has drawn something itself. */
@Composable
private fun PlayerSurface(video: SavedVideo, playback: Playback) {
    val context = LocalContext.current
    val player = remember(video.uri) { MediaPlayer() }
    var rendered by remember(video.uri) { mutableStateOf(false) }

    DisposableEffect(player) {
        playback.attach(player)
        player.setOnPreparedListener { mp ->
            playback.duration = mp.duration
            playback.ready = true
        }
        player.setOnCompletionListener {
            playback.playing = false
            playback.position = playback.duration
        }
        runCatching {
            player.setDataSource(context, video.uri)
            player.prepareAsync()
        }.onFailure { Log.e("FullSensor", "Cannot play ${video.uri}", it) }
        onDispose {
            playback.detach(player)
            player.release()
        }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .alpha(if (rendered) 1f else 0f),
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var surface: Surface? = null

                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        surface = Surface(texture).also { runCatching { player.setSurface(it) } }
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        runCatching { player.setSurface(null) }
                        surface?.release()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                        rendered = true
                    }
                }
            }
        },
    )
}

/** Fits a box of [aspect] into the available space after rotating it by [rotation] degrees. */
@Composable
private fun RotatedFit(aspect: Float, rotation: Int, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val sideways = rotation % 180 != 0
        val shownAspect = if (sideways) 1f / aspect else aspect
        val fitted = if (maxWidth / maxHeight > shownAspect) DpSize(maxHeight * shownAspect, maxHeight)
        else DpSize(maxWidth, maxWidth / shownAspect)
        Box(
            Modifier
                .requiredSize(if (sideways) DpSize(fitted.height, fitted.width) else fitted)
                .graphicsLayer { rotationZ = rotation.toFloat() },
            content = content,
        )
    }
}

@Composable
private fun CircleIconButton(icon: Int, contentDescription: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(PillColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = contentDescription, tint = TextPrimary, modifier = Modifier.size(22.dp))
    }
}

/** e.g. "24.8 MB · 1440×1920 · 47 Mbps"; parts that are not known yet are left out. */
private fun videoDetails(context: Context, video: SavedVideo, preview: VideoPreview?): String = listOfNotNull(
    video.sizeBytes.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
    preview?.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width}×${it.height}" },
    preview?.bitrate?.takeIf { it > 0 }?.let { "%.0f Mbps".format(it / 1_000_000f) },
).joinToString(" · ")

private fun formatTime(ms: Int): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
