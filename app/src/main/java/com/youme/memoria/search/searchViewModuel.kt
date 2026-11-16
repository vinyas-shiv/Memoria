package com.youme.memoria.search

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.youme.memoria.MemoriaApp
import com.youme.memoria.SearchFilters
import com.youme.memoria.roomCach.PhotoEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

enum class SearchLayout { STAGGERED, DATEBLOCKS }

sealed class SearchState {
    object Loading : SearchState()
    data class Success(val results: List<Pair<PhotoEntity, Float>>) : SearchState()
    data class Error(val message: String) : SearchState()
}

class SearchViewModuel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as MemoriaApp).repo
    private val searchQuery = MutableStateFlow("")
    val searchImage = MutableStateFlow<Uri?>(null)

    val searchLayout = MutableStateFlow<SearchLayout>(SearchLayout.STAGGERED)

    val weightsSearch = MutableStateFlow<ByteArray>(ByteArray(0))
    val searchfilters = MutableStateFlow<SearchFilters>(SearchFilters(null, null, emptyList(), null))

    val results = combine(
        searchfilters,
        searchQuery.debounce(100),
        searchImage,
        weightsSearch
    ) { filters, query, image, _ ->
        filters to (query to image)
    }
        .distinctUntilChanged()
        .toSearchStates { (filters, query) ->
            val textQuery = query.first
            val imageUri = query.second
            if (imageUri == null && textQuery.isBlank()) {
                return@toSearchStates emptyList()
            }
            val allImages = repo.indexedImagesFiltered(filters)
            if (allImages.isEmpty()) {
                return@toSearchStates emptyList()
            }
            if (imageUri == null) {
                repo.ensureTextModelInitialized()
                repo.search(textQuery, allImages, filters.confidence.score)
            } else {
                repo.searchByImage(application, imageUri, allImages)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchState.Success(emptyList()))

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setSearchFilters(searchFilters: SearchFilters) {
        this.searchfilters.value = searchFilters
    }

    fun setSearchImage(image: Uri?) {
        searchImage.value = image
    }

    fun setSearchLayout(searchLayout: SearchLayout) {
        this.searchLayout.value = searchLayout
    }
}

internal fun <T> Flow<T>.toSearchStates(
    search: suspend (T) -> List<Pair<PhotoEntity, Float>>
): Flow<SearchState> = flatMapLatest { input ->
    flow<SearchState> {
        emit(SearchState.Loading)
        emit(SearchState.Success(search(input)))
    }.catch { e ->
        emit(SearchState.Error(e.message ?: "search failed"))
    }
}
