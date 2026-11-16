package com.youme.memoria.Gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.youme.memoria.ImageLoading.ImageUriItem
import com.youme.memoria.ImageSizeUtil
import com.youme.memoria.MediaScannerHelper
import com.youme.memoria.settings.FoldersManager.FolderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GalleryPagingSource(
    private val contentResolver: ContentResolver,
    private val pageSize: Int = 60,
    private val appContext: Context? = null
) : PagingSource<Int, DateImageBlocks>() {

    override fun getRefreshKey(state: PagingState<Int, DateImageBlocks>): Int? {
        return state.anchorPosition?.let { anchor ->
            state.closestPageToPosition(anchor)?.let { it.prevKey?.plus(1) ?: it.nextKey?.minus(1) }
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, DateImageBlocks> {
        val page = params.key ?: 0
        return withContext(Dispatchers.IO) {
            try {
                val (blocks, rowCount) = queryPage(page, pageSize)
                LoadResult.Page(
                    data = blocks,
                    prevKey = if (page == 0) null else page - 1,
                    nextKey = if (rowCount < pageSize) null else page + 1
                )
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }
    }

    private suspend fun queryPage(page: Int, pageSize: Int): Pair<List<DateImageBlocks>, Int> {
        val ctx = appContext ?: return emptyList<DateImageBlocks>() to 0
        val selectedBuckets = FolderPrefs(ctx).selectedBuckets.first()
        if (selectedBuckets.isEmpty()) return emptyList<DateImageBlocks>() to 0

        val allEntries = MediaScannerHelper.getUnifiedGalleryEntries(ctx, selectedBuckets)
        val offset = page * pageSize
        if (offset >= allEntries.size) return emptyList<DateImageBlocks>() to 0
        val slice = allEntries.subList(offset, minOf(offset + pageSize, allEntries.size))
        val blocks = LinkedHashMap<String, DateImageBlocks>()
        val fmt = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
        for (entry in slice) {
            val (w, h) = if (entry.width > 0 && entry.height > 0) {
                entry.width to entry.height
            } else {
                ImageSizeUtil.getImageDimensions(ctx, entry.uri) ?: (1 to 1)
            }
            val date = fmt.format(Date(entry.dateTaken))
            blocks.getOrPut(date) { DateImageBlocks(date, mutableListOf()) }
                .images.add(ImageUriItem(entry.id, entry.uri, w, h))
        }
        return blocks.values.toList() to slice.size
    }
}

data class DateImageBlocks(
    val dataTaken: String,
    val images: MutableList<ImageUriItem>,
)
