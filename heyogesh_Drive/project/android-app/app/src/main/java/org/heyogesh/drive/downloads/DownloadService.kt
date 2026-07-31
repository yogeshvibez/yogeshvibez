package org.heyogesh.drive.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.heyogesh.drive.R
import org.heyogesh.drive.api.DriveApi
import org.heyogesh.drive.util.Formatters
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A foreground service owns all bytes written to MediaStore Downloads. It uses
 * HTTP Range on resume and keeps each queue record in SQLite for process death.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var database: DownloadDatabase
    private lateinit var api: DriveApi
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var loop: Job? = null
    @Volatile private var activeId: Long? = null
    @Volatile private var activeCall: okhttp3.Call? = null

    override fun onCreate() {
        super.onCreate()
        database = DownloadDatabase(applicationContext)
        database.requeueInterrupted()
        api = DriveApi(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Preparing downloads", null, -1, true))
        when (intent?.action) {
            ACTION_CANCEL -> cancel(intent.getLongExtra(EXTRA_ID, -1))
            ACTION_PAUSE -> if (intent.getLongExtra(EXTRA_ID, -1) == activeId) activeCall?.cancel()
        }
        ensureLoop()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { activeCall?.cancel(); scope.cancel(); super.onDestroy() }

    private fun ensureLoop() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                val record = database.nextQueued() ?: break
                activeId = record.id
                process(record)
                activeId = null
                activeCall = null
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun process(initial: DownloadRecord) {
        var record = database.get(initial.id) ?: return
        try {
            if (record.archiveId != null && record.url == null) {
                database.updateState(record.id, DownloadState.PREPARING)
                broadcast()
                val ready = waitForArchive(record)
                if (!ready) return
                record = database.get(record.id) ?: return
            }
            if (database.get(record.id)?.state != DownloadState.QUEUED && database.get(record.id)?.state != DownloadState.PREPARING) return
            download(record)
        } catch (error: Exception) {
            val current = database.get(initial.id)
            if (current?.state !in setOf(DownloadState.PAUSED, DownloadState.CANCELLED)) {
                database.updateState(initial.id, DownloadState.FAILED, error.message ?: "Download failed.")
                broadcast()
            }
        }
    }

    private suspend fun waitForArchive(record: DownloadRecord): Boolean {
        while (scope.isActive) {
            when (database.get(record.id)?.state) {
                DownloadState.PAUSED, DownloadState.CANCELLED -> return false
            }
            val status = api.archiveStatus(record.archiveId ?: return false)
            when (status.state) {
                "ready" -> {
                    database.updateUrl(record.id, api.absoluteApiPath(status.downloadPath ?: error("Archive link missing.")))
                    database.progress(record.id, 0, -1, 0, -1)
                    database.updateState(record.id, DownloadState.QUEUED)
                    broadcast()
                    return true
                }
                "failed", "cancelled" -> throw IOException(status.error?.message ?: "Archive preparation failed.")
                else -> {
                    database.progress(record.id, status.processedBytes, status.sourceBytes, 0, -1)
                    startForeground(NOTIFICATION_ID, notification("Preparing ${record.title}", record, status.sourceBytes, false, status.processedBytes))
                    broadcast()
                    delay(1_250)
                }
            }
        }
        return false
    }

    private fun download(record: DownloadRecord) {
        val current = database.get(record.id) ?: return
        if (current.state == DownloadState.PAUSED || current.state == DownloadState.CANCELLED) return
        val destination = current.destinationUri?.let(Uri::parse) ?: createDownload(current).also { database.setDestination(current.id, it.toString()) }
        val existing = current.downloadedBytes
        val request = Request.Builder().url(current.url ?: throw IOException("Download URL unavailable."))
            .header("Authorization", "Bearer ${api.currentToken()}")
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .get().build()
        activeCall = client.newCall(request)
        activeCall!!.execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned HTTP ${response.code}.")
            if (existing > 0 && response.code != 206) {
                contentResolver.delete(destination, null, null)
                database.reset(current.id)
                database.updateState(current.id, DownloadState.QUEUED)
                return
            }
            val body = response.body ?: throw IOException("The server returned no download data.")
            val total = body.contentLength().takeIf { it >= 0 }?.plus(existing) ?: -1
            database.updateState(current.id, DownloadState.RUNNING)
            var downloaded = existing
            var lastBytes = downloaded
            var lastAt = System.currentTimeMillis()
            contentResolver.openOutputStream(destination, if (existing > 0) "wa" else "w")?.use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        val state = database.get(current.id)?.state
                        if (state != DownloadState.RUNNING) return
                        output.write(buffer, 0, count)
                        downloaded += count
                        val now = System.currentTimeMillis()
                        if (now - lastAt >= REPORT_EVERY_MS) {
                            val speed = ((downloaded - lastBytes) * 1000 / (now - lastAt)).coerceAtLeast(0)
                            val eta = if (total > 0 && speed > 0) ((total - downloaded) / speed).coerceAtLeast(0) else -1
                            database.progress(current.id, downloaded, total, speed, eta)
                            updateProgressNotification(current, downloaded, total, speed)
                            broadcast()
                            lastAt = now
                            lastBytes = downloaded
                        }
                    }
                }
            } ?: throw IOException("Android could not open the Downloads folder.")
            val finishedState = database.get(current.id)?.state
            if (finishedState == DownloadState.RUNNING) {
                contentResolver.update(destination, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                database.progress(current.id, downloaded, total, 0, 0)
                database.updateState(current.id, DownloadState.COMPLETED)
                broadcast()
            }
        }
    }

    private fun createDownload(record: DownloadRecord): Uri {
        val uniqueName = record.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, uniqueName)
            put(MediaStore.Downloads.MIME_TYPE, record.mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Android could not create a Downloads file.")
    }

    private fun cancel(id: Long) {
        if (id < 0) return
        database.get(id)?.destinationUri?.let { contentResolver.delete(Uri.parse(it), null, null) }
        if (id == activeId) activeCall?.cancel()
        broadcast()
    }

    private fun broadcast() = sendBroadcast(Intent(ACTION_UPDATED).setPackage(packageName))

    private fun updateProgressNotification(record: DownloadRecord, downloaded: Long, total: Long, speed: Long) {
        startForeground(NOTIFICATION_ID, notification("Downloading ${record.title}", record, total, total <= 0, downloaded, speed))
    }

    private fun notification(title: String, record: DownloadRecord?, total: Long, indeterminate: Boolean, downloaded: Long = 0, speed: Long = 0) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_drive)
            .setContentTitle(title)
            .setContentText(if (speed > 0) Formatters.speed(speed) else "Heyogesh Drive")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(if (total > 0) 100 else 0, if (total > 0) ((downloaded * 100 / total).toInt()) else 0, indeterminate)
            .apply {
                record?.let {
                    addAction(0, "Pause", serviceIntent(ACTION_PAUSE, it.id))
                    addAction(0, "Cancel", serviceIntent(ACTION_CANCEL, it.id))
                }
            }.build()

    private fun serviceIntent(action: String, id: Long): PendingIntent = PendingIntent.getService(
        this, id.toInt() xor action.hashCode(), Intent(this, DownloadService::class.java).setAction(action).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.channel_downloads_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_ENQUEUE = "org.heyogesh.drive.ENQUEUE"
        const val ACTION_PAUSE = "org.heyogesh.drive.PAUSE"
        const val ACTION_RESUME = "org.heyogesh.drive.RESUME"
        const val ACTION_CANCEL = "org.heyogesh.drive.CANCEL"
        const val ACTION_UPDATED = "org.heyogesh.drive.DOWNLOAD_UPDATED"
        const val EXTRA_ID = "download_id"
        private const val CHANNEL_ID = "drive_downloads"
        private const val NOTIFICATION_ID = 2608
        private const val REPORT_EVERY_MS = 500L
    }
}
