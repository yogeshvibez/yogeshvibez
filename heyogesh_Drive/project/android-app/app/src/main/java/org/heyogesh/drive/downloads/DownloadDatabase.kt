package org.heyogesh.drive.downloads

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

object DownloadState {
    const val QUEUED = "queued"
    const val PREPARING = "preparing"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

data class DownloadRecord(
    val id: Long,
    val url: String?,
    val archiveId: String?,
    val title: String,
    val mimeType: String,
    val destinationUri: String?,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val speedBytesPerSecond: Long,
    val etaSeconds: Long,
    val state: String,
    val error: String?,
    val createdAt: Long,
)

/** Persists queue state and partial-file offsets so a paused transfer can resume. */
class DownloadDatabase(context: Context) : SQLiteOpenHelper(context, "heyogesh_drive_downloads.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE downloads (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT, archive_id TEXT, title TEXT NOT NULL, mime_type TEXT NOT NULL,
                destination_uri TEXT, total_bytes INTEGER NOT NULL DEFAULT -1,
                downloaded_bytes INTEGER NOT NULL DEFAULT 0, speed_bps INTEGER NOT NULL DEFAULT 0,
                eta_seconds INTEGER NOT NULL DEFAULT -1, state TEXT NOT NULL, error TEXT,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX downloads_state_idx ON downloads(state, created_at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(url: String?, archiveId: String?, title: String, mimeType: String): Long = writableDatabase.insertOrThrow(
        "downloads", null, ContentValues().apply {
            put("url", url); put("archive_id", archiveId); put("title", title); put("mime_type", mimeType)
            put("state", DownloadState.QUEUED); put("created_at", System.currentTimeMillis()); put("updated_at", System.currentTimeMillis())
        },
    )

    fun get(id: Long): DownloadRecord? = query("_id=?", arrayOf(id.toString())).firstOrNull()
    fun all(): List<DownloadRecord> = query(null, null)
    fun nextQueued(): DownloadRecord? = query("state=?", arrayOf(DownloadState.QUEUED)).firstOrNull()

    fun requeueInterrupted() {
        writableDatabase.update(
            "downloads",
            ContentValues().apply { put("state", DownloadState.QUEUED); put("updated_at", System.currentTimeMillis()) },
            "state IN (?, ?)", arrayOf(DownloadState.RUNNING, DownloadState.PREPARING),
        )
    }

    fun updateState(id: Long, state: String, error: String? = null) = update(id, ContentValues().apply {
        put("state", state); put("error", error); put("updated_at", System.currentTimeMillis())
    })

    fun updateUrl(id: Long, url: String) = update(id, ContentValues().apply {
        put("url", url); put("updated_at", System.currentTimeMillis())
    })

    fun setDestination(id: Long, uri: String) = update(id, ContentValues().apply {
        put("destination_uri", uri); put("updated_at", System.currentTimeMillis())
    })

    fun reset(id: Long) = update(id, ContentValues().apply {
        putNull("destination_uri"); put("total_bytes", -1); put("downloaded_bytes", 0)
        put("speed_bps", 0); put("eta_seconds", -1); put("updated_at", System.currentTimeMillis())
    })

    fun progress(id: Long, downloaded: Long, total: Long, speed: Long, eta: Long) = update(id, ContentValues().apply {
        put("downloaded_bytes", downloaded); put("total_bytes", total); put("speed_bps", speed); put("eta_seconds", eta)
        put("updated_at", System.currentTimeMillis())
    })

    private fun update(id: Long, values: ContentValues) {
        writableDatabase.update("downloads", values, "_id=?", arrayOf(id.toString()))
    }

    private fun query(selection: String?, args: Array<String>?): List<DownloadRecord> = readableDatabase.query(
        "downloads", null, selection, args, null, null, "created_at DESC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toRecord()) } }

    private fun Cursor.toRecord() = DownloadRecord(
        id = getLong(getColumnIndexOrThrow("_id")), url = getStringOrNull("url"), archiveId = getStringOrNull("archive_id"),
        title = getString(getColumnIndexOrThrow("title")), mimeType = getString(getColumnIndexOrThrow("mime_type")),
        destinationUri = getStringOrNull("destination_uri"), totalBytes = getLong(getColumnIndexOrThrow("total_bytes")),
        downloadedBytes = getLong(getColumnIndexOrThrow("downloaded_bytes")), speedBytesPerSecond = getLong(getColumnIndexOrThrow("speed_bps")),
        etaSeconds = getLong(getColumnIndexOrThrow("eta_seconds")), state = getString(getColumnIndexOrThrow("state")),
        error = getStringOrNull("error"), createdAt = getLong(getColumnIndexOrThrow("created_at")),
    )

    private fun Cursor.getStringOrNull(column: String): String? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)
}
