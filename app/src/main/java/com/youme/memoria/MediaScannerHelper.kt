package com.youme.memoria

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import com.youme.memoria.Gallery.FolderInfo
import java.io.File
import java.util.Locale

data class GalleryMediaEntry(
    val id: Long,
    val uri: Uri,
    val dateTaken: Long,
    val width: Int,
    val height: Int
)

object MediaScannerHelper {

    const val FS_BUCKET_PREFIX = "fs:"

    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "avif"
    )

    private val CONTEXTUAL_SUBFOLDER_NAMES = setOf(
        "sent", "private"
    )

    private val KNOWN_ANDROID_MEDIA_SUBPATHS = listOf(
        "Android/media/com.whatsapp",
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent",
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Private",
        "Android/media/com.whatsapp.w4b",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images/Sent",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images/Private",
        "WhatsApp/Media/WhatsApp Images",
        "WhatsApp/Media/WhatsApp Images/Sent",
        "WhatsApp/Media/WhatsApp Images/Private"
    )

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun createAllFilesAccessIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    fun isImageFileName(name: String): Boolean {
        if (name.startsWith(".")) return false
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return false
        val ext = name.substring(dot + 1).lowercase(Locale.ROOT)
        return ext in IMAGE_EXTENSIONS
    }

    fun normalizeRelativePath(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val cleaned = raw.replace('\\', '/').trim('/')
        return if (cleaned.isEmpty()) "" else "$cleaned/"
    }

    fun formatFolderDisplayName(relativePath: String, fallbackName: String = ""): String {
        val normalized = normalizeRelativePath(relativePath)
        val segments = normalized.trimEnd('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return fallbackName.ifBlank { "Root" }
        val leaf = segments.last()
        if (segments.size >= 2 && leaf.lowercase(Locale.ROOT) in CONTEXTUAL_SUBFOLDER_NAMES) {
            val parent = segments[segments.size - 2]
            return "$parent ($leaf)"
        }
        return fallbackName.ifBlank { leaf }
    }

    fun getStorageRoots(context: Context): List<File> {
        val roots = LinkedHashMap<String, File>()
        runCatching {
            val primary = Environment.getExternalStorageDirectory()
            if (primary != null && primary.exists()) {
                roots[primary.absolutePath.trimEnd('/')] = primary
            }
        }
        runCatching {
            @Suppress("DEPRECATION")
            context.externalMediaDirs?.filterNotNull()?.forEach { mediaDir ->
                // .../Android/media/<package> -> 3 parents up is storage volume root
                val root = mediaDir.parentFile?.parentFile?.parentFile
                if (root != null && root.exists()) {
                    roots.putIfAbsent(root.absolutePath.trimEnd('/'), root)
                }
            }
        }
        return roots.values.toList()
    }

    fun getRelativeFolderPath(context: Context, dir: File?): String {
        if (dir == null) return ""
        val absDir = dir.absolutePath.replace('\\', '/').trimEnd('/')
        for (root in getStorageRoots(context)) {
            val rootPath = root.absolutePath.replace('\\', '/').trimEnd('/')
            if (absDir.equals(rootPath, ignoreCase = false)) {
                return ""
            }
            if (absDir.startsWith("$rootPath/")) {
                val rel = absDir.substring(rootPath.length + 1)
                return normalizeRelativePath(rel)
            }
        }
        return normalizeRelativePath(dir.name)
    }

    private fun walkHiddenImageFiles(
        context: Context,
        excludePaths: Set<String>,
        onFile: (File, String) -> Unit
    ) {
        val visitedDirs = HashSet<String>()
        val seenFiles = HashSet<String>()

        fun walkDir(dir: File, depth: Int) {
            if (depth > 12) return
            val absDir = dir.absolutePath
            if (!visitedDirs.add(absDir)) return
            val children = try {
                dir.listFiles()
            } catch (_: SecurityException) {
                null
            } ?: return

            var relPath: String? = null
            for (child in children) {
                val name = child.name
                if (name.startsWith(".")) continue
                if (child.isDirectory) {
                    walkDir(child, depth + 1)
                } else if (child.isFile && child.length() > 0L && isImageFileName(name)) {
                    val filePath = child.absolutePath
                    if (filePath !in excludePaths && seenFiles.add(filePath)) {
                        val folderRel = relPath ?: getRelativeFolderPath(context, dir).also {
                            relPath = it
                        }
                        onFile(child, folderRel)
                    }
                }
            }
        }

        for (root in getStorageRoots(context)) {
            val topChildren = try {
                root.listFiles()
            } catch (_: SecurityException) {
                null
            }
            topChildren?.forEach { child ->
                val name = child.name
                if (name.startsWith(".")) return@forEach
                if (child.isDirectory) {
                    if (!name.equals("Android", ignoreCase = true)) {
                        walkDir(child, 1)
                    }
                } else if (child.isFile && child.length() > 0L && isImageFileName(name)) {
                    val filePath = child.absolutePath
                    if (filePath !in excludePaths && seenFiles.add(filePath)) {
                        onFile(child, "")
                    }
                }
            }

            val androidMedia = File(root, "Android/media")
            if (androidMedia.exists()) {
                walkDir(androidMedia, 2)
            }

            for (subPath in KNOWN_ANDROID_MEDIA_SUBPATHS) {
                val target = File(root, subPath)
                if (target.exists() && target.isDirectory) {
                    val depth = subPath.count { it == '/' } + 1
                    walkDir(target, depth)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    fun getAvailableFolders(context: Context): List<FolderInfo> {
        data class MutableFolder(
            val bucketId: String,
            val bucketName: String,
            val folderPath: String,
            var count: Int
        )

        val byBucketId = LinkedHashMap<String, MutableFolder>()
        val bucketIdByRelPath = HashMap<String, String>()
        val mediaStorePaths = HashSet<String>()

        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DATA
            )
        } else {
            arrayOf(
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATA
            )
        }

        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else -1

                while (cursor.moveToNext()) {
                    val bucketId = cursor.getString(idCol) ?: continue
                    val rawName = cursor.getString(nameCol) ?: "Unknown"
                    val dataPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    if (!dataPath.isNullOrEmpty()) {
                        mediaStorePaths.add(dataPath)
                    }
                    val rawRel = if (relCol >= 0) cursor.getString(relCol) else null
                    val relPath = if (!rawRel.isNullOrBlank()) {
                        normalizeRelativePath(rawRel)
                    } else if (!dataPath.isNullOrEmpty()) {
                        getRelativeFolderPath(context, File(dataPath).parentFile)
                    } else {
                        ""
                    }

                    val existing = byBucketId[bucketId]
                    if (existing != null) {
                        existing.count++
                    } else {
                        val displayName = formatFolderDisplayName(relPath, rawName)
                        val displayPath = relPath.ifEmpty { rawName }
                        byBucketId[bucketId] = MutableFolder(
                            bucketId = bucketId,
                            bucketName = displayName,
                            folderPath = displayPath,
                            count = 1
                        )
                        if (relPath.isNotEmpty()) {
                            bucketIdByRelPath.putIfAbsent(relPath, bucketId)
                        }
                    }
                }
            }
        }

        walkHiddenImageFiles(context, mediaStorePaths) { file, relPath ->
            val matchedBucketId = if (relPath.isNotEmpty()) bucketIdByRelPath[relPath] else null
            val bucketId = matchedBucketId ?: "$FS_BUCKET_PREFIX$relPath"
            val existing = byBucketId[bucketId]
            if (existing != null) {
                existing.count++
            } else {
                val fallback = file.parentFile?.name ?: "Root"
                val displayName = formatFolderDisplayName(relPath, fallback)
                val displayPath = relPath.ifEmpty { "/" }
                byBucketId[bucketId] = MutableFolder(
                    bucketId = bucketId,
                    bucketName = displayName,
                    folderPath = displayPath,
                    count = 1
                )
                if (relPath.isNotEmpty()) {
                    bucketIdByRelPath.putIfAbsent(relPath, bucketId)
                }
            }
        }

        return byBucketId.values
            .filter { it.count > 0 }
            .sortedByDescending { it.count }
            .map {
                FolderInfo(
                    rank = 0,
                    bucketId = it.bucketId,
                    bucketName = it.bucketName,
                    folderPath = it.folderPath,
                    itemCount = it.count
                )
            }
    }

    @Suppress("DEPRECATION")
    fun getImagesForSelectedBuckets(context: Context, selectedBuckets: Set<String>): List<Uri> {
        val result = ArrayList<Uri>()
        val mediaStorePaths = HashSet<String>()

        val selectAll = selectedBuckets.isEmpty()
        val mediaBucketIds = selectedBuckets.filter { !it.startsWith(FS_BUCKET_PREFIX) }.toHashSet()
        val fsRelPaths = selectedBuckets
            .filter { it.startsWith(FS_BUCKET_PREFIX) }
            .map { normalizeRelativePath(it.removePrefix(FS_BUCKET_PREFIX)) }
            .toHashSet()
        val selectedRelPathsFromMediaBuckets = HashSet<String>()

        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DATE_ADDED
            )
        } else {
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DATE_ADDED
            )
        }

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val bucketId = cursor.getString(bucketCol) ?: ""
                    val dataPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    if (!dataPath.isNullOrEmpty()) {
                        mediaStorePaths.add(dataPath)
                    }
                    val rawRel = if (relCol >= 0) cursor.getString(relCol) else null
                    val relPath = if (!rawRel.isNullOrBlank()) {
                        normalizeRelativePath(rawRel)
                    } else if (!dataPath.isNullOrEmpty()) {
                        getRelativeFolderPath(context, File(dataPath).parentFile)
                    } else {
                        ""
                    }

                    val include = selectAll ||
                        bucketId in mediaBucketIds ||
                        (relPath.isNotEmpty() && relPath in fsRelPaths)

                    if (include) {
                        if (bucketId in mediaBucketIds && relPath.isNotEmpty()) {
                            selectedRelPathsFromMediaBuckets.add(relPath)
                        }
                        result.add(
                            ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                id
                            )
                        )
                    }
                }
            }
        }

        if (selectAll) {
            val hiddenFiles = ArrayList<File>()
            walkHiddenImageFiles(context, mediaStorePaths) { file, _ ->
                hiddenFiles.add(file)
            }
            hiddenFiles.sortByDescending { it.lastModified() }
            hiddenFiles.forEach { result.add(Uri.fromFile(it)) }
        } else {
            val targetRelPaths = fsRelPaths + selectedRelPathsFromMediaBuckets
            if (targetRelPaths.isNotEmpty()) {
                val hiddenFiles = ArrayList<File>()
                val seen = HashSet<String>()
                for (root in getStorageRoots(context)) {
                    for (relPath in targetRelPaths) {
                        val dir = if (relPath.isEmpty()) root else File(root, relPath.trimEnd('/'))
                        val children = try {
                            dir.listFiles()
                        } catch (_: SecurityException) {
                            null
                        } ?: continue

                        for (child in children) {
                            if (child.isFile && child.length() > 0L && isImageFileName(child.name)) {
                                val abs = child.absolutePath
                                if (abs !in mediaStorePaths && seen.add(abs)) {
                                    hiddenFiles.add(child)
                                }
                            }
                        }
                    }
                }
                hiddenFiles.sortByDescending { it.lastModified() }
                hiddenFiles.forEach { result.add(Uri.fromFile(it)) }
            }
        }

        return result
    }

    @Suppress("DEPRECATION")
    fun getUnifiedGalleryEntries(
        context: Context,
        selectedBuckets: Set<String>
    ): List<GalleryMediaEntry> {
        val entries = ArrayList<GalleryMediaEntry>()
        val mediaStorePaths = HashSet<String>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATA
        )
        val sortOrder =
            "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media._ID} DESC"

        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateTakenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val dataPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    if (!dataPath.isNullOrEmpty()) {
                        mediaStorePaths.add(dataPath)
                    }
                    val rawDateTaken = cursor.getLong(dateTakenCol)
                    val dateMillis = if (rawDateTaken > 0L) {
                        rawDateTaken
                    } else {
                        cursor.getLong(dateAddedCol) * 1000L
                    }
                    entries.add(
                        GalleryMediaEntry(
                            id = id,
                            uri = ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                id
                            ),
                            dateTaken = dateMillis,
                            width = cursor.getInt(widthCol),
                            height = cursor.getInt(heightCol)
                        )
                    )
                }
            }
        }

        val fsRelPaths = selectedBuckets
            .filter { it.startsWith(FS_BUCKET_PREFIX) }
            .map { normalizeRelativePath(it.removePrefix(FS_BUCKET_PREFIX)) }
            .toHashSet()

        if (fsRelPaths.isNotEmpty()) {
            val seen = HashSet<String>()
            for (root in getStorageRoots(context)) {
                for (relPath in fsRelPaths) {
                    val dir = if (relPath.isEmpty()) root else File(root, relPath.trimEnd('/'))
                    val children = try {
                        dir.listFiles()
                    } catch (_: SecurityException) {
                        null
                    } ?: continue
                    for (child in children) {
                        if (child.isFile && child.length() > 0L && isImageFileName(child.name)) {
                            val abs = child.absolutePath
                            if (abs !in mediaStorePaths && seen.add(abs)) {
                                val uri = Uri.fromFile(child)
                                entries.add(
                                    GalleryMediaEntry(
                                        id = -(abs.hashCode().toLong() and 0x7FFFFFFFL) - 1L,
                                        uri = uri,
                                        dateTaken = child.lastModified(),
                                        width = 0,
                                        height = 0
                                    )
                                )
                            }
                        }
                    }
                }
            }
            entries.sortWith(
                compareByDescending<GalleryMediaEntry> { it.dateTaken }
                    .thenByDescending { it.id }
            )
        }

        return entries
    }

    fun mediaExists(context: Context, uri: Uri): Boolean {
        if (uri.scheme == "file") {
            val path = uri.path ?: return false
            return File(path).exists()
        }
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.Media._ID),
                null,
                null,
                null
            )?.use { it.moveToFirst() } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun toShareableUri(context: Context, uri: Uri): Uri {
        if (uri.scheme == "file") {
            val path = uri.path ?: return uri
            val file = File(path)
            if (file.exists()) {
                return runCatching {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                }.getOrDefault(uri)
            }
        }
        return uri
    }
}
