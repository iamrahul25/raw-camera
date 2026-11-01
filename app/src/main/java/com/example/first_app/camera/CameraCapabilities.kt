package com.example.first_app.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import android.util.SizeF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "FullSensor"
private const val MAX_PREVIEW_AREA = 1920 * 1440
private val PREFERRED_FPS = intArrayOf(30, 25, 24, 20, 15)

data class EncoderChoice(
    val mime: String,
    val label: String,
    val bitrate: Int,
)

data class VideoSizeOption(
    val size: Size,
    val fps: Int,
    val maxFps: Int,
    val aeFpsRange: Range<Int>,
    /** Fraction of the active sensor area that ends up in the frame (1.0 = whole sensor). */
    val sensorCoverage: Float,
    /** Null when no hardware encoder can handle this size at [fps]. */
    val encoder: EncoderChoice?,
) {
    val ratio: Float get() = size.width.toFloat() / size.height
    val recordable: Boolean get() = encoder != null
    val coversFullSensor: Boolean get() = sensorCoverage >= 0.99f
}

data class CameraInfo(
    val id: String,
    val facing: Int,
    val sensorOrientation: Int,
    val activeArray: Rect,
    val pixelArray: Size?,
    val maxResolutionPixelArray: Size?,
    val physicalSizeMm: SizeF?,
    val focalLengthMm: Float?,
    val hardwareLevel: String,
    val capabilities: List<String>,
    val rawSizes: List<Size>,
    val afModes: IntArray,
    val distortionCorrectionModes: IntArray,
    val previewSizes: List<Size>,
    /** Sorted best first: recordable, then widest sensor coverage, then largest. */
    val videoSizes: List<VideoSizeOption>,
) {
    val sensorRatio: Float get() = activeArray.width().toFloat() / activeArray.height()

    val facingLabel: String
        get() = when (facing) {
            CameraMetadata.LENS_FACING_BACK -> "Back"
            CameraMetadata.LENS_FACING_FRONT -> "Front"
            else -> "External"
        }

    val equivalentFocalLength: Int?
        get() {
            val f = focalLengthMm ?: return null
            val sensor = physicalSizeMm ?: return null
            val diagonal = sqrt(sensor.width * sensor.width + sensor.height * sensor.height)
            return if (diagonal > 0f) (f * 43.27f / diagonal).roundToInt() else null
        }

    val label: String
        get() = buildString {
            append(facingLabel).append(' ').append(id)
            equivalentFocalLength?.let { append(" · ").append(it).append("mm") }
        }

    /** Display ratio of a frame once rotated upright on a portrait-natural device. */
    fun uprightRatio(size: Size): Float =
        if (sensorOrientation % 180 != 0) size.height.toFloat() / size.width
        else size.width.toFloat() / size.height

    fun previewSizeFor(option: VideoSizeOption): Size {
        val matching = previewSizes.filter {
            abs(it.width.toFloat() / it.height - option.ratio) < 0.01f &&
                it.width * it.height <= MAX_PREVIEW_AREA
        }
        return matching.maxByOrNull { it.width * it.height }
            ?: previewSizes.filter { it.width * it.height <= MAX_PREVIEW_AREA }
                .minByOrNull { abs(it.width.toFloat() / it.height - option.ratio) }
            ?: option.size
    }

    fun report(): String = buildString {
        appendLine("Camera $id ($facingLabel)")
        appendLine("Hardware level: $hardwareLevel")
        appendLine("Sensor orientation: $sensorOrientation°")
        pixelArray?.let { appendLine("Pixel array: ${it.describe()}") }
        maxResolutionPixelArray?.let { appendLine("Max-resolution pixel array: ${it.describe()} (binned for video)") }
        appendLine("Active array: ${activeArray.width()}×${activeArray.height()} · ${ratioLabel(sensorRatio)}")
        physicalSizeMm?.let { appendLine("Physical sensor: %.2f × %.2f mm".format(it.width, it.height)) }
        focalLengthMm?.let { f ->
            append("Focal length: %.2f mm".format(f))
            equivalentFocalLength?.let { append(" (≈${it}mm equiv.)") }
            appendLine()
        }
        appendLine("Capabilities: ${capabilities.joinToString()}")
        appendLine(
            if (rawSizes.isEmpty()) "RAW_SENSOR: not available"
            else "RAW_SENSOR: ${rawSizes.joinToString { "${it.width}×${it.height}" }}"
        )
        appendLine()
        appendLine("Video sizes (best first):")
        videoSizes.forEach { appendLine("  ${it.describe()}") }
    }
}

fun Size.describe(): String = "${width}×${height} (%.1f MP)".format(width * height / 1_000_000f)

fun VideoSizeOption.describe(): String = buildString {
    append("${size.width}×${size.height} · ${ratioLabel(ratio)} · ${fps}fps")
    if (maxFps > fps) append(" (max $maxFps)")
    append(" · ${(sensorCoverage * 100).roundToInt()}% sensor")
    append(
        encoder?.let { " · ${it.label} ${it.bitrate / 1_000_000} Mbps" } ?: " · no encoder"
    )
}

fun ratioLabel(ratio: Float): String {
    val known = listOf(
        4f / 3 to "4:3", 16f / 9 to "16:9", 3f / 2 to "3:2", 1f to "1:1",
        5f / 4 to "5:4", 2f to "2:1", 21f / 9 to "21:9", 11f / 9 to "11:9",
    )
    val match = known.firstOrNull { abs(it.first - ratio) / it.first < 0.01f }
    return match?.second ?: "%.2f:1".format(ratio)
}

object CameraInspector {

    fun inspectAll(context: Context): List<CameraInfo> {
        val manager = context.getSystemService(CameraManager::class.java)
        val encoders = EncoderCatalog()
        return manager.cameraIdList
            .mapNotNull { id ->
                runCatching { inspect(id, manager.getCameraCharacteristics(id), encoders) }
                    .onFailure { Log.w(TAG, "Skipping camera $id", it) }
                    .getOrNull()
            }
            .sortedWith(
                compareBy<CameraInfo> { it.facing != CameraMetadata.LENS_FACING_BACK }
                    .thenBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }
            )
            .onEach { info -> info.report().lines().forEach { Log.i(TAG, it) } }
    }

    private fun inspect(id: String, chars: CameraCharacteristics, encoders: EncoderCatalog): CameraInfo? {
        val capabilityCodes = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in capabilityCodes) return null

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val sensorRatio = activeArray.width().toFloat() / activeArray.height()
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList().orEmpty()

        val videoSizes = map.getOutputSizes(MediaRecorder::class.java).orEmpty()
            .map { size ->
                val minFrameNs = map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
                val maxFps = if (minFrameNs > 0) (1_000_000_000.0 / minFrameNs).roundToInt() else 30
                val fpsLadder = PREFERRED_FPS.filter { it <= maxFps }.ifEmpty { listOf(maxFps) }
                val (fps, encoder) = fpsLadder.firstNotNullOfOrNull { candidate ->
                    val fps = min(candidate, pickAeRange(fpsRanges, candidate).upper)
                    encoders.choose(size.width, size.height, fps)?.let { fps to it }
                } ?: (fpsLadder.first() to null)
                val ratio = size.width.toFloat() / size.height
                VideoSizeOption(
                    size = size,
                    fps = fps,
                    maxFps = maxFps,
                    aeFpsRange = pickAeRange(fpsRanges, fps),
                    sensorCoverage = min(ratio, sensorRatio) / max(ratio, sensorRatio),
                    encoder = encoder,
                )
            }
            .sortedWith(
                compareByDescending<VideoSizeOption> { it.recordable }
                    .thenByDescending { (it.sensorCoverage * 100).roundToInt() }
                    .thenByDescending { it.size.width * it.size.height }
            )
        if (videoSizes.isEmpty()) return null

        val maxResPixelArray = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)
        } else null
        val distortionModes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            chars.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
        } else null

        return CameraInfo(
            id = id,
            facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraMetadata.LENS_FACING_EXTERNAL,
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            activeArray = activeArray,
            pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE),
            maxResolutionPixelArray = maxResPixelArray,
            physicalSizeMm = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE),
            focalLengthMm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull(),
            hardwareLevel = hardwareLevelLabel(chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)),
            capabilities = capabilityCodes.toList().mapNotNull(::capabilityLabel).distinct(),
            rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty(),
            afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0),
            distortionCorrectionModes = distortionModes ?: IntArray(0),
            previewSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty(),
            videoSizes = videoSizes,
        )
    }

    private fun pickAeRange(ranges: List<Range<Int>>, fps: Int): Range<Int> =
        ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: ranges.filter { it.upper <= fps }.maxByOrNull { it.upper }
            ?: Range(fps, fps)

    private fun hardwareLevelLabel(level: Int?): String = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun capabilityLabel(code: Int): String? = when (code) {
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "HIGH_SPEED_VIDEO"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ULTRA_HIGH_RESOLUTION"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT -> "10_BIT"
        else -> null
    }
}

private class EncoderCatalog {
    private val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder }
        .filter { Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || it.isHardwareAccelerated }

    fun choose(width: Int, height: Int, fps: Int): EncoderChoice? =
        candidate(MediaFormat.MIMETYPE_VIDEO_HEVC, "HEVC", 0.2, width, height, fps)
            ?: candidate(MediaFormat.MIMETYPE_VIDEO_AVC, "H.264", 0.3, width, height, fps)

    private fun candidate(
        mime: String,
        label: String,
        bitsPerPixel: Double,
        width: Int,
        height: Int,
        fps: Int,
    ): EncoderChoice? {
        val caps = encoders.asSequence()
            .filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            .mapNotNull { runCatching { it.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() }
            .firstOrNull { it.supports(width, height, fps) }
            ?: return null
        val target = (width.toLong() * height * fps * bitsPerPixel).toLong()
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return EncoderChoice(mime, label, target.coerceIn(caps.bitrateRange.lower, caps.bitrateRange.upper))
    }

    private fun MediaCodecInfo.VideoCapabilities.supports(width: Int, height: Int, fps: Int): Boolean =
        runCatching { areSizeAndRateSupported(width, height, fps.toDouble()) }.getOrDefault(false)
}
