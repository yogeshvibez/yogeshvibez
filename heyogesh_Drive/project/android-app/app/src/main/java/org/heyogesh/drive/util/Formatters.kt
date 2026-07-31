package org.heyogesh.drive.util

import android.text.format.DateUtils
import org.heyogesh.drive.R
import org.heyogesh.drive.api.DriveItem
import kotlin.math.ln
import kotlin.math.pow

object Formatters {
    fun bytes(value: Long?): String {
        if (value == null) return "Folder"
        if (value < 1024) return "$value B"
        val unit = (ln(value.toDouble()) / ln(1024.0)).toInt().coerceAtMost(4)
        val suffix = arrayOf("B", "KB", "MB", "GB", "TB")[unit]
        return "%.1f %s".format(value / 1024.0.pow(unit.toDouble()), suffix)
    }

    fun speed(bytesPerSecond: Long): String = if (bytesPerSecond <= 0) "Calculating speed" else "${bytes(bytesPerSecond)}/s"

    fun eta(seconds: Long): String = when {
        seconds < 0 -> "ETA calculating"
        seconds < 60 -> "ETA ${seconds}s"
        seconds < 3600 -> "ETA ${seconds / 60}m"
        else -> "ETA ${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    fun metadata(item: DriveItem): String {
        if (item.kind == "folder") return "Folder"
        val time = runCatching {
            DateUtils.getRelativeTimeSpanString(java.time.Instant.parse(item.modifiedAt).toEpochMilli()).toString()
        }.getOrDefault("")
        return listOf(bytes(item.size), time).filter(String::isNotBlank).joinToString(" · ")
    }

    fun icon(item: DriveItem): Int = when (item.kind) {
        "folder" -> R.drawable.ic_folder
        "video" -> R.drawable.ic_video
        "audio" -> R.drawable.ic_audio
        "image" -> R.drawable.ic_image
        "apk" -> R.drawable.ic_apk
        else -> R.drawable.ic_file
    }
}
