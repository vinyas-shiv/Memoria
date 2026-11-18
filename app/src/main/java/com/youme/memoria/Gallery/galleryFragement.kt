package com.youme.memoria.Gallery

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.youme.memoria.MemoriaApp
import com.youme.memoria.PhotoRepository
import com.youme.memoria.R
import com.youme.memoria.imageViewer.imageViewerActivity
import com.youme.memoria.search.searchActivity
import com.youme.memoria.settings.SettingsPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class GalleryFragement : Fragment(R.layout.gallery_layout) {

    private lateinit var Adapter: GalleryDateAdapter

    private lateinit var repo: PhotoRepository
    private var observer: GalleryObserver? = null
    private var imageEncodingJob: Job? = null
    private val imglist = mutableListOf<Uri>()

    private val indexingViewModel: IndexingViewModel by activityViewModels {
        val app = requireContext().applicationContext as MemoriaApp
        IndexingViewModelFactory(app.repo, app)
    }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            indexingViewModel.startIndexing()
            if (!granted) Toast.makeText(requireContext(), "started indexing without notification", Toast.LENGTH_SHORT).show()
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = (requireContext().applicationContext as MemoriaApp).repo

        Adapter = GalleryDateAdapter { uri ->
            viewLifecycleOwner.lifecycleScope.launch {
                val pos = repo.getImagePosition(requireContext(), uri)
                val imageId = if (uri.scheme == "content") {
                    runCatching { ContentUris.parseId(uri) }.getOrDefault(-1L)
                } else -1L
                val intent = Intent(requireContext(), imageViewerActivity::class.java)
                intent.putExtra("pos", pos)
                intent.putExtra("imageId", imageId)
                startActivity(intent)
            }
        }

        val recyclerView = view.findViewById<RecyclerView>(R.id.rec)
        val layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)

        recyclerView.apply {
            this.layoutManager = layoutManager
            itemAnimator = null
            setItemViewCacheSize(6)
            adapter = Adapter
        }

        val contentObserver = GalleryObserver(Handler(Looper.getMainLooper()))
        observer = contentObserver
        requireContext().contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver
        )

        loadGallery()
        indexingState()
        navigateToSearch()
    }

    override fun onDestroyView() {
        observer?.let {
            runCatching { requireContext().contentResolver.unregisterContentObserver(it) }
        }
        observer = null
        super.onDestroyView()
    }

    private fun loadGallery() {
        val viewModel = GalleryViewModel(
            GalleryRepository(requireContext().contentResolver, requireContext().applicationContext)
        )
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.galleryFlow.collectLatest { pagingData ->
                        Adapter.submitData(pagingData)
                    }
                }
                launch {
                    indexingViewModel.folderPrefs.selectedBuckets.collectLatest {
                        Adapter.refresh()
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else indexingViewModel.startIndexing()
        } else {
            indexingViewModel.startIndexing()
        }
    }

    private fun indexingState() {
        val toolbar = requireView().findViewById<MaterialToolbar>(R.id.toolbar)

        val largeIndicatorBox = requireView().findViewById<MaterialCardView>(R.id.big_indicator)
        val logText = requireView().findViewById<TextView>(R.id.textView2)
        val processButton = requireView().findViewById<MaterialButton>(R.id.button)
        val progressbar = requireView().findViewById<LinearProgressIndicator>(R.id.progressbar)

        val smallIndicatorBox = requireView().findViewById<MaterialCardView>(R.id.small_indicator)
        val smallIndicatorText = requireView().findViewById<TextView>(R.id.textView12)
        val smallIndicatorProgress = requireView().findViewById<LinearProgressIndicator>(R.id.prog2)

        val collapseButton = requireView().findViewById<Button>(R.id.button5)

        collapseButton.setOnClickListener {
            animateIndicator(largeIndicatorBox, smallIndicatorBox)
            toolbar.title = ""
        }
        smallIndicatorBox.setOnClickListener {
            animateIndicator(smallIndicatorBox, largeIndicatorBox, toolbar)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            launch {
                SettingsPrefs.getIndicatorStatus(requireContext()).collectLatest { status ->
                    largeIndicatorBox.isVisible = !status
                    smallIndicatorBox.isVisible = status
                    toolbar.title = if (status) "" else "WAmemoria"
                }
            }

            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                indexingViewModel.state.collectLatest { state ->
                    when (state) {
                        is IndexingViewModel.IndexingState.Ready -> {
                            processButton.setIconResource(R.drawable.baseline_play_arrow_24)
                            logText.text = "Indexed : ${state.processed}/${state.total}"

                            smallIndicatorText.text = "Paused"

                            progressbar.apply {
                                isIndeterminate = false
                                max = state.total
                                progress = state.processed
                            }
                            smallIndicatorProgress.apply {
                                max = state.total
                                progress = state.processed
                            }

                            processButton.isVisible = true
                            processButton.setOnClickListener {
                                requestNotificationPermissionIfNeeded()
                                logText.text = "Loading"
                                progressbar.isIndeterminate = true
                            }
                        }
                        is IndexingViewModel.IndexingState.Running -> {
                            logText.text = "Indexing: ${state.processed}/${state.total}"
                            smallIndicatorText.text = "${state.processed}/${state.total}"

                            processButton.setIconResource(R.drawable.baseline_pause_24)
                            processButton.isVisible = true
                            processButton.setOnClickListener {
                                indexingViewModel.pause()
                                logText.text = "Indexed : ${state.processed}/${state.total}"
                            }

                            progressbar.isVisible = true
                            progressbar.isIndeterminate = false

                            progressbar.apply {
                                max = state.total
                                progress = state.processed
                            }
                            smallIndicatorProgress.apply {
                                max = state.total
                                progress = state.processed
                            }
                        }
                        is IndexingViewModel.IndexingState.Completed -> {
                            processButton.isVisible = false
                            logText.text = "Indexing completed: ${state.total}"
                            progressbar.isIndeterminate = false

                            progressbar.apply {
                                max = state.total
                                progress = state.total
                            }
                            smallIndicatorProgress.apply {
                                max = state.total
                                progress = state.total
                            }

                            smallIndicatorText.text = "Completed"
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun animateIndicator(visibleView: View, hiddenView: View, toolbar: MaterialToolbar? = null) {
        visibleView.animate().scaleX(0f).scaleY(0f).setDuration(200).withEndAction {
            visibleView.isVisible = false
            hiddenView.apply {
                isVisible = true
                scaleX = 0f
                scaleY = 0f
            }
            toolbar?.apply {
                title = "WAmemoria"
            }
            hiddenView.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        }.start()
    }

    private fun navigateToSearch() {
        val toolbar = requireView().findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.search -> {
                    indexingViewModel.pause()
                    val intent = Intent(requireContext(), searchActivity::class.java)
                    startActivity(intent)
                }
            }
            true
        }
    }
}
