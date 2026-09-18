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

    @Query("SELECT * FROM outbox ORDER BY seq DESC LIMIT 100")
    fun recent(): Flow<List<OutboxEntity>>

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
