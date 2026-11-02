package com.example.first_app.camera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

data class SavedVideo(
    val displayName: String,
    val location: String,
    val uri: Uri,
    /** 0 when unknown. */
    val sizeBytes: Long = 0,
    /** Epoch millis; 0 when unknown. */
    val createdAt: Long = 0,
)

/** Destination for one recording: MediaStore `Movies/DualCam` on Android 10+, app storage before that. */
class VideoOutput private constructor(
    private val context: Context,
    val displayName: String,
    private val uri: Uri?,
    private val descriptor: ParcelFileDescriptor?,
    private val file: File?,
) {

    fun applyTo(recorder: android.media.MediaRecorder) {
        if (descriptor != null) recorder.setOutputFile(descriptor.fileDescriptor)
        else recorder.setOutputFile(file!!.absolutePath)
    }

    fun finish(success: Boolean): SavedVideo? {
        runCatching { descriptor?.close() }
        if (!success) {
            discard()
            return null
        }
        if (uri != null) {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
            return SavedVideo(displayName, "Movies/$FOLDER/$displayName", uri)
        }
        return SavedVideo(displayName, file!!.absolutePath, Uri.fromFile(file))
    }

    private fun discard() {
        runCatching {
            if (uri != null) context.contentResolver.delete(uri, null, null) else file?.delete()
        }.onFailure { Log.w("FullSensor", "Could not delete failed recording", it) }
    }

    companion object {
        private const val FOLDER = "DualCam"

        fun create(context: Context): VideoOutput {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val name = "DC_$stamp.mp4"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values,
                ) ?: error("Could not create a MediaStore entry")
                val descriptor = resolver.openFileDescriptor(uri, "rw")
                    ?: error("Could not open $uri for writing")
                return VideoOutput(context, name, uri, descriptor, null)
            }
            return VideoOutput(context, name, null, null, File(legacyDir(context), name))
        }

        /** Recordings newest first (needs video read permission to see ones from earlier installs). */
        fun all(context: Context): List<SavedVideo> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return legacyDir(context).listFiles { f -> f.name.endsWith(".mp4") }.orEmpty()
                    .sortedByDescending { it.lastModified() }
                    .map { SavedVideo(it.name, it.absolutePath, Uri.fromFile(it), it.length(), it.lastModified()) }
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED,
            )
            return context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.Video.Media.RELATIVE_PATH} = ?",
                arrayOf("${Environment.DIRECTORY_MOVIES}/$FOLDER/"),
                "${MediaStore.Video.Media.DATE_ADDED} DESC, ${MediaStore.Video.Media._ID} DESC",
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1)
                        add(
                            SavedVideo(
                                displayName = name,
                                location = "Movies/$FOLDER/$name",
                                uri = ContentUris.withAppendedId(collection, cursor.getLong(0)),
                                sizeBytes = cursor.getLong(2),
                                createdAt = cursor.getLong(3) * 1000,
                            )
                        )
                    }
                }
            }.orEmpty()
        }

        /** Throws SecurityException when the file belongs to another app (or an earlier install). */
        fun delete(context: Context, video: SavedVideo): Boolean =
            if (video.uri.scheme == "file") File(video.uri.path!!).delete()
            else context.contentResolver.delete(video.uri, null, null) > 0

        private fun legacyDir(context: Context): File =
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
    }
}

/** Upright first frame, the upright (rotation tag applied) video size, and stream details. */
class VideoPreview(val frame: Bitmap?, val width: Int, val height: Int, val bitrate: Int)

/** Upright first frame, scaled so its longer side is at most [maxSide]. */
fun SavedVideo.thumbnail(context: Context, maxSide: Int = 256): Bitmap? =
    withRetriever(context) { it.frameAtTime?.scaledTo(maxSide) }

/**
 * MediaPlayer reports the stored size even though it draws frames upright, so the viewer takes the
 * upright size from here instead.
 */
fun SavedVideo.preview(context: Context, maxSide: Int): VideoPreview? = withRetriever(context) { retriever ->
    fun key(k: Int) = retriever.extractMetadata(k)?.toIntOrNull() ?: 0
    val rotation = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
    val width = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
    val height = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
    val sideways = rotation % 180 != 0
    VideoPreview(
        frame = retriever.frameAtTime?.scaledTo(maxSide),
        width = if (sideways) height else width,
        height = if (sideways) width else height,
        bitrate = key(MediaMetadataRetriever.METADATA_KEY_BITRATE),
    )
}

private fun <T> SavedVideo.withRetriever(context: Context, block: (MediaMetadataRetriever) -> T): T? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        block(retriever)
    } catch (e: Exception) {
        Log.w("FullSensor", "Cannot read $uri", e)
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun Bitmap.scaledTo(maxSide: Int): Bitmap {
    val scale = maxSide.toFloat() / max(width, height)
    return if (scale >= 1f) this
    else Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
}