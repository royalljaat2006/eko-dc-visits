package com.eko.dcvisits.core

import com.eko.dcvisits.core.outbox.AckResult
import com.eko.dcvisits.core.outbox.Backoff
import com.eko.dcvisits.core.outbox.OpState
import com.eko.dcvisits.core.outbox.Outbox
import com.eko.dcvisits.core.outbox.Tier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** C3 outbox invariants (contracts/c3-sync/PROTOCOL.md §1, §3, §5, §6). */
class OutboxTest {

    private fun newOutbox(): Outbox {
        var n = 0
        return Outbox(idGenerator = { "id-${++n}" }, backoff = Backoff())
    }

    @Test
    fun `seq is one monotonic counter across op types (C3 §1)`() {
        val outbox = newOutbox()
        val a = outbox.enqueue("visit.checkin", "{}", Tier.T1, nowMs = 0)
        val b = outbox.enqueue("track.chunk", "{}", Tier.T3, nowMs = 1)
        val c = outbox.enqueue("attendance.end", "{}", Tier.T1, nowMs = 2)
        assertEquals(listOf(1L, 2L, 3L), listOf(a.seq, b.seq, c.seq))
    }

    @Test
    fun `tiers preempt - T1 drains before older T2 and T3 (C3 §5)`() {
        val outbox = newOutbox()
        outbox.enqueue("visit.photo_meta", "{}", Tier.T2, nowMs = 0) // older, lower tier
        outbox.enqueue("track.chunk", "{}", Tier.T3, nowMs = 1)
        val t1 = outbox.enqueue("visit.checkin", "{}", Tier.T1, nowMs = 2) // newest, highest tier

        val batch = assertNotNull(outbox.nextBatch(maxOps = 2, nowMs = 10))
        assertEquals(t1.opId, batch.ops.first().opId, "T1 preempts older lower tiers")
        assertEquals(Tier.T2, batch.ops[1].tier, "then oldest of the next tier")
    }

    @Test
    fun `oldest-first within a tier - multi-day backlog drains in order (C3 §6)`() {
        val outbox = newOutbox()
        val day1 = outbox.enqueue("visit.checkin", "day1", Tier.T1, nowMs = 1_000)
        val day2 = outbox.enqueue("visit.checkin", "day2", Tier.T1, nowMs = 90_000_000)
        val batch = assertNotNull(outbox.nextBatch(maxOps = 10, nowMs = 100_000_000))
        assertEquals(listOf(day1.opId, day2.opId), batch.ops.map { it.opId })
    }

    @Test
    fun `at-least-once - a failed batch is re-sent byte-identically after backoff (C3 §3)`() {
        val outbox = newOutbox()
        val op = outbox.enqueue("visit.checkin", "{\"x\":1}", Tier.T1, nowMs = 0)

        val first = assertNotNull(outbox.nextBatch(maxOps = 10, nowMs = 0))
        outbox.onSendFailure(first.batchId, nowMs = 0, random = { 0.5 }) // deterministic jitter = 1.0×

        assertNull(outbox.nextBatch(maxOps = 10, nowMs = 1_000), "backoff window must gate the retry")

        val retry = assertNotNull(outbox.nextBatch(maxOps = 10, nowMs = 10_000))
        assertEquals(op.opId, retry.ops.single().opId, "same op identity on resend")
        assertEquals("{\"x\":1}", retry.ops.single().payload, "payload byte-identical on resend")
        assertEquals(1, retry.ops.single().attempts)
    }

    @Test
    fun `acks - duplicate is success, quarantined is terminal-but-kept, rejected terminal (C3 §3)`() {
        val outbox = newOutbox()
        val a = outbox.enqueue("visit.checkin", "{}", Tier.T1, nowMs = 0)
        val b = outbox.enqueue("visit.checkin", "{}", Tier.T1, nowMs = 0)
        val c = outbox.enqueue("visit.checkin", "{}", Tier.T1, nowMs = 0)
        val batch = assertNotNull(outbox.nextBatch(maxOps = 10, nowMs = 0))

        outbox.applyAck(
            batch.batchId,
            mapOf(a.opId to AckResult.DUPLICATE, b.opId to AckResult.QUARANTINED, c.opId to AckResult.ACCEPTED_FLAGGED),
        )
        assertEquals(OpState.ACKED, outbox.get(a.opId)!!.state, "duplicate = success (lost-ack replay)")
        assertEquals(OpState.QUARANTINED, outbox.get(b.opId)!!.state)
        assertEquals(OpState.ACKED, outbox.get(c.opId)!!.state)
        assertNull(outbox.nextBatch(maxOps = 10, nowMs = 1_000_000), "terminal ops never re-send")
    }

    @Test
    fun `Retry-After sets the backoff floor (server load shedding, C3 §6)`() {
        val outbox = newOutbox()
        outbox.enqueue("visit.checkin", "{}", Tier.T1, nowMs = 0)
        val batch = assertNotNull(outbox.nextBatch(maxOps = 10, nowMs = 0))
        outbox.onSendFailure(batch.batchId, nowMs = 0, retryAfterMs = 60_000, random = { 0.0 })
        assertNull(outbox.nextBatch(maxOps = 10, nowMs = 59_999))
        assertNotNull(outbox.nextBatch(maxOps = 10, nowMs = 60_000))
    }

    @Test
    fun `cold-start recovery returns in-flight ops to pending (crash resilience)`() {
        val outbox = newOutbox()
        val op = outbox.enqueue("visit.checkin", "{}", Tier.T1, nowMs = 0)
        assertNotNull(outbox.nextBatch(maxOps = 10, nowMs = 0))
        assertNull(outbox.nextBatch(maxOps = 10, nowMs = 1), "in-flight ops are not re-picked")

        outbox.recoverInFlight() // app restarted before any ack
        val recovered = assertNotNull(outbox.nextBatch(maxOps = 10, nowMs = 2))
        assertEquals(op.opId, recovered.ops.single().opId)
    }

    @Test
    fun `envelope telemetry - queue depth by tier and oldest unsynced age (C3 §2)`() {
        val outbox = newOutbox()
        outbox.enqueue("visit.checkin", "{}", Tier.T1, nowMs = 1_000)
        outbox.enqueue("visit.photo_meta", "{}", Tier.T2, nowMs = 5_000)
        val depth = outbox.queueDepthByTier()
        assertEquals(1, depth[Tier.T1])
        assertEquals(1, depth[Tier.T2])
        assertEquals(0, depth[Tier.T4])
        assertEquals(9_000, outbox.oldestUnsyncedAgeMs(nowMs = 10_000))
    }

    @Test
    fun `backoff caps at 15 minutes and jitters within ±20 percent`() {
        val backoff = Backoff()
        val capped = backoff.nextDelayMs(attempts = 30, random = { 0.5 }) // jitter 1.0×
        assertEquals(15 * 60_000, capped)
        val low = backoff.nextDelayMs(attempts = 1, random = { 0.0 }) // jitter 0.8×
        val high = backoff.nextDelayMs(attempts = 1, random = { 1.0 }) // jitter 1.2×
        assertEquals(4_000, low)
        assertEquals(6_000, high)
        assertTrue(low < high)
    }
}
