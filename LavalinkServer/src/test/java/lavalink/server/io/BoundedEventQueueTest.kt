package lavalink.server.io

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BoundedEventQueueTest {
    @Test
    fun acceptsEventsWithinByteLimit() {
        val queue = BoundedEventQueue(8)

        assertTrue(queue.offer("1234"))
        assertTrue(queue.offer("5678"))
        assertEquals(listOf("1234", "5678"), queue.drain())
        assertTrue(queue.drain().isEmpty())
    }

    @Test
    fun rejectsEventsThatWouldExceedByteLimit() {
        val queue = BoundedEventQueue(5)

        assertTrue(queue.offer("1234"))
        assertFalse(queue.offer("56"))
        assertEquals(listOf("1234"), queue.drain())
    }

    @Test
    fun countsUtf8BytesRatherThanCharacters() {
        val queue = BoundedEventQueue(3)

        assertFalse(queue.offer("éé"))
        assertTrue(queue.offer("é"))
        assertEquals(listOf("é"), queue.drain())
    }

    @Test
    fun clearReleasesQueuedEvents() {
        val queue = BoundedEventQueue(4)

        assertTrue(queue.offer("1234"))
        queue.clear()

        assertTrue(queue.offer("5678"))
    }

    @Test
    fun rejectsNonPositiveLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            BoundedEventQueue(0)
        }
    }
}
