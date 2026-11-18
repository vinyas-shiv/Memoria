package com.youme.memoria.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.youme.memoria.BuildConfig
import com.youme.memoria.Gallery.IndexingViewModel
import com.youme.memoria.Gallery.IndexingViewModelFactory
import com.youme.memoria.MediaScannerHelper
import com.youme.memoria.MemoriaApp
import com.youme.memoria.R
import com.youme.memoria.roomCach.IndexBackupManager
import com.youme.memoria.settings.FoldersManager.FoldersManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsFragment : Fragment(R.layout.settings_layout) {
    private val indexingViewModel: IndexingViewModel by activityViewModels {
        val app = requireContext().applicationContext as MemoriaApp
        IndexingViewModelFactory(app.repo, app)
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            indexingViewModel.startIndexing()
            if (!granted) Toast.makeText(requireContext(), "started indexing without notification", Toast.LENGTH_SHORT).show()
        }

    private val exportIndexLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { destUri: Uri? ->
            if (destUri != null) {
                performBackup(destUri)
            }
        }

    private val importIndexLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { srcUri: Uri? ->
            if (srcUri != null) {
                confirmAndPerformRestore(srcUri)
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupGeneralSettings()
        setupSpeedAndThermalSettings()
        setupFolders()
        setupBackupAndRestore()
        setupIndexingUi()
        setupUI()
        setUpAppVersion()
        openRepo()
    }

    private fun setupSpeedAndThermalSettings() {
        val chipGroup = requireView().findViewById<ChipGroup>(R.id.speedChipGroup)
        val chipEco = requireView().findViewById<Chip>(R.id.chipSpeedEco)
        val chipBalanced = requireView().findViewById<Chip>(R.id.chipSpeedBalanced)
        val chipFast = requireView().findViewById<Chip>(R.id.chipSpeedFast)
        val speedDesc = requireView().findViewById<TextView>(R.id.speedDescText)
        val chargingSwitch = requireView().findViewById<MaterialSwitch>(R.id.chargingCooldownSwitch)

        fun descriptionFor(speed: IndexingSpeed): String = when (speed) {
            IndexingSpeed.ECO -> "Eco: lowest heat for active phone use or warm rooms"
            IndexingSpeed.BALANCED -> "Balanced: smooth pacing to keep your phone comfortable"
            IndexingSpeed.FAST -> "Fast: maximum indexing speed when unplugged and cool"
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    SettingsPrefs.getIndexingSpeed(requireContext()).collectLatest { speed ->
                        val targetId = when (speed) {
                            IndexingSpeed.ECO -> chipEco.id
                            IndexingSpeed.BALANCED -> chipBalanced.id
                            IndexingSpeed.FAST -> chipFast.id
                        }
                        if (chipGroup.checkedChipId != targetId) {
                            chipGroup.check(targetId)
                        }
                        speedDesc.text = descriptionFor(speed)
                    }
                }
                launch {
                    SettingsPrefs.getChargingCooldown(requireContext()).collectLatest { enabled ->
                        if (chargingSwitch.isChecked != enabled) {
                            chargingSwitch.isChecked = enabled
                        }
                    }
                }
            }
        }

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val selected = when (checkedIds.firstOrNull()) {
                R.id.chipSpeedEco -> IndexingSpeed.ECO
                R.id.chipSpeedBalanced -> IndexingSpeed.BALANCED
                R.id.chipSpeedFast -> IndexingSpeed.FAST
                else -> return@setOnCheckedStateChangeListener
            }
            speedDesc.text = descriptionFor(selected)
            viewLifecycleOwner.lifecycleScope.launch {
                SettingsPrefs.setIndexingSpeed(requireContext(), selected)
            }
        }

        chargingSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewLifecycleOwner.lifecycleScope.launch {
                SettingsPrefs.setChargingCooldown(requireContext(), isChecked)
            }
        }
    }

    private fun setupBackupAndRestore() {
        val backupBtn = requireView().findViewById<MaterialButton>(R.id.backupIndexBtn)
        val restoreBtn = requireView().findViewById<MaterialButton>(R.id.restoreIndexBtn)

        backupBtn.setOnClickListener {
            indexingViewModel.pause()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            exportIndexLauncher.launch("wamemoria_index_$stamp.db")
        }

        restoreBtn.setOnClickListener {
            indexingViewModel.pause()
            importIndexLauncher.launch(arrayOf("*/*"))
        }
    }

    private fun performBackup(destUri: Uri) {
        val appCtx = requireContext().applicationContext
        val backupBtn = view?.findViewById<MaterialButton>(R.id.backupIndexBtn)
        backupBtn?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val result = IndexBackupManager.exportIndex(appCtx, destUri)
            if (!isAdded) return@launch
            view?.findViewById<MaterialButton>(R.id.backupIndexBtn)?.isEnabled = true
            result.onSuccess { count ->
                indexingViewModel.getFormattedDatabaseSize("photo_db")
                Toast.makeText(
                    appCtx,
                    "Backed up $count indexed photos successfully",
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    appCtx,
                    "Backup failed: ${err.message ?: "Unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun confirmAndPerformRestore(srcUri: Uri) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Restore Index Backup?")
            .setMessage("This will replace your current local photo index with the selected backup file and restart WAmemoria.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore") { _, _ ->
                val appCtx = requireContext().applicationContext
                val restoreBtn = view?.findViewById<MaterialButton>(R.id.restoreIndexBtn)
                restoreBtn?.isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = IndexBackupManager.importIndex(appCtx, srcUri)
                    if (!isAdded) return@launch
                    view?.findViewById<MaterialButton>(R.id.restoreIndexBtn)?.isEnabled = true
                    result.onSuccess { count ->
                        MediaScannerHelper.invalidateCache()
                        Toast.makeText(
                            appCtx,
                            "Restored $count indexed photos! Restarting...",
                            Toast.LENGTH_LONG
                        ).show()
                        delay(600)
                        val launchIntent = appCtx.packageManager
                            .getLaunchIntentForPackage(appCtx.packageName)
                            ?.apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            }
                        if (launchIntent != null) {
                            startActivity(launchIntent)
                        }
                        Runtime.getRuntime().exit(0)
                    }.onFailure { err ->
                        Toast.makeText(
                            appCtx,
                            "Restore failed: ${err.message ?: "Invalid backup file"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .show()
    }

    private fun setupUI() {
        val dBSizeText = requireView().findViewById<TextView>(R.id.db_size)
        val batteryStatusChip = requireView().findViewById<Chip>(R.id.chip)
        val alertText = requireView().findViewById<TextView>(R.id.textView4)
        indexingViewModel.getFormattedDatabaseSize("photo_db")

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    indexingViewModel.dbSize.collectLatest {
                        dBSizeText.text = it
                    }
                }

                launch {
                    indexingViewModel.batteryTemp.collectLatest { temp ->
                        batteryStatusChip.apply {
                            text = "$temp°C"

                            when {
                                temp < 35f -> {
                                    chipIconTint = ColorStateList.valueOf(Color.GREEN)
                                    alertText.isVisible = false
                                }

                                temp < 42f -> {
                                    val color = ColorStateList.valueOf("#FFA500".toColorInt())
                                    chipIconTint = color
                                    alertText.apply {
                                        text = "Running a bit slower to keep your phone cool"
                                        isVisible = true
                                        setTextColor(color)
                                    }
                                }

                                else -> {
                                    val color = ColorStateList.valueOf(Color.RED)
                                    chipIconTint = color
                                    alertText.apply {
                                        text =
                                            "Paused – your device is warm. Indexing will resume once it cools down."
                                        isVisible = true
                                        setTextColor(color)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openRepo() {
        val repoContainer = requireView().findViewById<MaterialCardView>(R.id.repo)
        repoContainer.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, "https://github.com/vinyas-shiv/Memoria".toUri())
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(requireContext(), "No browser found to open the link", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setUpAppVersion() {
        val versionName: String = BuildConfig.VERSION_NAME
        val versionText = requireView().findViewById<TextView>(R.id.version)
        versionText.text = versionName
    }

    private fun setupIndexingUi() {
        val progressLog = requireView().findViewById<TextView>(R.id.textView7)
        val progressBar = requireView().findViewById<LinearProgressIndicator>(R.id.progressBar2)
        val statusLog = requireView().findViewById<TextView>(R.id.textView6)
        val etaLog = requireView().findViewById<TextView>(R.id.textView5)

        val pauseBt = requireView().findViewById<MaterialButton>(R.id.button3)
        val resumeBt = requireView().findViewById<MaterialButton>(R.id.button4)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                indexingViewModel.state.collectLatest { state ->
                    try {
                        when (state) {
                            is IndexingViewModel.IndexingState.Ready -> {
                                statusLog.text = "Status: Ready"
                                progressLog.text = "${state.processed}/${state.total}"
                                progressBar.max = state.total
                                progressBar.progress = state.processed

                                etaLog.text = if (state.total > 0) "${(state.processed * 100) / state.total}%" else "0%"

                                resumeBt.apply {
                                    isEnabled = true
                                    setOnClickListener {
                                        progressLog.text = "Loading.."
                                        requestNotificationPermissionIfNeeded()
                                    }
                                }
                                pauseBt.isEnabled = false
                            }

                            is IndexingViewModel.IndexingState.Running -> {
                                statusLog.text = "Status: Running"
                                progressLog.text = "${state.processed}/${state.total}"
                                progressBar.max = state.total
                                progressBar.progress = state.processed

                                val pct = if (state.total > 0) (state.processed * 100) / state.total else 0
                                etaLog.apply {
                                    isVisible = true
                                    text = "$pct%  ETA: ${formatEta(state.etaMinutes.toLong())}"
                                }

                                pauseBt.apply {
                                    isEnabled = true
                                    setOnClickListener {
                                        indexingViewModel.pause()
                                    }
                                }
                                resumeBt.isEnabled = false
                            }

                            is IndexingViewModel.IndexingState.Idle -> {
                                statusLog.text = "Status: Idle"
                            }

                            is IndexingViewModel.IndexingState.Completed -> {
                                pauseBt.isEnabled = false
                                resumeBt.isEnabled = false
                                etaLog.isVisible = false
                                statusLog.text = "Status: Completed"
                                progressLog.text = "${state.total}/${state.total}"
                                progressBar.apply {
                                    max = state.total
                                    progress = state.total
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // not done yet
                    }
                }
            }
        }
    }

    private fun setupFolders() {
        val manageFoldersButton = requireView().findViewById<MaterialCardView>(R.id.manageFoldersButt)
        val foldersTitle = requireView().findViewById<TextView>(R.id.foldersTitle)

        manageFoldersButton.setOnClickListener {
            FoldersManager().show(parentFragmentManager, "")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    indexingViewModel.folders.collectLatest { folders ->
                        foldersTitle.text = "Manage Folders (${folders.size})"
                    }
                }

                launch {
                    indexingViewModel.getBatteryTemperature()
                }

                launch {
                    indexingViewModel.folderPrefs.selectedBuckets.collectLatest { selectedFolders ->
                        indexingViewModel.scanGallery()
                        view?.let {
                            it.findViewById<TextView>(R.id.textView33).text = "${selectedFolders.size} selected"
                        }
                    }
                }
            }
        }
    }

    private fun setupGeneralSettings() {
        val indicatorSwitch = requireView().findViewById<MaterialSwitch>(R.id.indictorSwitch)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SettingsPrefs.getIndicatorStatus(requireContext()).collectLatest { status ->
                    indicatorSwitch.isChecked = status
                }
            }
        }
        indicatorSwitch.setOnCheckedChangeListener { _, bool ->
            viewLifecycleOwner.lifecycleScope.launch {
                SettingsPrefs.setIndicatorStatus(requireContext(), bool)
            }
        }
    }

    private fun formatEta(etaMs: Long): String {
        if (etaMs <= 0) return "< 1s"
        val totalSeconds = etaMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }
}
