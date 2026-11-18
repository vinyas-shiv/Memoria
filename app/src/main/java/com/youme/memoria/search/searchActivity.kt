package com.youme.memoria.search

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.PagingData
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.loadingindicator.LoadingIndicator
import com.google.android.material.search.SearchBar
import com.google.android.material.search.SearchView
import com.yalantis.ucrop.UCrop
import com.youme.memoria.Album.deleteAlert
import com.youme.memoria.ImageLoading.ImagePagingAdapter
import com.youme.memoria.ImageLoading.ImageUriItem
import com.youme.memoria.R
import com.youme.memoria.imageViewer.imageViewerActivity
import com.youme.memoria.search.searchHistory.SearchHistroyAdapter
import com.youme.memoria.search.searchHistory.SearchHistroyViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class searchActivity : AppCompatActivity() {

    private lateinit var adapterDateSections: SearchDateAdapter
    private lateinit var adapterStaggeredGrid: ImagePagingAdapter
    private val searchViewModuel: SearchViewModuel by viewModels()
    private val searchHistroyViewModuel: SearchHistroyViewModel by viewModels()
    private lateinit var historyAdapter: SearchHistroyAdapter
    private var activeImageToolbar: View? = null

    private val cropLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            when (result.resultCode) {
                RESULT_OK -> {
                    val croppedUri: Uri? = result.data?.let { UCrop.getOutput(it) }
                    searchViewModuel.setSearchImage(croppedUri)
                }
                UCrop.RESULT_ERROR -> {
                    val error = result.data?.let { UCrop.getError(it) }
                    error?.printStackTrace()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.search_layout)
        setupPadding()

        adapterDateSections = SearchDateAdapter(
            onPhotoClick = { uri ->
                val pos = SearchResultCache.searchResults
                    ?.indexOfFirst { it.uri == uri }
                    ?.takeIf { it >= 0 } ?: 0
                val intent = Intent(this@searchActivity, imageViewerActivity::class.java).apply {
                    putExtra("pos", pos)
                    putExtra("from_search", true)
                }
                startActivity(intent)
            },
            onToggleExpand = { dayKey ->
                searchViewModuel.toggleDayExpanded(dayKey)
            }
        )

        adapterStaggeredGrid = ImagePagingAdapter { _, pos ->
            val intent = Intent(this@searchActivity, imageViewerActivity::class.java).apply {
                putExtra("pos", pos.coerceAtLeast(0))
                putExtra("from_search", true)
            }
            startActivity(intent)
        }

        val searchUri = intent.getStringExtra("uri")
        searchUri?.let {
            searchViewModuel.setSearchImage(it.toUri())
        }

        setupRecyclerViewAndModeChips()
        setupSearch()
        setupFilterObserver()
        setupImageSearch()
        setupTopBar()
        setupSearchHistory()
        handleShareIntent(intent)
    }

    private fun setupRecyclerViewAndModeChips() {
        val recyclerView = findViewById<RecyclerView>(R.id.rec)
        val layoutChipGroup = findViewById<ChipGroup>(R.id.layoutChipGroup)

        val staggeredLayoutManager = StaggeredGridLayoutManager(
            2,
            StaggeredGridLayoutManager.VERTICAL
        ).apply {
            gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS
        }
        val dateLinearLayoutManager = LinearLayoutManager(
            this@searchActivity,
            LinearLayoutManager.VERTICAL,
            false
        )

        recyclerView.itemAnimator = null
        recyclerView.setItemViewCacheSize(6)

        layoutChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chipDateBest -> searchViewModuel.setSearchLayout(SearchLayout.DATEBLOCKS)
                R.id.chipDateNewest -> searchViewModuel.setSearchLayout(SearchLayout.DATE_NEWEST)
                R.id.chipFlatGrid -> searchViewModuel.setSearchLayout(SearchLayout.STAGGERED)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchViewModuel.searchLayout.collectLatest { layout ->
                    val targetChipId = when (layout) {
                        SearchLayout.DATEBLOCKS -> R.id.chipDateBest
                        SearchLayout.DATE_NEWEST -> R.id.chipDateNewest
                        SearchLayout.STAGGERED -> R.id.chipFlatGrid
                    }
                    if (layoutChipGroup.checkedChipId != targetChipId) {
                        layoutChipGroup.check(targetChipId)
                    }

                    when (layout) {
                        SearchLayout.STAGGERED -> {
                            if (recyclerView.adapter !== adapterStaggeredGrid) {
                                recyclerView.layoutManager = staggeredLayoutManager
                                recyclerView.adapter = adapterStaggeredGrid
                            }
                        }
                        SearchLayout.DATEBLOCKS, SearchLayout.DATE_NEWEST -> {
                            if (recyclerView.adapter !== adapterDateSections) {
                                recyclerView.layoutManager = dateLinearLayoutManager
                                recyclerView.adapter = adapterDateSections
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setupFilterObserver() {
        val filtersTagsContainer = findViewById<ChipGroup>(R.id.chipGroup2)
        val controlsBar = findViewById<HorizontalScrollView>(R.id.controlsBar)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchViewModuel.searchfilters.collectLatest { searchFilters ->
                    filtersTagsContainer.removeAllViews()
                    searchFilters.folders.forEach { folder ->
                        val chip = Chip(this@searchActivity).apply {
                            text = folder.trimEnd('/').substringAfterLast("/")
                            isCloseIconVisible = true
                            setOnCloseIconClickListener {
                                val folderList = searchFilters.folders.toMutableList()
                                folderList.remove(folder)
                                searchViewModuel.setSearchFilters(
                                    searchViewModuel.searchfilters.value.copy(folders = folderList)
                                )
                            }
                        }
                        filtersTagsContainer.addView(chip)
                    }
                    if (searchFilters.folders.isNotEmpty()) {
                        controlsBar.isVisible = true
                    }
                }
            }
        }
    }

    private fun showSortDialog(anchor: View) {
        val popupView = layoutInflater.inflate(R.layout.dialog_sort, null)
        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.isOutsideTouchable = true

        val radioGroup = popupView.findViewById<RadioGroup>(R.id.radio_group)

        lifecycleScope.launch {
            when (searchViewModuel.searchLayout.first()) {
                SearchLayout.DATEBLOCKS -> radioGroup.check(R.id.dateBlocks)
                SearchLayout.DATE_NEWEST -> radioGroup.check(R.id.dateNewest)
                SearchLayout.STAGGERED -> radioGroup.check(R.id.staggerd)
            }

            radioGroup.setOnCheckedChangeListener { _, id ->
                when (id) {
                    R.id.dateBlocks -> searchViewModuel.setSearchLayout(SearchLayout.DATEBLOCKS)
                    R.id.dateNewest -> searchViewModuel.setSearchLayout(SearchLayout.DATE_NEWEST)
                    R.id.staggerd -> searchViewModuel.setSearchLayout(SearchLayout.STAGGERED)
                }
                popupWindow.dismiss()
            }
        }

        popupWindow.showAsDropDown(anchor)
    }

    private fun setupImageSearch() {
        val container = findViewById<LinearLayout>(R.id.linearLayout2)
        val searchToolBar = findViewById<AppBarLayout>(R.id.app_bar_layout)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchViewModuel.searchImage.collectLatest { image ->
                    activeImageToolbar?.let {
                        container.removeView(it)
                        activeImageToolbar = null
                    }
                    if (image != null) {
                        searchToolBar.isVisible = false
                        val imageToolbar =
                            layoutInflater.inflate(R.layout.image_search_topbar, container, false)
                        activeImageToolbar = imageToolbar
                        val closeButton = imageToolbar.findViewById<ImageButton>(R.id.imageButton5)
                        val imageview = imageToolbar.findViewById<ImageView>(R.id.imageView7)

                        Glide.with(this@searchActivity)
                            .load(image)
                            .skipMemoryCache(true)
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                            .into(imageview)

                        container.addView(imageToolbar)

                        closeButton.setOnClickListener {
                            container.removeView(imageToolbar)
                            activeImageToolbar = null
                            searchToolBar.isVisible = true
                            searchViewModuel.setSearchImage(null)
                            File(cacheDir, "cropped.jpg").delete()
                        }
                    } else {
                        searchToolBar.isVisible = true
                    }
                }
            }
        }
    }

    private fun cropImage(image: Uri) {
        val destinUri = Uri.fromFile(File(cacheDir, "cropped.jpg"))
        val options = UCrop.Options().apply {
            setCompressionFormat(Bitmap.CompressFormat.JPEG)
            setFreeStyleCropEnabled(true)
        }

        val intent = UCrop.of(image, destinUri)
            .withOptions(options)
            .useSourceImageAspectRatio()
            .getIntent(this)
        cropLauncher.launch(intent)
    }

    private fun setupTopBar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finishAfterTransition() }
        val pickedImage = registerForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                cropImage(uri)
            }
        }

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.image_search -> {
                    pickedImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
                R.id.filter -> {
                    FilterBottomFrag().show(supportFragmentManager, "")
                }
                R.id.sort_view -> {
                    toolbar.findViewById<View>(R.id.sort_view)?.let { anchor ->
                        showSortDialog(anchor)
                    }
                }
            }
            true
        }
    }

    private fun setupSearch() {
        val recyclerView = findViewById<RecyclerView>(R.id.rec)
        val searchBar = findViewById<SearchBar>(R.id.search_bar)
        val searchView = findViewById<SearchView>(R.id.search_view)
        val progressbar = findViewById<LoadingIndicator>(R.id.loading)
        val emptyState = findViewById<View>(R.id.emptyState)
        val controlsBar = findViewById<HorizontalScrollView>(R.id.controlsBar)

        searchView.setupWithSearchBar(searchBar)
        progressbar.isVisible = false

        var lastResultRef: List<*>? = null
        var lastLayout: SearchLayout? = null

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    searchViewModuel.results,
                    searchViewModuel.searchLayout,
                    searchViewModuel.expandedDays
                ) { state, layout, expandedSet ->
                    Triple(state, layout, expandedSet)
                }.collectLatest { (state, layout, expandedSet) ->
                    when (state) {
                        is SearchState.Loading -> {
                            progressbar.isVisible = true
                        }
                        is SearchState.Success -> {
                            progressbar.isVisible = false
                            val hasResults = state.results.isNotEmpty()
                            emptyState.isVisible = !hasResults
                            controlsBar.isVisible = hasResults ||
                                searchViewModuel.searchfilters.value.folders.isNotEmpty()

                            val isNewSearchOrLayout =
                                state.results !== lastResultRef || layout != lastLayout
                            val isNewSearch = state.results !== lastResultRef
                            lastResultRef = state.results
                            lastLayout = layout

                            val sortedGroups = when (layout) {
                                SearchLayout.DATEBLOCKS -> state.dateGroups.sortedByDescending { it.bestScore }
                                SearchLayout.DATE_NEWEST -> state.dateGroups.sortedByDescending { it.dayTimestamp }
                                SearchLayout.STAGGERED -> state.dateGroups
                            }

                            val displayGroups = sortedGroups.map { group ->
                                val expanded = group.dayKey in expandedSet && group.canExpand
                                if (group.isExpanded == expanded) group else group.copy(isExpanded = expanded)
                            }

                            val mappedFlatList = state.results.map { (photo, _) ->
                                ImageUriItem(
                                    id = photo.uri.hashCode().toLong(),
                                    uri = photo.uri.toUri(),
                                    width = photo.width.coerceAtLeast(1),
                                    height = photo.height.coerceAtLeast(1)
                                )
                            }

                            SearchResultCache.searchResults = when (layout) {
                                SearchLayout.STAGGERED -> mappedFlatList
                                SearchLayout.DATEBLOCKS, SearchLayout.DATE_NEWEST ->
                                    displayGroups.flatMap { it.displayedImages }
                            }

                            adapterDateSections.submitList(displayGroups) {
                                if (isNewSearchOrLayout && layout != SearchLayout.STAGGERED) {
                                    recyclerView.scrollToPosition(0)
                                }
                            }

                            if (isNewSearch) {
                                runCatching {
                                    (recyclerView.layoutManager as? StaggeredGridLayoutManager)
                                        ?.invalidateSpanAssignments()
                                }
                                adapterStaggeredGrid.submitData(lifecycle, PagingData.from(mappedFlatList))
                            }
                            if (isNewSearchOrLayout && layout == SearchLayout.STAGGERED) {
                                recyclerView.post { recyclerView.scrollToPosition(0) }
                            }
                        }
                        is SearchState.Error -> {
                            progressbar.isVisible = false
                            Log.d("searcherror", "setupSearch: ${state.message}")
                            Toast.makeText(
                                this@searchActivity,
                                "Search failed. Please try again.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }

        searchView.getEditText().setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = searchView.getEditText().text.toString()
                adapterDateSections.submitList(emptyList())
                lifecycleScope.launch {
                    adapterStaggeredGrid.submitData(PagingData.from(emptyList()))
                }

                searchViewModuel.setSearchQuery(query)
                if (query.isNotBlank()) {
                    searchHistroyViewModuel.addToHistroy(query)
                }
                searchBar.setText(query)
                searchView.hide()
                true
            } else {
                false
            }
        }
    }

    private fun setupSearchHistory() {
        val searchBar = findViewById<SearchBar>(R.id.search_bar)
        val searchView = findViewById<SearchView>(R.id.search_view)
        val clearHistoryButton = findViewById<Button>(R.id.button19)

        historyAdapter = SearchHistroyAdapter(
            onPinnedClicked = { query, isPinned ->
                searchHistroyViewModuel.setPinned(query, !isPinned)
            },
            onClick = { query ->
                searchViewModuel.setSearchQuery(query)
                searchHistroyViewModuel.addToHistroy(query)
                searchBar.setText(query)
                searchView.hide()
            },
            onLongClick = { query ->
                deleteAlert(
                    this@searchActivity,
                    "Delete Search Item",
                    "Are you sure you want to delete \"$query\" from your search history? This action cannot be undone."
                ) {
                    searchHistroyViewModuel.deleteHistoryItem(query)
                }
            }
        )

        val recyclerView = findViewById<RecyclerView>(R.id.historyRec)
        recyclerView.apply {
            adapter = historyAdapter
            layoutManager = LinearLayoutManager(this@searchActivity, LinearLayoutManager.VERTICAL, false)
        }

        clearHistoryButton.setOnClickListener {
            deleteAlert(
                this@searchActivity,
                "Clear Search History",
                "Are you sure you want to delete all your search history? This action cannot be undone."
            ) {
                searchHistroyViewModuel.deleteHistory()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchHistroyViewModuel.searchHistory.collectLatest { history ->
                    historyAdapter.submitData(history)
                }
            }
        }
    }

    override fun onDestroy() {
        SearchResultCache.searchResults = null
        super.onDestroy()
    }

    private fun handleShareIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            imageUri?.let { cropImage(it) }
        }
    }

    private fun setupPadding() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.linearLayout2)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            params.topMargin = systemBars.top
            view.layoutParams = params
            insets
        }
    }
}
