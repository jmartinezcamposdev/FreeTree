package com.jmartinezcampos.freetree.core.session

import java.nio.file.Path
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MessageLevel { INFO, ERROR }

data class MessageLogEntry(
    val id: Long,
    val timestampMillis: Long,
    val repoName: String,
    val repoPath: String,
    val level: MessageLevel,
    val text: String,
    val stderr: String? = null,
)

/**
 * Process-wide, in-memory message history. It is intentionally not persisted.
 */
class MessageLog(private val capacity: Int = 1000) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val lock = Any()
    private val buffer = ArrayDeque<MessageLogEntry>()
    private var nextId = 0L
    private val mutableEntries = MutableStateFlow<List<MessageLogEntry>>(emptyList())
    val entries: StateFlow<List<MessageLogEntry>> = mutableEntries.asStateFlow()

    fun record(timestampMillis: Long, repoPath: Path, message: UserMessage) {
        val entry = MessageLogEntry(
            id = synchronized(lock) { nextId++ },
            timestampMillis = timestampMillis,
            repoName = repoPath.fileName?.toString()?.takeIf { it.isNotBlank() }
                ?: repoPath.toString(),
            repoPath = repoPath.toString(),
            level = when (message) {
                is UserMessage.Info -> MessageLevel.INFO
                is UserMessage.Error -> MessageLevel.ERROR
            },
            text = when (message) {
                is UserMessage.Info -> message.text
                is UserMessage.Error -> message.text
            },
            stderr = (message as? UserMessage.Error)?.stderr,
        )
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > capacity) buffer.removeFirst()
            mutableEntries.value = buffer.toList()
        }
    }

    fun snapshot(): List<MessageLogEntry> = synchronized(lock) { buffer.toList() }
}
