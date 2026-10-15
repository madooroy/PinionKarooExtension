package io.github.madooroy.pinionkaroo

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Writes every log line to a file on the Karoo as well, because logcat only keeps the last few minutes and a
 * dropout during a ride is long gone by the time the Karoo is back on a computer.
 *
 * One file per day in the app's own storage (no permission needed):
 * `/sdcard/Android/data/io.github.madooroy.pinionkaroo/files/logs/pinion-YYYY-MM-DD.log`.
 * Fetch with `adb pull /sdcard/Android/data/io.github.madooroy.pinionkaroo/files/logs`.
 * Each file stops growing at [MAX_FILE_BYTES]; files older than [KEEP_DAYS] days are deleted at start-up.
 */
class FileLogTree(context: Context) : Timber.Tree() {
    private val dir: File? = context.getExternalFilesDir(null)?.let { File(it, "logs") }
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "pinion-file-log").apply { isDaemon = true } }
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var fullFile: String? = null

    init {
        writer.execute {
            val d = dir ?: return@execute
            d.mkdirs()
            val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000
            d.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val now = Date()
        val line = buildString {
            append(timeFormat.format(now)).append(' ').append(level(priority)).append(' ').append(message)
            if (t != null) append('\n').append(Log.getStackTraceString(t))
            append('\n')
        }
        val day = dayFormat.format(now)
        writer.execute { write(day, line) }
    }

    private fun write(day: String, line: String) {
        val d = dir ?: return
        val file = File(d, "pinion-$day.log")
        try {
            if (file.length() >= MAX_FILE_BYTES) {
                if (fullFile != file.name) {
                    fullFile = file.name
                    FileWriter(file, true).use { it.write("--- log full, nothing more is written today ---\n") }
                }
                return
            }
            FileWriter(file, true).use { it.write(line) }
        } catch (e: Exception) {
            // Never let logging break the extension; the line still went to logcat
        }
    }

    private fun level(priority: Int) = when (priority) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        else -> '?'
    }

    companion object {
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
        const val KEEP_DAYS = 14
    }
}
