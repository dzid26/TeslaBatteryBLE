// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.protocol

class AntiReplayWindow {

    private var history = 0L
    private var counter = 0
    private var used = false

    fun update(counter: Int): Boolean {
        if (!used) {
            used = true
            this.counter = counter
            return true
        }
        val (updatedCounter, updatedHistory, ok) = updateSlidingWindow(this.counter, history, counter)
        if (ok) {
            this.counter = updatedCounter
            history = updatedHistory
        }
        return ok
    }

    companion object {
        const val WINDOW_SIZE = 32

        fun updateSlidingWindow(counter: Int, window: Long, newCounter: Int): Triple<Int, Long, Boolean> {
            val current = counter.toLong() and UINT32_MASK
            val incoming = newCounter.toLong() and UINT32_MASK

            if (current == incoming) {
                return Triple(counter, window, false)
            }

            if (incoming < current) {
                val age = current - incoming
                if (age > WINDOW_SIZE) {
                    return Triple(counter, window, false)
                }
                if ((window ushr (age - 1).toInt()) and 1L == 1L) {
                    return Triple(counter, window, false)
                }
                return Triple(counter, window or (1L shl (age - 1).toInt()), true)
            }

            val shift = incoming - current
            val updatedWindow = if (shift >= Long.SIZE_BITS) {
                0L
            } else {
                (window shl shift.toInt()) or (1L shl (shift - 1).toInt())
            }
            return Triple(newCounter, updatedWindow, true)
        }

        private const val UINT32_MASK = 0xFFFFFFFFL
    }
}
