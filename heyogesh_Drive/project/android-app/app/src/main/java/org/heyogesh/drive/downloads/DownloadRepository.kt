package org.heyogesh.drive.downloads

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import org.heyogesh.drive.api.DriveItem

class DownloadRepository(private val context: Context) {
    private val database = DownloadDatabase(context.applicationContext)

    fun enqueueFile(item: DriveItem, url: String): Long {
        val id = database.insert(url, null, item.name, item.mimeType ?: "application/octet-stream")
        signal(DownloadService.ACTION_ENQUEUE, id)
        return id
    }

    fun enqueueArchive(archiveId: String, title: String): Long {
        val id = database.insert(null, archiveId, title, "application/zip")
        signal(DownloadService.ACTION_ENQUEUE, id)
        return id
    }

    fun records(): List<DownloadRecord> = database.all()
    fun pause(id: Long) { database.updateState(id, DownloadState.PAUSED); signal(DownloadService.ACTION_PAUSE, id) }
    fun resume(id: Long) { database.updateState(id, DownloadState.QUEUED); signal(DownloadService.ACTION_RESUME, id) }
    fun cancel(id: Long) { database.updateState(id, DownloadState.CANCELLED); signal(DownloadService.ACTION_CANCEL, id) }

    private fun signal(action: String, id: Long) {
        val intent = Intent(context, DownloadService::class.java).setAction(action).putExtra(DownloadService.EXTRA_ID, id)
        ContextCompat.startForegroundService(context, intent)
    }
}
