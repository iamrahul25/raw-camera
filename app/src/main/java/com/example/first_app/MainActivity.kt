package com.example.first_app

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.first_app.camera.CameraInfo
import com.example.first_app.camera.CameraInspector
import com.example.first_app.camera.CaptureSettings
import com.example.first_app.camera.FullSensorCamera
import com.example.first_app.camera.SavedVideo
import com.example.first_app.camera.VideoOutput
import com.example.first_app.ui.FullSensorScreen
import com.example.first_app.ui.theme.FirstAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CameraUiState {
    var hasCameraPermission by mutableStateOf(false)
    var cameras by mutableStateOf<List<CameraInfo>>(emptyList())
    var selectedCamera by mutableIntStateOf(0)
    var selectedSize by mutableIntStateOf(0)
    var disableDistortionCorrection by mutableStateOf(false)
    var recordAudio by mutableStateOf(true)
    var recordingSince by mutableStateOf<Long?>(null)
    var busy by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)
    var lastVideo by mutableStateOf<SavedVideo?>(null)
    /** Recordings shown in the viewer, newest first; null when the viewer is closed. */
    var viewingVideos by mutableStateOf<List<SavedVideo>?>(null)

    val camera: CameraInfo? get() = cameras.getOrNull(selectedCamera)
    val option get() = camera?.videoSizes?.getOrNull(selectedSize)
}

interface CameraActions {
    fun requestPermissions()
    fun selectCamera(index: Int)
    fun selectSize(index: Int)
    fun setDisableDistortionCorrection(disable: Boolean)
    fun setRecordAudio(enabled: Boolean)
    fun toggleRecording()
    fun onTextureAvailable(texture: SurfaceTexture)
    fun onTextureDestroyed()
    fun viewVideos()
    fun closeVideo()
    fun deleteVideo(video: SavedVideo)
}

class MainActivity : ComponentActivity(), CameraActions {

    private val state = CameraUiState()
    private lateinit var camera: FullSensorCamera
    private lateinit var orientationListener: OrientationEventListener
    private var surfaceTexture: SurfaceTexture? = null
    private var deviceOrientation = 0
    private var started = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsChanged() }

    private val videoPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasVideoAccess()) openVideos() else state.status = "Allow video access to view recordings"
        }

    private var pendingDelete: SavedVideo? = null

    private val deleteRequestLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val video = pendingDelete ?: return@registerForActivityResult
            pendingDelete = null
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                onVideoDeleted(video)
            } else {
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        runCatching { VideoOutput.delete(applicationContext, video) }.getOrDefault(false)
                    }
                    if (deleted) onVideoDeleted(video) else state.status = "Couldn't delete ${video.displayName}"
                }
            }
        }

    private val videoPermissions = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        camera = FullSensorCamera(applicationContext) { message ->
            runOnUiThread { state.status = message }
        }
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation != ORIENTATION_UNKNOWN) deviceOrientation = orientation
            }
        }

        setContent {
            FirstAppTheme(darkTheme = true, dynamicColor = false) {
                FullSensorScreen(state = state, actions = this)
            }
        }

        if (isGranted(Manifest.permission.CAMERA)) onPermissionsChanged() else requestPermissions()
        if (hasVideoAccess()) {
            lifecycleScope.launch {
                state.lastVideo = withContext(Dispatchers.IO) { VideoOutput.all(applicationContext).firstOrNull() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        orientationListener.enable()
        rebind()
    }

    override fun onStop() {
        started = false
        orientationListener.disable()
        lifecycleScope.launch {
            val saved = camera.release()
            if (state.recordingSince != null) {
                state.recordingSince = null
                saved?.let { state.lastVideo = it }
                state.status = if (saved == null) "Recording stopped" else null
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        camera.shutdown()
        super.onDestroy()
    }

    override fun requestPermissions() {
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    override fun selectCamera(index: Int) {
        if (index == state.selectedCamera || state.recordingSince != null) return
        state.selectedCamera = index
        state.selectedSize = 0
        rebind()
    }

    override fun selectSize(index: Int) {
        if (index == state.selectedSize || state.recordingSince != null) return
        state.selectedSize = index
        rebind()
    }

    override fun setDisableDistortionCorrection(disable: Boolean) {
        state.disableDistortionCorrection = disable
        rebind()
    }

    override fun setRecordAudio(enabled: Boolean) {
        state.recordAudio = enabled
        if (enabled && !isGranted(Manifest.permission.RECORD_AUDIO)) requestPermissions()
    }

    override fun toggleRecording() {
        if (state.busy) return
        val info = state.camera ?: return
        state.busy = true
        lifecycleScope.launch {
            try {
                if (state.recordingSince == null) {
                    val audio = state.recordAudio && isGranted(Manifest.permission.RECORD_AUDIO)
                    val name = camera.startRecording(orientationHint(info), audio)
                    state.recordingSince = SystemClock.elapsedRealtime()
                    state.status = "Recording $name"
                } else {
                    val saved = camera.stopRecording()
                    state.recordingSince = null
                    saved?.let { state.lastVideo = it }
                    state.status = if (saved == null) "Recording failed (too short?)" else null
                }
            } catch (e: Exception) {
                Log.e("FullSensor", "Recording toggle failed", e)
                state.recordingSince = null
                state.status = "Error: ${e.message}"
            } finally {
                state.busy = false
            }
        }
    }

    override fun onTextureAvailable(texture: SurfaceTexture) {
        surfaceTexture = texture
        rebind()
    }

    override fun onTextureDestroyed() {
        surfaceTexture = null
        lifecycleScope.launch { camera.detachSurface() }
    }

    override fun viewVideos() {
        if (state.recordingSince != null) return
        if (hasVideoAccess()) openVideos() else videoPermissionLauncher.launch(videoPermissions)
    }

    override fun closeVideo() {
        state.viewingVideos = null
    }

    override fun deleteVideo(video: SavedVideo) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { VideoOutput.delete(applicationContext, video) } }
            result.onSuccess { deleted ->
                if (deleted) onVideoDeleted(video) else state.status = "Couldn't delete ${video.displayName}"
            }.onFailure { error ->
                val sender = deleteConsentSender(video, error)
                if (sender == null) {
                    Log.e("FullSensor", "Delete failed", error)
                    state.status = "Couldn't delete ${video.displayName}"
                } else {
                    pendingDelete = video
                    deleteRequestLauncher.launch(IntentSenderRequest.Builder(sender).build())
                }
            }
        }
    }

    private fun deleteConsentSender(video: SavedVideo, error: Throwable): IntentSender? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && error is SecurityException ->
            MediaStore.createDeleteRequest(contentResolver, listOf(video.uri)).intentSender
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && error is RecoverableSecurityException ->
            error.userAction.actionIntent.intentSender
        else -> null
    }

    private fun onVideoDeleted(video: SavedVideo) {
        val remaining = state.viewingVideos.orEmpty().filter { it.uri != video.uri }
        state.viewingVideos = remaining.ifEmpty { null }
        if (state.lastVideo?.uri == video.uri) {
            state.lastVideo = remaining.firstOrNull()
            if (remaining.isEmpty()) {
                lifecycleScope.launch {
                    state.lastVideo = withContext(Dispatchers.IO) { VideoOutput.all(applicationContext).firstOrNull() }
                }
            }
        }
    }

    private fun openVideos() {
        lifecycleScope.launch {
            val videos = withContext(Dispatchers.IO) { VideoOutput.all(applicationContext) }
                .ifEmpty { listOfNotNull(state.lastVideo) }
            if (videos.isEmpty()) {
                state.status = "No recordings yet"
                return@launch
            }
            state.status = null
            state.lastVideo = videos.first()
            state.viewingVideos = videos
        }
    }

    private fun onPermissionsChanged() {
        state.hasCameraPermission = isGranted(Manifest.permission.CAMERA)
        if (!state.hasCameraPermission || state.cameras.isNotEmpty()) return
        lifecycleScope.launch {
            state.cameras = withContext(Dispatchers.Default) { CameraInspector.inspectAll(applicationContext) }
            if (state.cameras.isEmpty()) state.status = "No usable camera found"
            rebind()
        }
    }

    private fun rebind() {
        val texture = surfaceTexture ?: return
        if (!started || !state.hasCameraPermission || state.recordingSince != null) return
        val info = state.camera ?: return
        val option = state.option ?: return
        val previewSize = info.previewSizeFor(option)
        val settings = CaptureSettings(state.disableDistortionCorrection)
        lifecycleScope.launch {
            runCatching { camera.bind(info, option, previewSize, texture, settings) }
                .onSuccess { if (state.status?.startsWith("Camera") == true) state.status = null }
                .onFailure {
                    Log.e("FullSensor", "Camera bind failed", it)
                    state.status = "Camera error: ${it.message}"
                }
        }
    }

    private fun orientationHint(info: CameraInfo): Int {
        var degrees = (deviceOrientation + 45) / 90 * 90 % 360
        if (info.facing == CameraMetadata.LENS_FACING_FRONT) degrees = -degrees
        return (info.sensorOrientation + degrees + 360) % 360
    }

    private fun hasVideoAccess() = videoPermissions.any(::isGranted)

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
