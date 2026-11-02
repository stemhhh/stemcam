package com.obsphonecam.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object SessionLogger {
    private val lines = CopyOnWriteArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun clear() = lines.clear()

    fun log(msg: String) {
        lines += "${fmt.format(Date())}  $msg"
        if (lines.size > 2000) {
            lines.subList(0, lines.size - 1500).clear()
        }
    }

    fun exportAndShare(context: Context) {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val file = File(dir, "opcm-session.log")
        file.writeText(lines.joinToString("\n"))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "OBS Phone Cam log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share log"))
    }
}
