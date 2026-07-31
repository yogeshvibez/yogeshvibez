package org.heyogesh.drive.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.heyogesh.drive.R
import org.heyogesh.drive.downloads.DownloadRecord
import org.heyogesh.drive.downloads.DownloadState
import org.heyogesh.drive.util.Formatters

class DownloadAdapter(
    private val onPause: (Long) -> Unit,
    private val onResume: (Long) -> Unit,
    private val onCancel: (Long) -> Unit,
) : RecyclerView.Adapter<DownloadAdapter.Holder>() {
    private var records: List<DownloadRecord> = emptyList()
    fun submit(records: List<DownloadRecord>) { this.records = records; notifyDataSetChanged() }
    override fun getItemCount(): Int = records.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false),
    )
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(records[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.downloadTitle)
        private val status = view.findViewById<TextView>(R.id.downloadStatus)
        private val details = view.findViewById<TextView>(R.id.downloadDetails)
        private val progress = view.findViewById<LinearProgressIndicator>(R.id.downloadProgress)
        private val pauseResume = view.findViewById<MaterialButton>(R.id.pauseResumeButton)
        private val cancel = view.findViewById<MaterialButton>(R.id.cancelButton)

        fun bind(record: DownloadRecord) {
            title.text = record.title
            status.text = when (record.state) {
                DownloadState.QUEUED -> "Waiting in queue"
                DownloadState.PREPARING -> "Preparing archive on storage server"
                DownloadState.RUNNING -> "Downloading"
                DownloadState.PAUSED -> "Paused"
                DownloadState.COMPLETED -> "Completed"
                DownloadState.CANCELLED -> "Cancelled"
                DownloadState.FAILED -> record.error ?: "Download failed"
                else -> record.state
            }
            val hasTotal = record.totalBytes > 0
            progress.isIndeterminate = !hasTotal && record.state in setOf(DownloadState.RUNNING, DownloadState.PREPARING)
            progress.progress = if (hasTotal) ((record.downloadedBytes * 100 / record.totalBytes).toInt()).coerceIn(0, 100) else 0
            details.text = when (record.state) {
                DownloadState.COMPLETED -> Formatters.bytes(record.downloadedBytes)
                DownloadState.FAILED -> record.error ?: "Try resuming the download."
                else -> listOf(
                    if (hasTotal) "${Formatters.bytes(record.downloadedBytes)} / ${Formatters.bytes(record.totalBytes)}" else Formatters.bytes(record.downloadedBytes),
                    if (record.speedBytesPerSecond > 0) Formatters.speed(record.speedBytesPerSecond) else "",
                    if (record.etaSeconds >= 0) Formatters.eta(record.etaSeconds) else "",
                ).filter(String::isNotBlank).joinToString(" · ")
            }
            val terminal = record.state in setOf(DownloadState.COMPLETED, DownloadState.CANCELLED, DownloadState.FAILED)
            pauseResume.visibility = if (terminal) View.GONE else View.VISIBLE
            cancel.visibility = if (terminal) View.GONE else View.VISIBLE
            pauseResume.text = if (record.state == DownloadState.PAUSED) "Resume" else "Pause"
            pauseResume.setOnClickListener {
                if (record.state == DownloadState.PAUSED) onResume(record.id) else onPause(record.id)
            }
            cancel.setOnClickListener { onCancel(record.id) }
        }
    }
}
