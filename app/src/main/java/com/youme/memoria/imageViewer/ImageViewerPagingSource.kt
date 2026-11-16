package com.youme.memoria.imageViewer

import android.content.ContentResolver
import android.content.Context
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.youme.memoria.ImageLoading.ImageUriItem
import com.youme.memoria.ImageSizeUtil
import com.youme.memoria.MediaScannerHelper
import com.youme.memoria.settings.FoldersManager.FolderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class ImageViewerPagingSource(
    private val contentResolver: ContentResolver,
    private val totalCount: Int,
    private val appContext: Context? = null
) : PagingSource<Int, ImageUriItem>() {

    override fun getRefreshKey(state: PagingState<Int, ImageUriItem>): Int? =
        state.anchorPosition?.let { (it - state.config.pageSize / 2).coerceAtLeast(0) }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ImageUriItem> =
        withContext(Dispatchers.IO) {
            try {
                if (totalCount <= 0) {
                    return@withContext LoadResult.Page(
                        data = emptyList(),
                        prevKey = null,
                        nextKey = null,
                        itemsBefore = 0,
                        itemsAfter = 0
                    )
                }
                val offset = (params.key ?: 0).coerceIn(0, maxOf(totalCount - 1, 0))
                val limit = if (params is LoadParams.Prepend)
                    minOf(params.loadSize, offset) else params.loadSize
                val start = if (params is LoadParams.Prepend) offset - limit else offset

                val items = query(start, limit)
                LoadResult.Page(
                    data = items,
                    prevKey = if (start == 0) null else start,
                    nextKey = if (start + items.size >= totalCount) null else start + items.size,
                    itemsBefore = start,
                    itemsAfter = (totalCount - start - items.size).coerceAtLeast(0)
                )
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }

    private suspend fun query(offset: Int, limit: Int): List<ImageUriItem> {
        val ctx = appContext ?: return emptyList()
        val selectedBuckets = FolderPrefs(ctx).selectedBuckets.first()
        if (selectedBuckets.isEmpty()) return emptyList()
        val allEntries = MediaScannerHelper.getUnifiedGalleryEntries(ctx, selectedBuckets)
        if (offset >= allEntries.size || limit <= 0) return emptyList()
        val slice = allEntries.subList(offset, minOf(offset + limit, allEntries.size))
        return slice.map { entry ->
            val (w, h) = if (entry.width > 0 && entry.height > 0) {
                entry.width to entry.height
            } else {
                ImageSizeUtil.getImageDimensions(ctx, entry.uri) ?: (1 to 1)
            }
            ImageUriItem(entry.id, entry.uri, w, h)
        }
    }
}
