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

enum class SearchLayout { DATEBLOCKS, DATE_NEWEST, STAGGERED }

sealed class SearchState {
    object Loading : SearchState()
    data class Success(
        val results: List<Pair<PhotoEntity, Float>>,
        val dateGroups: List<SearchDateGroup> = emptyList()
    ) : SearchState()
    data class Error(val message: String) : SearchState()
}

class SearchViewModuel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as MemoriaApp).repo
    private val searchQuery = MutableStateFlow("")
    val searchImage = MutableStateFlow<Uri?>(null)

    val searchLayout = MutableStateFlow<SearchLayout>(SearchLayout.DATEBLOCKS)
    val expandedDays = MutableStateFlow<Set<String>>(emptySet())

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
                return@toSearchStates emptyList<Pair<PhotoEntity, Float>>() to emptyList<SearchDateGroup>()
            }
            val allImages = repo.indexedImagesFiltered(filters)
            if (allImages.isEmpty()) {
                return@toSearchStates emptyList<Pair<PhotoEntity, Float>>() to emptyList<SearchDateGroup>()
            }
            val matched = if (imageUri == null) {
                repo.ensureTextModelInitialized()
                repo.search(textQuery, allImages, filters.confidence.score)
            } else {
                repo.searchByImage(application, imageUri, allImages, filters.confidence)
            }
            val dateGroups = repo.buildSearchDateGroups(matched)
            matched to dateGroups
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            SearchState.Success(emptyList(), emptyList())
        )

    fun setSearchQuery(query: String) {
        if (searchQuery.value != query) {
            expandedDays.value = emptySet()
        }
        searchQuery.value = query
    }

    fun setSearchFilters(searchFilters: SearchFilters) {
        this.searchfilters.value = searchFilters
    }

    fun setSearchImage(image: Uri?) {
        if (searchImage.value != image) {
            expandedDays.value = emptySet()
        }
        searchImage.value = image
    }

    fun setSearchLayout(searchLayout: SearchLayout) {
        this.searchLayout.value = searchLayout
    }

    fun toggleDayExpanded(dayKey: String) {
        val current = expandedDays.value
        expandedDays.value = if (dayKey in current) current - dayKey else current + dayKey
    }
}

internal fun <T> Flow<T>.toSearchStates(
    search: suspend (T) -> Pair<List<Pair<PhotoEntity, Float>>, List<SearchDateGroup>>
): Flow<SearchState> = flatMapLatest { input ->
    flow<SearchState> {
        emit(SearchState.Loading)
        val (matches, groups) = search(input)
        emit(SearchState.Success(matches, groups))
    }.catch { e ->
        emit(SearchState.Error(e.message ?: "search failed"))
    }
}
