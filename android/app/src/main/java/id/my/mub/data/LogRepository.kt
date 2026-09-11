package id.my.mub.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

object LogRepository {
    private val buffer = CopyOnWriteArrayList<LogEntry>()
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private const val MAX_LOG_ENTRIES = 500

    fun log(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        val entry = LogEntry(tag = tag, message = message, level = level)
        buffer.add(entry)
        if (buffer.size > MAX_LOG_ENTRIES) {
            buffer.removeAt(0)
        }
        _logs.value = buffer.toList()
    }

    fun clear() {
        buffer.clear()
        _logs.value = emptyList()
    }
}
