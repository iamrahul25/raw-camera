package com.example.first_app.camera

import android.content.ContentValues
import android.content.Context
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

data class SavedVideo(val displayName: String, val location: String, val uri: Uri)

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
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            return VideoOutput(context, name, null, null, File(dir, name))
        }
    }
}
