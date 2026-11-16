package com.youme.memoria.imageViewer

import android.content.ContentResolver
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingData
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomappbar.BottomAppBar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.youme.memoria.Album.AlbumViewModel
import com.youme.memoria.Gallery.GalleryRepository
import com.youme.memoria.Gallery.GalleryViewModel
import com.youme.memoria.MediaScannerHelper
import com.youme.memoria.R
import com.youme.memoria.imageViewer.Album.AddToAlbumBottomSheet
import com.youme.memoria.search.SearchResultCache
import com.youme.memoria.search.searchActivity
import com.youme.memoria.settings.FoldersManager.FolderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class imageViewerActivity : AppCompatActivity() {

    private val albumViewModel: AlbumViewModel by viewModels()

    private lateinit var Adatper: ImagePagerAdapter
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.imageview_activity)
        Adatper = ImagePagerAdapter({
            toggleBarsVisibility()
        }, {})

        setupViewPager()
        setupUi()
        setupPadding()
        setupBottomAppBar()
    }

    private fun setupUi() {
        val viewpager2 = findViewById<ViewPager2>(R.id.viewpager)
        val toolbar = findViewById<MaterialToolbar>(R.id.materialToolbar3)
        val swipeDownLayout = findViewById<SwipeDownLayout>(R.id.swipeDown)
        val progress = findViewById<CircularProgressIndicator>(R.id.progress)

        swipeDownLayout.onDismiss = {
            finish()
            overridePendingTransition(0, android.R.anim.fade_out)
        }

        swipeDownLayout.onDragProgress = { progres, _ ->
            findViewById<MaterialCardView>(R.id.progressBox).apply {
                isVisible = (progres * 100) > 0
                setCardBackgroundColor(
                    if (progress.progress == progress.max) resources.getColor(R.color.primary_80)
                    else Color.TRANSPARENT
                )
            }
            progress.progress = (progres * 100).toInt()
        }

        toolbar.setNavigationOnClickListener {
            finish()
        }

        Adatper.addOnPagesUpdatedListener {
            toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.find_similar -> {
                        val intent = Intent(this@imageViewerActivity, searchActivity::class.java)
                        intent.putExtra("uri", Adatper.peek(viewpager2.currentItem)?.uri.toString())
                        startActivity(intent)
                        true
                    }
                    R.id.show_albums -> {
                        val currentItem = Adatper.peek(viewpager2.currentItem)
                        AddToAlbumBottomSheet(currentItem?.uri.toString()).show(supportFragmentManager, "")
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun toggleBarsVisibility() {
        val bottomBar = findViewById<BottomAppBar>(R.id.bottomAppBar)
        val toolbar = findViewById<MaterialToolbar>(R.id.materialToolbar3)
        toolbar.isVisible = !toolbar.isVisible
        bottomBar.isVisible = !bottomBar.isVisible
    }

    private fun setupViewPager() {
        val viewpager2 = findViewById<ViewPager2>(R.id.viewpager)
        viewpager2.adapter = Adatper
        val tappedPosition = intent.getIntExtra("pos", 0)
        val isFromSearch = intent.getBooleanExtra("from_search", false)

        lifecycleScope.launch {
            if (isFromSearch) {
                val searchData = SearchResultCache.searchResults
                if (searchData != null) {
                    Adatper.submitData(PagingData.from(searchData))
                }
            } else {
                val totalCount = queryTotalGalleryCount()
                val viewModel = GalleryViewModel(GalleryRepository(contentResolver, applicationContext))
                viewModel.imageViewerFlow(tappedPosition, totalCount).collectLatest {
                    Adatper.submitData(it)
                }
            }
        }

        var isPositionSet = false
        Adatper.addOnPagesUpdatedListener {
            if (!isPositionSet && Adatper.itemCount > tappedPosition) {
                isPositionSet = true
                viewpager2.post {
                    viewpager2.setCurrentItem(tappedPosition, false)
                }
            }
        }
    }

    private fun setupBottomAppBar() {
        val viewpager2 = findViewById<ViewPager2>(R.id.viewpager)
        val bottomBar = findViewById<BottomAppBar>(R.id.bottomAppBar)
        val fabButton = findViewById<FloatingActionButton>(R.id.openImage)
        fabButton.setOnClickListener {
            val rawUri = Adatper.peek(viewpager2.currentItem)?.uri ?: return@setOnClickListener
            val safeUri = MediaScannerHelper.toShareableUri(this, rawUri)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(safeUri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        }

        bottomBar.setOnMenuItemClickListener { item ->
            val imageUri = Adatper.peek(viewpager2.currentItem)?.uri
            when (item.itemId) {
                R.id.share -> {
                    imageUri?.let { shareImage(it) }
                    true
                }
                else -> false
            }
        }
    }

    fun shareImage(imageUri: Uri) {
        val safeUri = MediaScannerHelper.toShareableUri(this, imageUri)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, safeUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share image"))
    }

    private suspend fun queryTotalGalleryCount(): Int = withContext(Dispatchers.IO) {
        val selectedBuckets = FolderPrefs(applicationContext).selectedBuckets.first()
        if (selectedBuckets.any { it.startsWith(MediaScannerHelper.FS_BUCKET_PREFIX) }) {
            MediaScannerHelper.getUnifiedGalleryEntries(applicationContext, selectedBuckets).size
        } else {
            queryAllImageIds(contentResolver).size
        }
    }

    suspend fun queryAllImageIds(contentResolver: ContentResolver): LongArray =
        withContext(Dispatchers.IO) {
            val ids = ArrayList<Long>()
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                null, null,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media._ID} DESC"
            )?.use {
                while (it.moveToNext()) ids.add(it.getLong(0))
            }
            ids.toLongArray()
        }

    private fun setupPadding() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            params.topMargin = systemBars.top
            view.layoutParams = params
            insets
        }
    }
}
