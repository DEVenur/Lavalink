package lavalink.server.io

import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

/**
 * Stores WebSocket events for a resumable session without allowing the queue
 * to grow beyond a fixed UTF-8 payload budget.
 */
internal class BoundedEventQueue(private val maxBytes: Long) {
    private val events = ArrayDeque<String>()
    private var queuedBytes = 0L

    init {
        require(maxBytes > 0) { "Maximum queued event size must be positive" }
    }

    @Synchronized
    fun offer(event: String): Boolean {
        val eventBytes = event.toByteArray(StandardCharsets.UTF_8).size.toLong()
        if (eventBytes > maxBytes - queuedBytes) return false

        events.addLast(event)
        queuedBytes += eventBytes
        return true
    }

    @Synchronized
    fun drain(): List<String> {
        val drained = events.toList()
        events.clear()
        queuedBytes = 0
        return drained
    }

    @Synchronized
    fun clear() {
        events.clear()
        queuedBytes = 0
    }
}
