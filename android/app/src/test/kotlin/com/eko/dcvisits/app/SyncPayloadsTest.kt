package com.eko.dcvisits.app

import com.eko.dcvisits.app.data.net.QueueDepthDto
import com.eko.dcvisits.app.data.net.SyncOpDto
import com.eko.dcvisits.app.data.sync.Fix
import com.eko.dcvisits.app.data.sync.SyncPayloads
import com.eko.dcvisits.app.util.Ist
import com.eko.dcvisits.core.outbox.Tier
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The op payloads must match the contracts/c1-entities schemas exactly —
 * those schemas are `additionalProperties: false`, so an extra key is a
 * quarantine at the server. These tests pin the shapes without a device.
 */
class SyncPayloadsTest {

    private val wall = "2026-09-04T09:15:00Z"

    @Test
    fun `attendance START carries exactly the schema keys`() {
        val ev = SyncPayloads.attendanceEvent(
            id = "018f-id", dcUserId = "u1", deviceId = "d1", kind = "START",
            fix = Fix(25.6, 85.1, 12.0), wallIso = wall, monotonicMs = 5_000,
        )
        assertEquals(
            setOf("id", "dc_user_id", "device_id", "kind", "fix", "timestamps"),
            ev.keys,
        )
        val ts = (ev["timestamps"] as JsonObject)
        assertEquals(setOf("device_wall_time", "monotonic_ms"), ts.keys)
    }

    @Test
    fun `attendance END without a fix omits the fix key (logged, never gated)`() {
        val ev = SyncPayloads.attendanceEvent(
            id = "x", dcUserId = "u1", deviceId = "d1", kind = "END",
            fix = null, wallIso = wall, monotonicMs = 1,
        )
        assertFalse("fix" in ev.keys)
        assertEquals("END", ev["kind"].toString().trim('"'))
    }

    @Test
    fun `visit checkin inside the radius has no out_of_radius_reason`() {
        val ev = SyncPayloads.checkinEvent(
            id = "v1", dcUserId = "u1", deviceId = "d1", locationId = "csp1",
            fix = Fix(25.6, 85.1, 8.0), wallIso = wall, monotonicMs = 2,
            outOfRadiusReason = null, remarks = null,
        )
        assertFalse("out_of_radius_reason" in ev.keys)
        assertTrue("planned_stop_id" in ev.keys)
        assertEquals("MANUAL", ev["trigger"].toString().trim('"'))
    }

    @Test
    fun `visit checkin outside the radius keeps the reason and remarks`() {
        val ev = SyncPayloads.checkinEvent(
            id = "v2", dcUserId = "u1", deviceId = "d1", locationId = "csp1",
            fix = Fix(25.6, 85.1, 8.0), wallIso = wall, monotonicMs = 2,
            outOfRadiusReason = "MASTER_PIN_WRONG", remarks = "pin is 400m off",
        )
        assertEquals("MASTER_PIN_WRONG", ev["out_of_radius_reason"].toString().trim('"'))
        assertEquals("pin is 400m off", ev["remarks"].toString().trim('"'))
    }

    @Test
    fun `visit checkout carries exactly the schema keys and defaults trigger to MANUAL`() {
        val ev = SyncPayloads.checkoutEvent(
            id = "co1", visitId = "v1", dcUserId = "u1", deviceId = "d1",
            fix = null, wallIso = wall, monotonicMs = 5,
        )
        assertEquals(setOf("id", "visit_id", "dc_user_id", "device_id", "trigger", "timestamps"), ev.keys)
        assertEquals("MANUAL", ev["trigger"].toString().trim('"'))
        assertEquals("v1", ev["visit_id"].toString().trim('"'))
    }

    @Test
    fun `visit checkout can carry AUTO_GEOFENCE and a fix`() {
        val ev = SyncPayloads.checkoutEvent(
            id = "co2", visitId = "v1", dcUserId = "u1", deviceId = "d1",
            fix = Fix(25.6, 85.1, 12.0), wallIso = wall, monotonicMs = 6,
            trigger = "AUTO_GEOFENCE",
        )
        assertEquals("AUTO_GEOFENCE", ev["trigger"].toString().trim('"'))
        assertTrue("fix" in ev.keys)
    }

    @Test
    fun `batch derives seq_from and seq_to from its ops`() {
        val ops = listOf(
            SyncOpDto("a", 7, SyncPayloads.OP_ATTENDANCE_START, JsonObject(emptyMap())),
            SyncOpDto("b", 9, SyncPayloads.OP_VISIT_CHECKIN, JsonObject(emptyMap())),
            SyncOpDto("c", 8, SyncPayloads.OP_VISIT_CHECKIN, JsonObject(emptyMap())),
        )
        val batch = SyncPayloads.batch(
            batchId = "batch1", deviceId = "d1", ops = ops,
            appVersion = "0.8.0", contractVersion = "0.8.0",
            queueDepth = QueueDepthDto(t1 = 3), oldestUnsyncedAgeS = 42,
            now = Instant.parse(wall),
        )
        assertEquals(7, batch.seq_from)
        assertEquals(9, batch.seq_to)
        assertEquals(3, batch.ops.size)
    }

    @Test
    fun `tier mapping - field ops are T1, tracks are T3`() {
        assertEquals(Tier.T1, SyncPayloads.tierFor(SyncPayloads.OP_ATTENDANCE_START))
        assertEquals(Tier.T1, SyncPayloads.tierFor(SyncPayloads.OP_VISIT_CHECKIN))
        assertEquals(Tier.T1, SyncPayloads.tierFor(SyncPayloads.OP_VISIT_CHECKOUT))
        assertEquals(Tier.T3, SyncPayloads.tierFor("track.chunk"))
    }

    @Test
    fun `IST date and minutes mirror the backend (03-30Z = 09-00 IST = 540)`() {
        assertEquals("2026-09-04", Ist.date(Instant.parse("2026-09-04T03:30:00Z")))
        assertEquals(540, Ist.minutesOfDay(Instant.parse("2026-09-04T03:30:00Z")))
        // 20:00Z is 01:30 IST the NEXT day
        assertEquals("2026-09-05", Ist.date(Instant.parse("2026-09-04T20:00:00Z")))
    }
}
