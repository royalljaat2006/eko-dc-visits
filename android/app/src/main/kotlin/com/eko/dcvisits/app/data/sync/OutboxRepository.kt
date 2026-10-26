package com.eko.dcvisits.app.data.sync

import com.eko.dcvisits.app.data.crypto.DeviceKey
import com.eko.dcvisits.app.data.net.ApiClient
import com.eko.dcvisits.app.data.net.C2Api
import com.eko.dcvisits.app.data.net.QueueDepthDto
import com.eko.dcvisits.app.data.net.SyncOpDto
import com.eko.dcvisits.app.data.net.bearer
import com.eko.dcvisits.app.data.db.OutboxDao
import com.eko.dcvisits.app.data.db.OutboxEntity
import com.eko.dcvisits.app.data.session.SessionStore
import com.eko.dcvisits.core.outbox.Backoff
import com.eko.dcvisits.core.outbox.OpState
import com.eko.dcvisits.core.outbox.Tier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Bridges the durable Room journal to the C3 sync endpoint. The transition
 * rules are exactly those of [com.eko.dcvisits.core.outbox.Outbox] (which is
 * unit-tested in :core) — this class is the persistence + HTTP wiring the
 * :core docs describe, kept as its own copy so :core stays pure-JVM and its
 * CI gate is undisturbed.
 *
 * Invariant (BUILD_PLAN §15.1): a row is written PENDING and fsync'd here
 * before any UI confirms the action; terminal rows are never deleted.
 */
class OutboxRepository(
    private val dao: OutboxDao,
    private val session: SessionStore,
    private val api: C2Api = ApiClient.api,
    private val appVersion: String,
    private val contractVersion: String = "0.13.0",
    private val backoff: Backoff = Backoff(),
    private val maxOpsPerBatch: Int = 50,
    /** Keep one request well under the server's 16 MiB body limit (photos are ~0.4 MB base64 each). */
    private val maxBatchPayloadChars: Int = 4_000_000,
) {
    val recent: Flow<List<OutboxEntity>> get() = dao.recent()

    /** Attendance + visit ops from the last ~36 h — unaffected by how many track chunks pile up. */
    val dayOps: Flow<List<OutboxEntity>> get() = dao.dayOps(System.currentTimeMillis() - 36 * 3600 * 1000L)

    /** seq must be unique and increasing — the UI and the tracking service enqueue concurrently. */
    private val enqueueLock = Mutex()
    val pendingCount: Flow<Int> get() = dao.pendingCount()
    val pendingCheckinCount: Flow<Int> get() = dao.pendingCheckinCount()

    /** Commit one evidence op. Returns its client op_id (== the payload `id`). */
    suspend fun enqueue(opType: String, payload: JsonObject, label: String): String {
        val opId = payload["id"].asStringOrNull() ?: Uuidv7.next()
        enqueueLock.withLock {
        val seq = dao.maxSeq() + 1
        dao.insertIfAbsent(
            OutboxEntity(
                opId = opId,
                seq = seq,
                type = opType,
                payload = ApiClient.json.encodeToString(JsonObject.serializer(), payload),
                tier = SyncPayloads.tierFor(opType).name,
                enqueuedAtMs = System.currentTimeMillis(),
                state = OpState.PENDING.name,
                attempts = 0,
                notBeforeMs = 0,
                inFlightBatchId = null,
                label = label,
            )
        )
        }
        return opId
    }

    /**
     * Drains every eligible op, oldest-first, T1 before T3. Stops on the first
     * transport failure (the backlog resumes next trigger). Returns true when
     * the queue reached a terminal-only state, false when work remains.
     */
    suspend fun drainOnce(now: Instant = Instant.now()): Boolean {
        if (session.current() == null) return true // not signed in — nothing to send
        recoverInFlight()

        while (true) {
            // Re-read every batch: a long backlog can outlive the 1h access token, and
            // the authenticator stores the refreshed one in the session.
            val s = session.current() ?: return false
            val nowMs = now.toEpochMilli().coerceAtLeast(System.currentTimeMillis())
            val rows = dao.nonTerminal()
            val eligible = takeWithinBudget(
                rows
                    .filter { it.state == OpState.PENDING.name && it.notBeforeMs <= nowMs }
                    .sortedWith(compareBy({ Tier.valueOf(it.tier).ordinal }, { it.seq }))
            )
            if (eligible.isEmpty()) {
                pruneSynced(nowMs)
                return rows.isEmpty()
            }

            val batchId = Uuidv7.next()
            dao.upsertAll(eligible.map { it.copy(state = OpState.IN_FLIGHT.name, inFlightBatchId = batchId) })

            val ops = eligible.map {
                SyncOpDto(
                    op_id = it.opId,
                    seq = it.seq,
                    type = it.type,
                    payload = ApiClient.json.decodeFromString(JsonObject.serializer(), it.payload),
                )
            }
            val nonTerminal = rows
            val unsigned = SyncPayloads.batch(
                batchId = batchId,
                deviceId = s.deviceId,
                ops = ops,
                appVersion = appVersion,
                contractVersion = contractVersion,
                queueDepth = depthOf(nonTerminal),
                oldestUnsyncedAgeS = (nonTerminal.minOfOrNull { it.enqueuedAtMs }
                    ?.let { (nowMs - it) / 1000 }) ?: 0,
                now = now,
            )
            val batch = unsigned.copy(
                signature = DeviceKey.sign(SyncPayloads.canonicalEnvelope(unsigned).toByteArray()),
            )

            val response = try {
                api.submitBatch(bearer(s.accessToken), batch)
            } catch (_: Exception) {
                requeueFailed(batchId, nowMs, retryAfterMs = null)
                return false
            }
            if (!response.isSuccessful) {
                val retryAfter = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000)
                requeueFailed(batchId, nowMs, retryAfter)
                return false
            }

            val results = response.body()?.results.orEmpty().associateBy { it.op_id }
            val updated = eligible.map { row ->
                when (results[row.opId]?.result) {
                    "accepted", "accepted-flagged", "duplicate" -> row.copy(state = OpState.ACKED.name, inFlightBatchId = null)
                    "quarantined" -> row.copy(state = OpState.QUARANTINED.name, inFlightBatchId = null)
                    "rejected" -> row.copy(state = OpState.REJECTED.name, inFlightBatchId = null)
                    // Server didn't rule on it — retry later with backoff, never in a hot loop.
                    else -> {
                        val attempts = row.attempts + 1
                        row.copy(
                            state = OpState.PENDING.name,
                            attempts = attempts,
                            notBeforeMs = nowMs + backoff.nextDelayMs(attempts, null) { Math.random() },
                            inFlightBatchId = null,
                        )
                    }
                }
            }
            dao.upsertAll(updated)
        }
    }

    /** Oldest-first ops up to [maxOpsPerBatch] and [maxBatchPayloadChars]; always at least one. */
    private fun takeWithinBudget(sorted: List<OutboxEntity>): List<OutboxEntity> {
        val out = ArrayList<OutboxEntity>()
        var chars = 0L
        for (row in sorted) {
            if (out.size >= maxOpsPerBatch) break
            if (out.isNotEmpty() && chars + row.payload.length > maxBatchPayloadChars) break
            out += row
            chars += row.payload.length
        }
        return out
    }

    /** Free phone storage: drop synced photo bytes now, synced history after a week. */
    private suspend fun pruneSynced(nowMs: Long) {
        dao.stripAckedPhotoPayloads()
        dao.deleteAckedBefore(nowMs - 7L * 24 * 3600 * 1000)
    }

    private suspend fun recoverInFlight() {
        val stuck = dao.nonTerminal().filter { it.state == OpState.IN_FLIGHT.name }
        if (stuck.isNotEmpty()) {
            dao.upsertAll(stuck.map { it.copy(state = OpState.PENDING.name, inFlightBatchId = null) })
        }
    }

    private suspend fun requeueFailed(batchId: String, nowMs: Long, retryAfterMs: Long?) {
        val failed = dao.nonTerminal().filter { it.state == OpState.IN_FLIGHT.name && it.inFlightBatchId == batchId }
        if (failed.isEmpty()) return
        dao.upsertAll(
            failed.map {
                val attempts = it.attempts + 1
                it.copy(
                    state = OpState.PENDING.name,
                    attempts = attempts,
                    notBeforeMs = nowMs + backoff.nextDelayMs(attempts, retryAfterMs) { Math.random() },
                    inFlightBatchId = null,
                )
            }
        )
    }

    private fun depthOf(rows: List<OutboxEntity>): QueueDepthDto {
        fun count(t: Tier) = rows.count { it.tier == t.name }
        return QueueDepthDto(count(Tier.T1), count(Tier.T2), count(Tier.T3), count(Tier.T4))
    }

    private fun kotlinx.serialization.json.JsonElement?.asStringOrNull(): String? =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
}
