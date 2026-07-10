package com.eko.dcvisits.core.outbox

/**
 * Client-side outbox state machine for the C3 sync protocol
 * (contracts/c3-sync/PROTOCOL.md). Transport- and storage-agnostic: the app
 * module wires this to Room persistence and an HTTP sender; everything here
 * is pure logic, JVM-unit-tested.
 *
 * C3 guarantees this class upholds:
 *  - §1  one per-device monotonic sequence across all op types
 *  - §3  at-least-once: an op is re-sent identically until a terminal ack;
 *        `duplicate` acks are success, `quarantined` stops retrying but the
 *        record is kept for the review trail, never deleted client-side
 *  - §5  priority tiers: T1 preempts T2 preempts T3 preempts T4; oldest-first
 *        within a tier (backlog drain order after multi-day offline)
 *  - §3  jittered exponential backoff on failure, honoring server Retry-After
 */

enum class Tier { T1, T2, T3, T4 }

enum class OpState {
    /** Waiting to be picked into a batch (or returned after a send failure). */
    PENDING,

    /** Included in an unacknowledged batch. Recovered to PENDING on cold start. */
    IN_FLIGHT,

    /** Terminal: server accepted (accepted / accepted-flagged / duplicate). */
    ACKED,

    /** Terminal: server quarantined it — kept locally for the review trail. */
    QUARANTINED,

    /** Terminal: server could not even quarantine it (C3 §3 "rejected"). */
    REJECTED,
}

data class OutboxOp(
    val opId: String,
    val seq: Long,
    val type: String,
    /** Serialized payload — opaque to the outbox; byte-identical on every resend. */
    val payload: String,
    val tier: Tier,
    val enqueuedAtMs: Long,
    val state: OpState = OpState.PENDING,
    val attempts: Int = 0,
    /** Earliest wall-clock ms this op may be sent again (backoff / Retry-After). */
    val notBeforeMs: Long = 0,
    val inFlightBatchId: String? = null,
)

data class OutboxBatch(val batchId: String, val seqFrom: Long, val seqTo: Long, val ops: List<OutboxOp>)

/** Per-op server disposition (C2 sync response result codes). */
enum class AckResult { ACCEPTED, ACCEPTED_FLAGGED, DUPLICATE, QUARANTINED, REJECTED }

class Outbox(
    private val idGenerator: () -> String,
    private val backoff: Backoff = Backoff(),
) {
    private val ops = LinkedHashMap<String, OutboxOp>() // insertion order = seq order
    private var nextSeq: Long = 1

    /** C3 §1: single monotonic counter across all op types. */
    fun enqueue(type: String, payload: String, tier: Tier, nowMs: Long): OutboxOp {
        val op = OutboxOp(
            opId = idGenerator(),
            seq = nextSeq++,
            type = type,
            payload = payload,
            tier = tier,
            enqueuedAtMs = nowMs,
        )
        ops[op.opId] = op
        return op
    }

    /**
     * Picks the next batch: strictly tier-ordered (T1 first), oldest `seq`
     * first within a tier, only ops whose backoff window has passed.
     * Returns null when nothing is eligible.
     */
    fun nextBatch(maxOps: Int, nowMs: Long): OutboxBatch? {
        val eligible = ops.values
            .filter { it.state == OpState.PENDING && it.notBeforeMs <= nowMs }
            .sortedWith(compareBy({ it.tier.ordinal }, { it.seq }))
            .take(maxOps)
        if (eligible.isEmpty()) return null

        val batchId = idGenerator()
        for (op in eligible) {
            ops[op.opId] = op.copy(state = OpState.IN_FLIGHT, inFlightBatchId = batchId)
        }
        return OutboxBatch(
            batchId = batchId,
            seqFrom = eligible.minOf { it.seq },
            seqTo = eligible.maxOf { it.seq },
            ops = eligible.map { ops[it.opId]!! },
        )
    }

    /** Applies the server's per-op dispositions for a batch (C3 §3). */
    fun applyAck(batchId: String, dispositions: Map<String, AckResult>) {
        for ((opId, result) in dispositions) {
            val op = ops[opId] ?: continue
            if (op.inFlightBatchId != batchId) continue
            val state = when (result) {
                AckResult.ACCEPTED, AckResult.ACCEPTED_FLAGGED, AckResult.DUPLICATE -> OpState.ACKED
                AckResult.QUARANTINED -> OpState.QUARANTINED
                AckResult.REJECTED -> OpState.REJECTED
            }
            ops[opId] = op.copy(state = state, inFlightBatchId = null)
        }
    }

    /**
     * The batch never got a response (network drop / 5xx / 429). Ops return to
     * PENDING with incremented attempts and a jittered backoff window —
     * they will be re-sent byte-identically (at-least-once).
     */
    fun onSendFailure(batchId: String, nowMs: Long, retryAfterMs: Long? = null, random: () -> Double = Math::random) {
        for ((id, op) in ops) {
            if (op.state == OpState.IN_FLIGHT && op.inFlightBatchId == batchId) {
                val attempts = op.attempts + 1
                ops[id] = op.copy(
                    state = OpState.PENDING,
                    attempts = attempts,
                    notBeforeMs = nowMs + backoff.nextDelayMs(attempts, retryAfterMs, random),
                    inFlightBatchId = null,
                )
            }
        }
    }

    /** Cold-start recovery: anything IN_FLIGHT when the app died is re-sendable. */
    fun recoverInFlight() {
        for ((id, op) in ops) {
            if (op.state == OpState.IN_FLIGHT) {
                ops[id] = op.copy(state = OpState.PENDING, inFlightBatchId = null)
            }
        }
    }

    /** Envelope telemetry (C3 §2): non-terminal depth per tier. */
    fun queueDepthByTier(): Map<Tier, Int> =
        Tier.entries.associateWith { tier ->
            ops.values.count { it.tier == tier && (it.state == OpState.PENDING || it.state == OpState.IN_FLIGHT) }
        }

    /** Envelope telemetry (C3 §2): age of the oldest unsynced op, 0 when drained. */
    fun oldestUnsyncedAgeMs(nowMs: Long): Long =
        ops.values
            .filter { it.state == OpState.PENDING || it.state == OpState.IN_FLIGHT }
            .maxOfOrNull { nowMs - it.enqueuedAtMs } ?: 0

    fun get(opId: String): OutboxOp? = ops[opId]
}

/**
 * Jittered exponential backoff (C3 §6): base 5 s doubling to a 15 min cap,
 * ±20 % jitter so a district's phones regaining signal at 19:00 don't
 * thundering-herd one tower; a server Retry-After sets the floor.
 */
class Backoff(
    private val baseMs: Long = 5_000,
    private val capMs: Long = 15 * 60_000,
    private val jitterFraction: Double = 0.2,
) {
    fun nextDelayMs(attempts: Int, retryAfterMs: Long? = null, random: () -> Double = Math::random): Long {
        val exp = (baseMs * (1L shl (attempts - 1).coerceIn(0, 20))).coerceAtMost(capMs)
        val jitter = 1.0 + (random() * 2 - 1) * jitterFraction
        val delay = (exp * jitter).toLong().coerceAtLeast(0)
        return maxOf(delay, retryAfterMs ?: 0)
    }
}
