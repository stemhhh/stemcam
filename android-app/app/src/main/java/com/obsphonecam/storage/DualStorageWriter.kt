package com.obsphonecam.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the same media bytes to internal + external volumes when possible.
 */
class DualStorageWriter(private val context: Context) {
    data class Targets(
        val internalDir: File,
        val externalDir: File?
    )

    fun prepareSessionDirs(sessionName: String = timestamp()): Targets {
        val internal = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "opcm/$sessionName")
        internal.mkdirs()
        val externalRoot = findSecondaryMediaRoot()
        val external = externalRoot?.let { File(it, "OBSPhoneCam/$sessionName").also { d -> d.mkdirs() } }
        return Targets(internal, external)
    }

    fun openDualFile(targets: Targets, filename: String): DualSink {
        val a = File(targets.internalDir, filename)
        val b = targets.externalDir?.let { File(it, filename) }
        return DualSink(
            primary = FileOutputStream(a),
            secondary = b?.let { FileOutputStream(it) },
            primaryPath = a.absolutePath,
            secondaryPath = b?.absolutePath
        )
    }

    fun freeSpaceMb(path: File): Long {
        return try {
            val st = StatFs(path.absolutePath)
            st.availableBytes / (1024 * 1024)
        } catch (_: Exception) {
            -1L
        }
    }

    fun lowSpaceWarning(targets: Targets, thresholdMb: Long = 2048): String? {
        val i = freeSpaceMb(targets.internalDir)
        val e = targets.externalDir?.let { freeSpaceMb(it) } ?: Long.MAX_VALUE
        return when {
            i in 0 until thresholdMb -> "Internal storage low (${i}MB)"
            e in 0 until thresholdMb -> "External storage low (${e}MB)"
            else -> null
        }
    }

    private fun findSecondaryMediaRoot(): File? {
        val sm = context.getSystemService(StorageManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT >= 24) {
            val vols = sm.storageVolumes
            for (v in vols) {
                if (v.isPrimary) continue
                val dir = if (Build.VERSION.SDK_INT >= 30) {
                    v.directory
                } else {
                    @Suppress("DEPRECATION")
                    v.javaClass.getMethod("getPathFile").invoke(v) as? File
                }
                if (dir != null && dir.exists() && dir.canWrite()) return dir
            }
        }
        // Removable app-specific dirs
        val dirs = context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES)
        return dirs.firstOrNull { it != null && Environment.isExternalStorageRemovable(it) }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}

class DualSink(
    private val primary: OutputStream,
    private val secondary: OutputStream?,
    val primaryPath: String,
    val secondaryPath: String?
) {
    @Volatile var secondaryFailed: Boolean = false
        private set

    fun write(data: ByteArray, off: Int = 0, len: Int = data.size) {
        primary.write(data, off, len)
        if (secondary != null && !secondaryFailed) {
            try {
                secondary.write(data, off, len)
            } catch (_: Exception) {
                secondaryFailed = true
                try { secondary.close() } catch (_: Exception) {}
            }
        }
    }

    fun flush() {
        primary.flush()
        if (secondary != null && !secondaryFailed) {
            try { secondary.flush() } catch (_: Exception) { secondaryFailed = true }
        }
    }

    fun close() {
        try { primary.close() } catch (_: Exception) {}
        if (secondary != null && !secondaryFailed) {
            try { secondary.close() } catch (_: Exception) {}
        }
    }
}
