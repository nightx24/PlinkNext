package com.example.data.store

import com.example.data.model.AppLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val MAX_ENTRIES = 250
    private val lock = Any()
    private val _entries = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val entries: StateFlow<List<AppLogEntry>> = _entries.asStateFlow()

    fun info(message: String, details: String? = null) = log("INFO", message, details)
    fun warning(message: String, details: String? = null) = log("WARN", message, details)
    fun error(message: String, details: String? = null) = log("ERROR", message, details)

    private fun log(level: String, message: String, details: String?) {
        val entry = AppLogEntry(
            timestampMs = System.currentTimeMillis(),
            level = level,
            message = message,
            details = details,
        )
        synchronized(lock) {
            val next = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
            _entries.value = next
        }
    }

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
        }
    }

    fun exportText(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return _entries.value.joinToString("\n") { entry ->
            val ts = fmt.format(Date(entry.timestampMs))
            val extra = entry.details?.let { " | $it" }.orEmpty()
            "[$ts] [${entry.level}] ${entry.message}$extra"
        }
    }
}
