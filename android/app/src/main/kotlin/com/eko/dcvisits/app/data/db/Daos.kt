package com.eko.dcvisits.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(op: OutboxEntity): Long

    @Upsert
    suspend fun upsertAll(ops: List<OutboxEntity>)

    @Query("SELECT * FROM outbox ORDER BY seq ASC")
    suspend fun all(): List<OutboxEntity>

    /** Only what the sync loop works on — never loads acked payloads (photos) into memory. */
    @Query("SELECT * FROM outbox WHERE state IN ('PENDING','IN_FLIGHT') ORDER BY seq ASC")
    suspend fun nonTerminal(): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY seq DESC LIMIT 100")
    fun recent(): Flow<List<OutboxEntity>>

    /**
     * Attendance and visit ops, newest first. Day state and the open visit are
     * derived from these — track chunks (~20/hour) must never push the morning
     * START out of the window, which [recent]'s LIMIT 100 did after ~5 hours.
     */
    @Query(
        "SELECT * FROM outbox WHERE type IN ('attendance.start','attendance.end','visit.checkin','visit.checkout') " +
            "AND enqueuedAtMs >= :sinceMs ORDER BY seq DESC"
    )
    fun dayOps(sinceMs: Long): Flow<List<OutboxEntity>>

    /** Check-ins not yet synced, however old (a DC can be offline for days). */
    @Query("SELECT COUNT(*) FROM outbox WHERE type = 'visit.checkin' AND state IN ('PENDING','IN_FLIGHT')")
    fun pendingCheckinCount(): Flow<Int>

    /** Acked photos keep their row (local trail) but drop the base64 JPEG — it is on the server now. */
    @Query("UPDATE outbox SET payload = '{}' WHERE state = 'ACKED' AND type = 'visit.photo' AND payload != '{}'")
    suspend fun stripAckedPhotoPayloads(): Int

    /** Synced rows older than the cutoff are only history — the server holds the evidence. */
    @Query("DELETE FROM outbox WHERE state = 'ACKED' AND enqueuedAtMs < :cutoffMs")
    suspend fun deleteAckedBefore(cutoffMs: Long): Int

    @Query("SELECT COUNT(*) FROM outbox WHERE state IN ('PENDING','IN_FLIGHT')")
    fun pendingCount(): Flow<Int>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM outbox")
    suspend fun maxSeq(): Long
}

@Dao
interface CspCacheDao {

    @Upsert
    suspend fun upsertAll(rows: List<CspCacheEntity>)

    @Query("DELETE FROM csp_cache WHERE cspLocationId NOT IN (:keepIds)")
    suspend fun deleteMissing(keepIds: List<String>)

    @Query("SELECT * FROM csp_cache ORDER BY name ASC")
    fun observeAll(): Flow<List<CspCacheEntity>>

    @Query("SELECT * FROM csp_cache ORDER BY name ASC")
    suspend fun all(): List<CspCacheEntity>

    @Query("SELECT * FROM csp_cache WHERE cspLocationId = :id")
    suspend fun byId(id: String): CspCacheEntity?
}
