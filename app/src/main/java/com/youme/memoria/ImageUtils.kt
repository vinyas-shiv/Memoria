package com.youme.memoria

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

object ImageSizeUtil {
    fun getImageDimensions(context: Context, uri: Uri): Pair<Int, Int>? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            if (uri.scheme == "file") {
                val path = uri.path ?: return null
                BitmapFactory.decodeFile(path, options)
            } else {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                }
            }
            if (options.outWidth > 0 && options.outHeight > 0) {
                options.outWidth to options.outHeight
            } else null
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    fun getPhotoMetadata(context: Context, uri: Uri): Pair<Long, String>? {
        if (uri.scheme == "file") {
            val filePath = uri.path ?: return null
            val file = File(filePath)
            if (!file.exists()) return null
            val exifDate = try {
                val exif = ExifInterface(file)
                val rawDate = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                rawDate?.let {
                    SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time
                }
            } catch (_: Exception) {
                null
            }
            val dateTaken = exifDate ?: file.lastModified()
            val relPath = MediaScannerHelper.getRelativeFolderPath(context, file.parentFile)
            return Pair(dateTaken, relPath)
        }

        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DATA
            )
        } else {
            arrayOf(
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATA
            )
        }
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val rawDate = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))
                val dateAddedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val dateTaken = if (rawDate > 0L) {
                    rawDate
                } else if (dateAddedCol >= 0) {
                    cursor.getLong(dateAddedCol) * 1000L
                } else {
                    0L
                }
                val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else -1
                val dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                val rawRel = if (relCol >= 0) cursor.getString(relCol) else null
                val path = if (!rawRel.isNullOrBlank()) {
                    MediaScannerHelper.normalizeRelativePath(rawRel)
                } else if (dataCol >= 0) {
                    val dataPath = cursor.getString(dataCol)
                    if (!dataPath.isNullOrEmpty()) {
                        MediaScannerHelper.getRelativeFolderPath(context, File(dataPath).parentFile)
                    } else ""
                } else ""
                return Pair(dateTaken, path)
            }
        }
        return null
    }

    fun getCameraInfo(context: Context, uri: Uri): Pair<String?, String?> {
        return try {
            if (uri.scheme == "file") {
                val filePath = uri.path ?: return (null to null)
                val exif = ExifInterface(File(filePath))
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)
                make to model
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    val make = exif.getAttribute(ExifInterface.TAG_MAKE)
                    val model = exif.getAttribute(ExifInterface.TAG_MODEL)
                    make to model
                } ?: (null to null)
            }
        } catch (e: Exception) {
            null to null
        }
    }
}
