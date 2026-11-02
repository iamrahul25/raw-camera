package com.example.first_app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "FullSensor"

data class CaptureSettings(val disableDistortionCorrection: Boolean = false)

/**
 * Camera2 recorder that keeps the whole sensor in frame: the stream size matches the sensor's
 * aspect ratio, the crop region is the full active array, and video stabilization (which crops)
 * is turned off.
 */
class FullSensorCamera(private val context: Context, private val onError: (String) -> Unit) {

    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("FullSensorCamera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val mutex = Mutex()

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var texture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var bound: Bound? = null
    private var recorder: MediaRecorder? = null
    private var output: VideoOutput? = null

    private class Bound(
        val info: CameraInfo,
        val option: VideoSizeOption,
        val previewSize: Size,
        val settings: CaptureSettings,
    )

    val isRecording: Boolean get() = recorder != null

    suspend fun bind(
        info: CameraInfo,
        option: VideoSizeOption,
        previewSize: Size,
        surfaceTexture: SurfaceTexture,
        settings: CaptureSettings,
    ) = mutex.withLock {
        check(recorder == null) { "Stop recording before changing the camera setup" }
        session?.close()
        session = null
        if (device?.id != info.openId) {
            device?.close()
            device = null
        }
        if (texture !== surfaceTexture) {
            previewSurface?.release()
            previewSurface = Surface(surfaceTexture)
            texture = surfaceTexture
        }
        bound = Bound(info, option, previewSize, settings)
        startPreviewLocked()
    }

    /** Starts recording and returns the file name being written. */
    suspend fun startRecording(orientationHint: Int, withAudio: Boolean): String = mutex.withLock {
        check(recorder == null) { "Already recording" }
        val b = bound ?: error("Camera is not ready")
        val dev = device ?: error("Camera is not open")
        val preview = previewSurface ?: error("Preview is not ready")
        val encoder = b.option.encoder ?: error("No encoder supports ${b.option.size}")

        val out = VideoOutput.create(context)
        val rec = newMediaRecorder()
        try {
            if (withAudio) rec.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            out.applyTo(rec)
            rec.setVideoEncoder(
                if (encoder.label == "HEVC") MediaRecorder.VideoEncoder.HEVC
                else MediaRecorder.VideoEncoder.H264
            )
            rec.setVideoSize(b.option.size.width, b.option.size.height)
            rec.setVideoFrameRate(b.option.fps)
            rec.setVideoEncodingBitRate(encoder.bitrate)
            if (withAudio) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioSamplingRate(48_000)
                rec.setAudioEncodingBitRate(256_000)
                rec.setAudioChannels(2)
            }
            rec.setOrientationHint(orientationHint)
            rec.prepare()

            session?.close()
            session = null
            val targets = listOf(preview, rec.surface)
            applyPreviewBufferSize(b)
            val s = dev.createSession(targets, b.info.physicalId)
            session = s
            s.setRepeatingRequest(buildRequest(dev, CameraDevice.TEMPLATE_RECORD, targets, b), null, handler)
            rec.start()
        } catch (e: Exception) {
            rec.release()
            out.finish(success = false)
            runCatching { startPreviewLocked() }
            throw e
        }
        recorder = rec
        output = out
        Log.i(TAG, "Recording ${out.displayName}: ${b.option.describe()}, rotation $orientationHint")
        out.displayName
    }

    suspend fun stopRecording(): SavedVideo? = mutex.withLock {
        stopRecordingLocked(restartPreview = true)
    }

    /** Closes the camera, finishing any recording in progress. Safe to call from onStop. */
    suspend fun release(): SavedVideo? = withContext(NonCancellable) {
        mutex.withLock {
            val saved = stopRecordingLocked(restartPreview = false)
            session?.close()
            session = null
            device?.close()
            device = null
            saved
        }
    }

    /** Forgets the preview texture once its view is gone. */
    suspend fun detachSurface() = withContext(NonCancellable) {
        mutex.withLock {
            session?.close()
            session = null
            previewSurface?.release()
            previewSurface = null
            texture = null
        }
    }

    fun shutdown() {
        thread.quitSafely()
    }

    private suspend fun startPreviewLocked() {
        val b = bound ?: return
        val surface = previewSurface ?: return
        val dev = device ?: openDevice(b.info.openId).also { device = it }
        applyPreviewBufferSize(b)
        val s = dev.createSession(listOf(surface), b.info.physicalId)
        session = s
        s.setRepeatingRequest(buildRequest(dev, CameraDevice.TEMPLATE_PREVIEW, listOf(surface), b), null, handler)
    }

    /**
     * TextureView resets its buffer size to its own pixel size whenever it is resized (e.g. when the
     * preview aspect changes on a camera switch), so this must run right before every session is created.
     */
    private fun applyPreviewBufferSize(b: Bound) {
        texture?.setDefaultBufferSize(b.previewSize.width, b.previewSize.height)
    }

    private suspend fun stopRecordingLocked(restartPreview: Boolean): SavedVideo? {
        val rec = recorder ?: return null
        val out = output
        recorder = null
        output = null
        runCatching { session?.stopRepeating() }
        val stopped = try {
            rec.stop()
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "MediaRecorder.stop failed; recording was probably too short", e)
            false
        }
        rec.release()
        val saved = out?.finish(stopped)
        if (restartPreview) {
            session?.close()
            session = null
            runCatching { startPreviewLocked() }.onFailure { Log.e(TAG, "Preview restart failed", it) }
        }
        return saved
    }

    private fun buildRequest(
        dev: CameraDevice,
        template: Int,
        targets: List<Surface>,
        b: Bound,
    ): CaptureRequest {
        val info = b.info
        val physicalId = info.physicalId
        val builder = if (physicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dev.createCaptureRequest(template, setOf(physicalId))
        } else {
            dev.createCaptureRequest(template)
        }

        /** Applies a lens-specific setting to the physical lens when it accepts it, else to the whole camera. */
        fun <T> CaptureRequest.Builder.setForLens(key: CaptureRequest.Key<T>, value: T) {
            if (physicalId != null && key.name in info.physicalRequestKeys &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ) {
                setPhysicalCameraKey(key, value, physicalId)
            } else {
                set(key, value)
            }
        }

        return builder.apply {
            targets.forEach(::addTarget)
            val active = info.activeArray
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, b.option.aeFpsRange)
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            // A physical lens's active array is in its own coordinates, so it can't crop the logical camera.
            if (physicalId == null || CaptureRequest.SCALER_CROP_REGION.name in info.physicalRequestKeys) {
                setForLens(CaptureRequest.SCALER_CROP_REGION, Rect(0, 0, active.width(), active.height()))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                set(CaptureRequest.CONTROL_ZOOM_RATIO, info.zoomRatio)
            }
            if (CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in info.afModes) {
                setForLens(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                b.settings.disableDistortionCorrection &&
                CameraMetadata.DISTORTION_CORRECTION_MODE_OFF in info.distortionCorrectionModes
            ) {
                setForLens(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_OFF)
            }
        }.build()
    }

    private fun newMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()

    @SuppressLint("MissingPermission")
    private suspend fun openDevice(id: String): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (cont.isActive) cont.resume(camera) else camera.close()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (cont.isActive) {
                    cont.resumeWithException(IllegalStateException("Camera $id is in use by another app"))
                } else {
                    onError("Camera $id was disconnected")
                }
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (cont.isActive) {
                    cont.resumeWithException(IllegalStateException("Camera $id failed to open (error $error)"))
                } else {
                    onError("Camera $id error $error")
                }
            }
        }, handler)
    }

    /** With [physicalId], every target streams from that lens of this logical camera. */
    private suspend fun CameraDevice.createSession(targets: List<Surface>, physicalId: String?): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resume(session) else session.close()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            IllegalStateException("This camera can't stream these sizes together")
                        )
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        targets.map { surface ->
                            OutputConfiguration(surface).apply { physicalId?.let(::setPhysicalCameraId) }
                        },
                        executor,
                        callback,
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                createCaptureSession(targets, callback, handler)
            }
        }
}
