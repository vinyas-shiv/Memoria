package com.youme.memoria.Gallery

import android.content.ContentResolver
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.youme.memoria.ImageLoading.ImageUriItem
import com.youme.memoria.imageViewer.ImageViewerPagingSource
import kotlinx.coroutines.flow.Flow

class GalleryRepository(
    private val contentResolver: ContentResolver,
    private val appContext: Context? = null
) {
    fun getGalleryPager(): Pager<Int, DateImageBlocks> {
        return Pager(
            config = PagingConfig(
                pageSize = 60,
                enablePlaceholders = false,
                prefetchDistance = 20,
                initialLoadSize = 60
            ),
            pagingSourceFactory = { GalleryPagingSource(contentResolver, 60, appContext) }
        )
    }

    fun galleryFlow(startPosition: Int = 0, pageSize: Int = 60): Flow<PagingData<DateImageBlocks>> {
        val startPage = (startPosition / pageSize).coerceAtLeast(0)
        Log.d("test_post", "galleryFlow: page=$startPage position=$startPosition")

        return Pager(
            config = PagingConfig(
                pageSize = pageSize,
                initialLoadSize = pageSize,
                prefetchDistance = 2,
                enablePlaceholders = false
            ),
            initialKey = startPage,
            pagingSourceFactory = { GalleryPagingSource(contentResolver, pageSize, appContext) }
        ).flow
    }

    fun imageViewerFlow(startPosition: Int, totalCount: Int): Flow<PagingData<ImageUriItem>> =
        Pager(
            config = PagingConfig(pageSize = 30, initialLoadSize = 60, enablePlaceholders = true),
            initialKey = (startPosition - 30).coerceAtLeast(0),
            pagingSourceFactory = { ImageViewerPagingSource(contentResolver, totalCount, appContext) }
        ).flow
}

class GalleryViewModel(private val repository: GalleryRepository) : ViewModel() {
    val galleryFlow: Flow<PagingData<DateImageBlocks>> =
        repository.getGalleryPager().flow.cachedIn(viewModelScope)

    fun imageViewerFlow(startPosition: Int, totalCount: Int): Flow<PagingData<ImageUriItem>> =
        repository.imageViewerFlow(startPosition, totalCount).cachedIn(viewModelScope)
}
