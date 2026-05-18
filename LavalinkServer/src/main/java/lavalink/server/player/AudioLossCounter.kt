/*
 * Copyright (c) 2021 Freya Arbjerg and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package lavalink.server.player

import com.sedmelluq.discord.lavaplayer.player.event.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

class AudioLossCounter : AudioEventListener {
    companion object {
        const val EXPECTED_PACKET_COUNT_PER_MIN = 60 * 1000 / 20 // 20ms packets
        private const val ACCEPTABLE_TRACK_SWITCH_TIME = 100L // ms
    }

    // AtomicLong for thread-safe timestamp tracking across audio threads
    private val playingSince = AtomicLong(Long.MAX_VALUE)
    private val lastTrackStarted = AtomicLong(Long.MAX_VALUE / 2)
    private val lastTrackEnded = AtomicLong(Long.MAX_VALUE)

    // Minute window tracking — written only under checkTime lock
    @Volatile private var curMinute: Long = 0

    // AtomicInteger for lock-free counter increments in the audio hot path
    private val curLoss = AtomicInteger(0)
    private val curSucc = AtomicInteger(0)

    @Volatile var lastMinuteLoss = 0
        private set
    @Volatile var lastMinuteSuccess = 0
        private set

    fun onLoss() {
        checkTime()
        curLoss.incrementAndGet()
    }

    fun onSuccess() {
        checkTime()
        curSucc.incrementAndGet()
    }

    val isDataUsable: Boolean
        get() {
            val ended = lastTrackEnded.get()
            // Check that there isn't a significant gap in playback
            if (lastTrackStarted.get() - ended > ACCEPTABLE_TRACK_SWITCH_TIME && ended != Long.MAX_VALUE) {
                return false
            }
            // Check that we have at least stats for the last minute
            val lastMin = System.currentTimeMillis() / 60000 - 1
            return playingSince.get() < lastMin * 60000
        }

    private fun checkTime() {
        val now = System.currentTimeMillis()
        val actualMinute = now / 60000
        // Only one thread needs to rotate the minute window
        if (curMinute != actualMinute) {
            synchronized(this) {
                if (curMinute != actualMinute) {
                    lastMinuteLoss = curLoss.getAndSet(0)
                    lastMinuteSuccess = curSucc.getAndSet(0)
                    curMinute = actualMinute
                }
            }
        }
    }

    override fun onEvent(event: AudioEvent) {
        // Capture time once to avoid multiple currentTimeMillis() calls per event
        val now = System.currentTimeMillis()
        when (event) {
            is PlayerPauseEvent,
            is TrackEndEvent,
            -> lastTrackEnded.set(now)

            is PlayerResumeEvent,
            is TrackStartEvent,
            -> {
                lastTrackStarted.set(now)
                if (now - lastTrackEnded.get() > ACCEPTABLE_TRACK_SWITCH_TIME || playingSince.get() == Long.MAX_VALUE) {
                    playingSince.set(now)
                    lastTrackEnded.set(Long.MAX_VALUE)
                }
            }
        }
    }

    override fun toString(): String = buildString {
        append("AudioLossCounter{")
        append("lastLoss=$lastMinuteLoss, ")
        append("lastSucc=$lastMinuteSuccess, ")
        append("total=${lastMinuteSuccess + lastMinuteLoss}")
        append('}')
    }
}
