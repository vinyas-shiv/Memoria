package com.youme.memoria.Gallery

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.youme.memoria.ImageSizeUtil
import com.youme.memoria.MainActivity
import com.youme.memoria.MediaScannerHelper
import com.youme.memoria.MemoriaApp
import com.youme.memoria.R
import com.youme.memoria.settings.IndexingSpeed
import com.youme.memoria.settings.SettingsPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

class IndexingWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val repo = (context.applicationContext as MemoriaApp).repo

    @Volatile private var currentSpeed: IndexingSpeed = IndexingSpeed.BALANCED
    @Volatile private var chargingCooldownEnabled: Boolean = true

    override suspend fun getForegroundInfo() = foregroundInfo(0, 0, currentSpeed)

    private suspend fun refreshSpeedPrefs() {
        val appCtx = context.applicationContext
        currentSpeed = SettingsPrefs.getIndexingSpeed(appCtx).first()
        chargingCooldownEnabled = SettingsPrefs.getChargingCooldown(appCtx).first()
    }

    override suspend fun doWork(): Result {
        refreshSpeedPrefs()
        setForeground(foregroundInfo(0, 0, currentSpeed))
        try {
            MediaScannerHelper.invalidateCache()
            val existing: HashSet<String> =
                repo.alreadyExistsList().mapTo(HashSet()) { it.uri }
            val all = repo.getImagesToIndex(context).map { it.toString() }
            val toProcess = all.filter { it !in existing }.map { it.toUri() }
            var done = existing.count { it in all }

            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to all.size, KEY_ETA to 0L))

            var prvEtaMs = 0L
            var lastUiUpdateMs = SystemClock.elapsedRealtime()
            repo.initializeImageModel()
            var avgMsPerImage = 0f

            toProcess.forEachIndexed { index, uri ->
                if (isStopped) return Result.success()
                coroutineContext.ensureActive()
                if (index % 5 == 0) {
                    refreshSpeedPrefs()
                }
                val iterStartMs = SystemClock.elapsedRealtime()

                try {
                    val (encodedImage, sizeData) = repo.encodeImageWithDimensions(context, uri)
                    val (dateTaken, folderPath, cameraInfo) =
                        ImageSizeUtil.getCombinedMetadata(context, uri)
                    repo.insertEmbeddingFast(
                        uri = uri.toString(),
                        embedding = encodedImage,
                        height = sizeData.second,
                        width = sizeData.first,
                        dateTaken = dateTaken,
                        folderPath = folderPath,
                        cameraMake = cameraInfo.first,
                        cameraModel = cameraInfo.second
                    )

                    done++
                    existing.add(uri.toString())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e("ImageEncoding", "Failed: $uri", e)
                    repo.markAsFailed(uri.toString())
                }

                // Apply user-configured speed pacing + charging/thermal protection before measuring full-step duration
                applySpeedAndThermalPacing(index == toProcess.lastIndex)

                val iterTook = (SystemClock.elapsedRealtime() - iterStartMs).toFloat()
                avgMsPerImage = if (avgMsPerImage == 0f) iterTook else avgMsPerImage * 0.9f + iterTook * 0.1f

                val nowElapsed = SystemClock.elapsedRealtime()
                if (nowElapsed - lastUiUpdateMs >= UI_UPDATE_INTERVAL_MS || index == toProcess.lastIndex) {
                    lastUiUpdateMs = nowElapsed
                    prvEtaMs = (avgMsPerImage * (toProcess.size - index - 1)).toLong()
                    setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to all.size, KEY_ETA to prvEtaMs))
                    setForeground(foregroundInfo(done, all.size, currentSpeed))
                }
            }
            return Result.success(workDataOf(KEY_DONE to done))
        } finally {
            withContext(NonCancellable) { repo.unloadImageModel() }
        }
    }

    private fun readBatteryState(): Pair<Float, Boolean> {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempTenths = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return (tempTenths / 10.0f) to (plugged != 0)
    }

    fun getThermalStatus(tempCelsius: Float): ThermalStatus {
        return when {
            tempCelsius < 35f -> ThermalStatus.GREEN
            tempCelsius < 42f -> ThermalStatus.ORANGE
            else -> ThermalStatus.RED
        }
    }

    fun getBatteryTemperature(): Float = readBatteryState().first

    private suspend fun applySpeedAndThermalPacing(isLastItem: Boolean) {
        if (isLastItem) return

        val speed = currentSpeed
        val (tempC, isPlugged) = readBatteryState()
        val extraChargeThrottle = isPlugged && chargingCooldownEnabled

        val baseDelayMs = when (speed) {
            IndexingSpeed.ECO -> if (extraChargeThrottle) 600L else 350L
            IndexingSpeed.BALANCED -> if (extraChargeThrottle) 280L else 120L
            IndexingSpeed.FAST -> if (extraChargeThrottle) 140L else 0L
        }

        val redPauseThreshold = when {
            extraChargeThrottle || speed == IndexingSpeed.ECO -> 38.5f
            speed == IndexingSpeed.BALANCED -> 40.0f
            else -> 42.0f
        }
        val orangeSlowThreshold = if (extraChargeThrottle || speed == IndexingSpeed.ECO) 34.5f else 35.0f

        if (tempC >= redPauseThreshold) {
            val resumeBelow = redPauseThreshold - 1.0f
            while (readBatteryState().first >= resumeBelow) {
                if (isStopped) return
                delay(4000.milliseconds)
            }
        } else if (tempC >= orangeSlowThreshold) {
            val thermalExtraMs = when (speed) {
                IndexingSpeed.ECO -> 300L
                IndexingSpeed.BALANCED -> 200L
                IndexingSpeed.FAST -> 120L
            }
            delay((baseDelayMs + thermalExtraMs).milliseconds)
        } else if (baseDelayMs > 0L) {
            delay(baseDelayMs.milliseconds)
        }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            applicationContext,
            2,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun foregroundInfo(done: Int, total: Int, speed: IndexingSpeed): ForegroundInfo {
        val pauseIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_image_search_24)
            .setContentTitle("Indexing photos (${speed.label})")
            .setContentText("$done / $total")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .addAction(R.drawable.baseline_pause_24, "Pause", pauseIntent)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, notif)
    }

    companion object {
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ETA = "eta"
        const val CHANNEL_ID = "indexing"
        const val NOTIF_ID = 42
        const val UNIQUE_NAME = "photo_indexing"
        private const val UI_UPDATE_INTERVAL_MS = 400L
    }
}
