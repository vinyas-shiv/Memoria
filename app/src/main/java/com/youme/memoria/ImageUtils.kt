package com.youme.memoria

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

data class ExifMetadata(
    val dateTaken: Long?,
    val cameraMake: String?,
    val cameraModel: String?
)

object ImageSizeUtil {

    private val dimensionsCache = LruCache<String, Pair<Int, Int>>(4096)

    fun cacheDimensions(uri: Uri, width: Int, height: Int) {
        if (width > 0 && height > 0) {
            dimensionsCache.put(uri.toString(), width to height)
        }
    }

    fun getImageDimensions(context: Context, uri: Uri): Pair<Int, Int>? {
        val key = uri.toString()
        dimensionsCache.get(key)?.let { return it }

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
                val pair = options.outWidth to options.outHeight
                dimensionsCache.put(key, pair)
                pair
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun readExifSinglePass(context: Context, uri: Uri): ExifMetadata {
        return try {
            val exif = if (uri.scheme == "file") {
                val filePath = uri.path ?: return ExifMetadata(null, null, null)
                val f = File(filePath)
                if (!f.exists()) return ExifMetadata(null, null, null)
                ExifInterface(f)
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    ExifInterface(stream)
                } ?: return ExifMetadata(null, null, null)
            }

            val rawDate = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            val parsedDate = rawDate?.let {
                runCatching {
                    SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time
                }.getOrNull()
            }
            val make = exif.getAttribute(ExifInterface.TAG_MAKE)
            val model = exif.getAttribute(ExifInterface.TAG_MODEL)
            ExifMetadata(parsedDate, make, model)
        } catch (_: Exception) {
            ExifMetadata(null, null, null)
        }
    }

    @Suppress("DEPRECATION")
    fun getCombinedMetadata(
        context: Context,
        uri: Uri
    ): Triple<Long?, String?, Pair<String?, String?>> {
        val exifMeta = readExifSinglePass(context, uri)
        if (uri.scheme == "file") {
            val filePath = uri.path ?: return Triple(null, null, exifMeta.cameraMake to exifMeta.cameraModel)
            val file = File(filePath)
            if (!file.exists()) return Triple(null, null, exifMeta.cameraMake to exifMeta.cameraModel)
            val dateTaken = exifMeta.dateTaken ?: file.lastModified()
            val relPath = MediaScannerHelper.getRelativeFolderPath(context, file.parentFile)
            return Triple(dateTaken, relPath, exifMeta.cameraMake to exifMeta.cameraModel)
        }

        val meta = getPhotoMetadata(context, uri)
        return Triple(meta?.first ?: exifMeta.dateTaken, meta?.second, exifMeta.cameraMake to exifMeta.cameraModel)
    }

    @Suppress("DEPRECATION")
    fun getPhotoMetadata(context: Context, uri: Uri): Pair<Long, String>? {
        if (uri.scheme == "file") {
            val filePath = uri.path ?: return null
            val file = File(filePath)
            if (!file.exists()) return null
            val exifDate = readExifSinglePass(context, uri).dateTaken
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
        val meta = readExifSinglePass(context, uri)
        return meta.cameraMake to meta.cameraModel
    }
}
