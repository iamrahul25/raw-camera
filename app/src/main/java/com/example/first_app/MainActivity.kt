package com.example.first_app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraMetadata
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
                state.status = saved?.let { "Saved ${it.location}" } ?: "Recording stopped"
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
                    state.status = saved?.let { "Saved ${it.location}" }
                        ?: "Recording failed (too short?)"
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

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
