package com.youme.memoria.settings.FoldersManager

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.youme.memoria.Gallery.IndexingViewModel
import com.youme.memoria.MediaScannerHelper
import com.youme.memoria.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class FoldersManager : BottomSheetDialogFragment(R.layout.folders_manager_layout) {
    override fun getTheme(): Int = R.style.Theme_Memoria_BottomSheet
    private val indexingViewModel: IndexingViewModel by activityViewModels()
    private lateinit var Adapter: FoldersAdapter

    @SuppressLint("StringFormatInvalid")
    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet =
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return
        bottomSheet.let {
            val behavior = BottomSheetBehavior.from(it)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.peekHeight = Resources.getSystem().displayMetrics.heightPixels
            it.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            behavior.skipCollapsed = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val folderPref = indexingViewModel.folderPrefs
        val saveButton = requireView().findViewById<MaterialButton>(R.id.button17)
        Adapter = FoldersAdapter(lifecycleScope)

        setupAllFilesAccessBanner()
        setupSearch()
        setupRecyclerView()
        setupSelectionButtons()

        saveButton.setOnClickListener {
            lifecycleScope.launch {
                indexingViewModel.pause()
                folderPref.setSelectedBuckets(Adapter.selectedFoldersFlow.value)
                indexingViewModel.scanGallery()
                dismiss()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    folderPref.selectedBuckets.collectLatest { selectedBuckets ->
                        Adapter.setSelected(selectedBuckets)
                    }
                }
                launch {
                    combine(
                        folderPref.selectedBuckets,
                        Adapter.selectedFoldersFlow
                    ) { savedInDatastore, currentInAdapter ->
                        savedInDatastore != currentInAdapter
                    }.collectLatest { isDifferent ->
                        saveButton.isVisible = isDifferent
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAllFilesAccessBannerVisibility()
        viewLifecycleOwner.lifecycleScope.launch {
            indexingViewModel.getAvailableFolders()
        }
    }

    private fun setupAllFilesAccessBanner() {
        val grantBtn = requireView().findViewById<MaterialButton>(R.id.grantAllFilesBtn)
        updateAllFilesAccessBannerVisibility()
        grantBtn.setOnClickListener {
            try {
                startActivity(MediaScannerHelper.createAllFilesAccessIntent(requireContext()))
            } catch (_: ActivityNotFoundException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        }
    }

    private fun updateAllFilesAccessBannerVisibility() {
        val banner = view?.findViewById<MaterialCardView>(R.id.allFilesBanner) ?: return
        banner.isVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !MediaScannerHelper.hasAllFilesAccess()
    }

    private fun setupSearch() {
        val searchInputText = requireView().findViewById<TextView>(R.id.searchInput)
        searchInputText.doAfterTextChanged { query ->
            Adapter.search(query.toString())
        }
    }

    private fun setupRecyclerView() {
        val recyclerView = requireView().findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.apply {
            layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
            adapter = Adapter
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                indexingViewModel.folders.collectLatest { folders ->
                    Adapter.submitData(folders)
                    requireView().findViewById<View>(R.id.progress).isVisible = false
                }
            }
        }
    }

    private fun setupSelectionButtons() {
        val selecteAllButton = requireView().findViewById<Button>(R.id.button14)
        val deselecteAllButton = requireView().findViewById<Button>(R.id.button18)

        selecteAllButton.setOnClickListener {
            Adapter.setAllSelected()
        }
        deselecteAllButton.setOnClickListener {
            Adapter.setAllDeselected()
        }
    }
}
