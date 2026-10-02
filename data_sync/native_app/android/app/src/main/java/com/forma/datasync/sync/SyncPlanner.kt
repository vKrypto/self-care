package com.forma.datasync.sync

/** Pure cursor planning; no Android dependencies. Windows never overlap acknowledged sources. */
object SyncPlanner {
    const val DAY_MS = 86_400_000L
    const val MIN_CHUNK_MS = 1_000L

    data class Window(val start: Long, val end: Long)

    fun nextWindow(cursors: Collection<Long>, now: Long, chunkMs: Long): Window? {
        require(chunkMs > 0 && now >= 0 && cursors.all { it >= 0 }) { "Invalid sync window bounds." }
        val start = cursors.minOrNull() ?: return null
        if (start >= now) return null
        val nextCursor = cursors.filter { it > start }.minOrNull() ?: now
        return Window(start, minOf(now, start + chunkMs, nextCursor))
    }

    fun smallerChunk(current: Long): Long? =
        if (current <= MIN_CHUNK_MS) null else maxOf(MIN_CHUNK_MS, current / 2)
}
