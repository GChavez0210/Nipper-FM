package app.openradio.data

import android.content.Context
import app.openradio.domain.ErrorLogEntry
import app.openradio.domain.ErrorLogFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Small on-device log of playback failures, retries and failovers, kept as tab-separated lines
 * in app-private storage and capped at [ErrorLogFormat.MAX_ENTRIES]. Nothing leaves the device
 * unless the user exports it from Settings.
 */
class PlaybackErrorLog(context: Context) {
    private val file = File(context.applicationContext.filesDir, "playback_errors.log")
    private val mutex = Mutex()

    suspend fun record(entry: ErrorLogEntry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val lines = readLines() + entry.toLine()
            file.writeText(ErrorLogFormat.trim(lines).joinToString("\n", postfix = "\n"))
        }
    }

    suspend fun entries(): List<ErrorLogEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { readLines().mapNotNull(ErrorLogEntry::parse) }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { file.delete() }
    }

    private fun readLines(): List<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
}
