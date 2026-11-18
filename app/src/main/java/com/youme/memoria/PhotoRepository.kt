package com.youme.memoria

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import com.youme.memoria.Encoder.MemoriaEncoder
import com.youme.memoria.ImageLoading.ImageUriItem
import com.youme.memoria.roomCach.FolderCount
import com.youme.memoria.roomCach.PhotoEntity
import com.youme.memoria.roomCach.PhotosDatabase
import com.youme.memoria.roomCach.toByteArray
import com.youme.memoria.roomCach.toFloatArray
import com.youme.memoria.search.SearchDateGroup
import com.youme.memoria.settings.FoldersManager.FolderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class Preset(
    val score: Float,
    val imageScore: Float,
    val maxImageResults: Int
) {
    LOOSE(0.14f, 0.38f, 250),
    BALANCED(0.18f, 0.46f, 150),
    STRICT(0.22f, 0.56f, 80)
}

data class SearchFilters(
    val startDate: Long?,
    val endDate: Long?,
    val folders: List<String>,
    val camera: String?,
    val confidence: Preset = Preset.BALANCED
)

private class DecodedEmbeddingEntry(
    val entity: PhotoEntity,
    val vector: FloatArray
)

class PhotoRepository(private val appContext: Context) {

    private val dao = PhotosDatabase.getInstance(appContext).photoDao()
    private val memoriaEncoder = MemoriaEncoder(appContext)

    private val embeddingCacheMutex = Mutex()
    private val embeddingCacheByUri = LinkedHashMap<String, DecodedEmbeddingEntry>(4096)
    @Volatile private var embeddingCacheDbCount: Int = -1

    suspend fun getCountPhotos() = dao.count()

    private val imageModelMutex = Mutex()

    suspend fun initializeImageModel() {
        imageModelMutex.withLock {
            withContext(Dispatchers.IO) {
                memoriaEncoder.initializeImageEncoder()
            }
        }
    }

    private val initMutex = Mutex()
    @Volatile var initializeedTextModel = false
        private set

    suspend fun initializeTextModel() {
        if (initializeedTextModel) return
        withContext(Dispatchers.IO) {
            memoriaEncoder.initializeTextEncoder()
            initializeedTextModel = true
        }
    }

    suspend fun ensureTextModelInitialized() {
        if (initializeedTextModel) return
        initMutex.withLock {
            if (initializeedTextModel) return
            initializeTextModel()
            initializeedTextModel = true
        }
    }

    suspend fun getFoldersList(): List<FolderCount> = dao.getAvailableFolders()

    suspend fun getCameraList() = dao.getAvailableCameras()

    suspend fun alreadyExistsList() = dao.getAll()

    private suspend fun ensureEmbeddingCacheLoaded(): Collection<DecodedEmbeddingEntry> =
        withContext(Dispatchers.IO) {
            val currentCount = dao.count()
            if (embeddingCacheDbCount == currentCount && embeddingCacheByUri.isNotEmpty()) {
                return@withContext embeddingCacheByUri.values
            }
            embeddingCacheMutex.withLock {
                val recheckCount = dao.count()
                if (embeddingCacheDbCount != recheckCount || (recheckCount > 0 && embeddingCacheByUri.isEmpty())) {
                    val rows = dao.getAll()
                    embeddingCacheByUri.clear()
                    for (row in rows) {
                        if (row.embedding.isNotEmpty()) {
                            val lightEntity = row.copy(embedding = NON_EMPTY_MARKER)
                            embeddingCacheByUri[row.uri] = DecodedEmbeddingEntry(
                                entity = lightEntity,
                                vector = row.embedding.toFloatArray()
                            )
                        }
                    }
                    embeddingCacheDbCount = recheckCount
                }
                embeddingCacheByUri.values
            }
        }

    suspend fun indexedImagesFiltered(searchFilters: SearchFilters): List<PhotoEntity> {
        val selectedBuckets = folderPrefs.selectedBuckets.first()
        if (selectedBuckets.isEmpty()) return emptyList()
        val allowedUris = withContext(Dispatchers.IO) {
            MediaScannerHelper.getImagesForSelectedBuckets(appContext, selectedBuckets)
                .mapTo(HashSet()) { it.toString() }
        }
        val cachedEntries = ensureEmbeddingCacheLoaded()
        val folderFilterSet = if (searchFilters.folders.isEmpty()) null else searchFilters.folders.toHashSet()
        val start = searchFilters.startDate
        val end = searchFilters.endDate
        val cam = searchFilters.camera

        val out = ArrayList<PhotoEntity>(minOf(cachedEntries.size, allowedUris.size))
        for (item in cachedEntries) {
            val e = item.entity
            if (e.uri !in allowedUris) continue
            if (start != null && (e.dateTaken ?: 0L) < start) continue
            if (end != null && (e.dateTaken ?: 0L) > end) continue
            if (folderFilterSet != null && e.folderPath !in folderFilterSet) continue
            if (cam != null && e.cameraModel != cam) continue
            out.add(e)
        }
        return out
    }

    suspend fun buildSearchDateGroups(
        matches: List<Pair<PhotoEntity, Float>>
    ): List<SearchDateGroup> = withContext(Dispatchers.Default) {
        if (matches.isEmpty()) return@withContext emptyList()

        val selectedBuckets = folderPrefs.selectedBuckets.first()
        val galleryEntries = if (selectedBuckets.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                MediaScannerHelper.getUnifiedGalleryEntries(appContext, selectedBuckets)
            }
        }

        val matchedUriSet = matches.mapTo(HashSet(matches.size * 2)) { it.first.uri }
        val matchedGalleryMap = HashMap<String, GalleryMediaEntry>(matches.size * 2)
        for (entry in galleryEntries) {
            val u = entry.uri.toString()
            if (u in matchedUriSet) {
                matchedGalleryMap[u] = entry
            }
        }

        val cal = Calendar.getInstance()
        fun dayCodeFor(millis: Long): Int {
            if (millis <= 0L) return 0
            cal.timeInMillis = millis
            return cal.get(Calendar.YEAR) * 10_000 +
                (cal.get(Calendar.MONTH) + 1) * 100 +
                cal.get(Calendar.DAY_OF_MONTH)
        }

        class MutableDayBucket(
            val dayCode: Int,
            var maxTimestamp: Long,
            var bestScore: Float
        ) {
            val matchedItems = ArrayList<ImageUriItem>()
            val matchedSeen = HashSet<String>()
            val allDayItems = ArrayList<ImageUriItem>()
            val allDaySeen = HashSet<String>()
        }

        val bucketsByDayCode = LinkedHashMap<Int, MutableDayBucket>()

        for ((photo, score) in matches) {
            val galleryEntry = matchedGalleryMap[photo.uri]
            val ts = galleryEntry?.dateTaken?.takeIf { it > 0L }
                ?: photo.dateTaken?.takeIf { it > 0L }
                ?: 0L
            val code = dayCodeFor(ts)
            val w = if (photo.width > 0) photo.width else (galleryEntry?.width ?: 1)
            val h = if (photo.height > 0) photo.height else (galleryEntry?.height ?: 1)
            val parsedUri = galleryEntry?.uri ?: Uri.parse(photo.uri)
            val id = galleryEntry?.id ?: photo.uri.hashCode().toLong()

            val bucket = bucketsByDayCode.getOrPut(code) {
                MutableDayBucket(
                    dayCode = code,
                    maxTimestamp = ts,
                    bestScore = score
                )
            }
            if (ts > bucket.maxTimestamp) bucket.maxTimestamp = ts
            if (score > bucket.bestScore) bucket.bestScore = score
            if (bucket.matchedSeen.add(photo.uri)) {
                bucket.matchedItems.add(ImageUriItem(id = id, uri = parsedUri, width = w, height = h))
            }
        }

        if (bucketsByDayCode.isEmpty()) return@withContext emptyList()

        for (entry in galleryEntries) {
            if (entry.dateTaken <= 0L) continue
            val code = dayCodeFor(entry.dateTaken)
            val bucket = bucketsByDayCode[code] ?: continue
            val uriStr = entry.uri.toString()
            if (entry.dateTaken > bucket.maxTimestamp) {
                bucket.maxTimestamp = entry.dateTaken
            }
            if (bucket.allDaySeen.add(uriStr)) {
                val w = if (entry.width > 0) entry.width else 1
                val h = if (entry.height > 0) entry.height else 1
                bucket.allDayItems.add(
                    ImageUriItem(
                        id = entry.id,
                        uri = entry.uri,
                        width = w,
                        height = h
                    )
                )
            }
        }

        for (bucket in bucketsByDayCode.values) {
            for (m in bucket.matchedItems) {
                if (bucket.allDaySeen.add(m.uri.toString())) {
                    bucket.allDayItems.add(m)
                }
            }
        }

        val labelFmt = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
        bucketsByDayCode.values.map { bucket ->
            val label = if (bucket.dayCode == 0 || bucket.maxTimestamp <= 0L) {
                "Unknown date"
            } else {
                labelFmt.format(Date(bucket.maxTimestamp))
            }
            SearchDateGroup(
                dayKey = bucket.dayCode.toString(),
                dateLabel = label,
                dayTimestamp = bucket.maxTimestamp,
                bestScore = bucket.bestScore,
                matchedImages = bucket.matchedItems,
                allDayImages = bucket.allDayItems,
                isExpanded = false
            )
        }
    }

    suspend fun markAsFailed(uri: String) {
        if (alreadyExists(uri)) return
        dao.insert(
            PhotoEntity(
                uri = uri,
                embedding = ByteArray(0),
                width = 0,
                height = 0
            )
        )
        embeddingCacheDbCount = -1
    }

    suspend fun encodeImage(context: Context, image: Uri): FloatArray {
        return withContext(Dispatchers.IO) {
            val (bitmap, _) = decodeBitmapAndDimensions(context, image)
            try {
                memoriaEncoder.encodeImage(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
    }

    suspend fun encodeImageWithDimensions(
        context: Context,
        image: Uri
    ): Pair<FloatArray, Pair<Int, Int>> = withContext(Dispatchers.IO) {
        val (bitmap, dims) = decodeBitmapAndDimensions(context, image)
        try {
            memoriaEncoder.encodeImage(bitmap) to dims
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun searchByEmbd(
        embedding: FloatArray,
        indexedImages: List<PhotoEntity>,
        confidence: Float
    ): List<Pair<PhotoEntity, Float>> {
        return withContext(Dispatchers.Default) {
            ensureEmbeddingCacheLoaded()
            indexedImages.mapNotNull { photo ->
                val vec = embeddingCacheByUri[photo.uri]?.vector
                    ?: photo.embedding.takeIf { it.size >= 4 }?.toFloatArray()
                    ?: return@mapNotNull null
                val score = memoriaEncoder.cosineSimilarity(embedding, vec)
                if (score >= confidence) (photo to score) else null
            }.sortedByDescending { it.second }
        }
    }

    suspend fun encodeAndSaveImage(context: Context, uri: Uri) {
        withContext(Dispatchers.IO) {
            initializeImageModel()
            val (encodedImage, sizeData) = encodeImageWithDimensions(context, uri)
            val (dateTaken, folderPath, cameraInfo) = ImageSizeUtil.getCombinedMetadata(context, uri)
            insertEmbeddingFast(
                uri = uri.toString(),
                embedding = encodedImage,
                height = sizeData.second,
                width = sizeData.first,
                dateTaken = dateTaken,
                folderPath = folderPath,
                cameraMake = cameraInfo.first,
                cameraModel = cameraInfo.second
            )
        }
    }

    suspend fun search(
        query: String,
        indexedImages: List<PhotoEntity>,
        confidence: Float
    ): List<Pair<PhotoEntity, Float>> {
        return withContext(Dispatchers.Default) {
            if (indexedImages.isEmpty()) return@withContext emptyList()
            ensureEmbeddingCacheLoaded()
            val encodedText = memoriaEncoder.encodeText(query)

            val cores = Runtime.getRuntime().availableProcessors()
            val chunkCount = cores.coerceAtMost(indexedImages.size).coerceAtLeast(1)
            val chunkSize = (indexedImages.size + chunkCount - 1) / chunkCount
            val chunks = indexedImages.chunked(chunkSize)

            val partialResults: List<List<Pair<PhotoEntity, Float>>> = chunks.map { chunk ->
                async {
                    val matches = ArrayList<Pair<PhotoEntity, Float>>()
                    for (photo in chunk) {
                        val vec = embeddingCacheByUri[photo.uri]?.vector
                            ?: photo.embedding.takeIf { it.size >= 4 }?.toFloatArray()
                            ?: continue
                        val score = memoriaEncoder.cosineSimilarity(encodedText, vec)
                        if (score >= confidence) {
                            matches.add(photo to score)
                        }
                    }
                    matches.sortByDescending { it.second }
                    matches
                }
            }.awaitAll()

            mergeSortedDescending(partialResults)
        }
    }

    private fun mergeSortedDescending(
        lists: List<List<Pair<PhotoEntity, Float>>>
    ): List<Pair<PhotoEntity, Float>> {
        val result = ArrayList<Pair<PhotoEntity, Float>>(lists.sumOf { it.size })
        val indices = IntArray(lists.size)

        while (true) {
            var bestListIdx = -1
            var bestScore = Float.NEGATIVE_INFINITY

            for (i in lists.indices) {
                val idx = indices[i]
                if (idx < lists[i].size) {
                    val score = lists[i][idx].second
                    if (score > bestScore) {
                        bestScore = score
                        bestListIdx = i
                    }
                }
            }

            if (bestListIdx == -1) break

            result.add(lists[bestListIdx][indices[bestListIdx]])
            indices[bestListIdx]++
        }
        return result
    }

    suspend fun pruneDeletedPhotos(currentUris: List<Uri>) {
        val currentUriStrings = currentUris.map { it.toString() }
        currentUriStrings.chunked(500).forEach { batch ->
            dao.deleteWhereUriNotIn(batch)
        }
        embeddingCacheMutex.withLock {
            currentUriStrings.forEach { embeddingCacheByUri.remove(it) }
            embeddingCacheDbCount = -1
        }
    }

    suspend fun searchByImage(
        context: Context,
        image: Uri,
        indexedImages: List<PhotoEntity>,
        confidence: Preset = Preset.BALANCED
    ): List<Pair<PhotoEntity, Float>> {
        if (indexedImages.isEmpty()) return emptyList()
        ensureEmbeddingCacheLoaded()
        val encodedImage = withContext(Dispatchers.IO) {
            initializeImageModel()
            val (bitmap, _) = decodeBitmapAndDimensions(context, image)
            try {
                memoriaEncoder.encodeImage(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
        return withContext(Dispatchers.Default) {
            val cores = Runtime.getRuntime().availableProcessors()
            val chunkCount = cores.coerceAtMost(indexedImages.size).coerceAtLeast(1)
            val chunkSize = (indexedImages.size + chunkCount - 1) / chunkCount
            val chunks = indexedImages.chunked(chunkSize)

            val allScoredLists: List<List<Pair<PhotoEntity, Float>>> = chunks.map { chunk ->
                async {
                    val scored = ArrayList<Pair<PhotoEntity, Float>>()
                    for (photo in chunk) {
                        val vec = embeddingCacheByUri[photo.uri]?.vector
                            ?: photo.embedding.takeIf { it.size >= 4 }?.toFloatArray()
                            ?: continue
                        val score = memoriaEncoder.cosineSimilarity(encodedImage, vec)
                        if (score >= 0.25f) {
                            scored.add(photo to score)
                        }
                    }
                    scored.sortByDescending { it.second }
                    scored
                }
            }.awaitAll()

            val merged = mergeSortedDescending(allScoredLists)
            val filtered = merged.filter { it.second >= confidence.imageScore }
                .take(confidence.maxImageResults)
            if (filtered.isNotEmpty()) {
                filtered
            } else {
                merged.take(30)
            }
        }
    }

    suspend fun insertEmbeddingFast(
        uri: String,
        embedding: FloatArray,
        height: Int,
        width: Int,
        dateTaken: Long?,
        folderPath: String?,
        cameraMake: String?,
        cameraModel: String?
    ) {
        val entity = PhotoEntity(
            uri = uri,
            embedding = embedding.toByteArray(),
            width = width,
            height = height,
            dateTaken = dateTaken,
            folderPath = folderPath,
            cameraMake = cameraMake,
            cameraModel = cameraModel
        )
        dao.insert(entity)
        embeddingCacheMutex.withLock {
            if (embeddingCacheDbCount >= 0 && uri !in embeddingCacheByUri) {
                embeddingCacheByUri[uri] = DecodedEmbeddingEntry(
                    entity = entity.copy(embedding = NON_EMPTY_MARKER),
                    vector = embedding
                )
                embeddingCacheDbCount++
            } else {
                embeddingCacheDbCount = -1
            }
        }
    }

    suspend fun saveEmbedding(
        uri: String,
        embedding: FloatArray,
        height: Int,
        width: Int,
        dateTaken: Long?,
        folderPath: String?,
        cameraMake: String?,
        cameraModel: String?
    ) {
        if (alreadyExists(uri)) return
        insertEmbeddingFast(uri, embedding, height, width, dateTaken, cameraMake = cameraMake, cameraModel = cameraModel, folderPath = folderPath)
    }

    suspend fun alreadyExists(uri: String): Boolean = dao.existsByUri(uri) != null

    suspend fun unloadModel() = imageModelMutex.withLock { memoriaEncoder.close() }
    fun unloadImageModel() = memoriaEncoder.freeImageEncoder()

    val folderPrefs = FolderPrefs(appContext)

    suspend fun getImagesToIndex(context: Context): List<Uri> = withContext(Dispatchers.IO) {
        val selectedBuckets = folderPrefs.selectedBuckets.first()
        MediaScannerHelper.getImagesForSelectedBuckets(context, selectedBuckets)
    }

    suspend fun getImagePosition(
        context: Context,
        image: Uri
    ): Int = withContext(Dispatchers.IO) {
        val selectedBuckets = folderPrefs.selectedBuckets.first()
        val entries = MediaScannerHelper.getUnifiedGalleryEntries(context, selectedBuckets)
        entries.indexOfFirst { it.uri == image }.coerceAtLeast(0)
    }

    suspend fun getImagePosition(
        contentResolver: ContentResolver,
        image: Uri
    ): Int = withContext(Dispatchers.IO) {
        if (image.scheme == "file") return@withContext 0
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val imageId = runCatching { ContentUris.parseId(image) }.getOrElse { return@withContext 0 }
        val dateTaken = contentResolver.query(
            uri, arrayOf(MediaStore.Images.Media.DATE_TAKEN),
            "${MediaStore.Images.Media._ID} = ?", arrayOf(imageId.toString()), null
        )?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return@withContext -1

        contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DATE_TAKEN} > ? OR " +
                "(${MediaStore.Images.Media.DATE_TAKEN} = ? AND ${MediaStore.Images.Media._ID} > ?)",
            arrayOf(dateTaken.toString(), dateTaken.toString(), imageId.toString()),
            null
        )?.use { it.count } ?: -1
    }

    private fun decodeBitmapAndDimensions(
        context: Context,
        uri: Uri
    ): Pair<Bitmap, Pair<Int, Int>> {
        val source = if (uri.scheme == "file" && !uri.path.isNullOrEmpty()) {
            ImageDecoder.createSource(File(uri.path!!))
        } else {
            ImageDecoder.createSource(context.contentResolver, uri)
        }
        var origW = 0
        var origH = 0
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            origW = info.size.width
            origH = info.size.height
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val maxDim = maxOf(origW, origH)
            if (maxDim > 1024) {
                val scale = 1024f / maxDim
                decoder.setTargetSize(
                    (origW * scale).toInt().coerceAtLeast(1),
                    (origH * scale).toInt().coerceAtLeast(1)
                )
            }
        }
        ImageSizeUtil.cacheDimensions(uri, origW, origH)
        return bitmap to (origW to origH)
    }

    fun uriToBitmap(context: Context, uri: Uri): Bitmap {
        return decodeBitmapAndDimensions(context, uri).first
    }

    companion object {
        private val NON_EMPTY_MARKER = byteArrayOf(1)
    }
}
