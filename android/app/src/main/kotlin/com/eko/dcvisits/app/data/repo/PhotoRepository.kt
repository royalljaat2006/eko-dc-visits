package com.eko.dcvisits.app.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import com.eko.dcvisits.app.data.crypto.DeviceKey
import com.eko.dcvisits.app.data.session.SessionStore
import com.eko.dcvisits.app.data.sync.Fix
import com.eko.dcvisits.app.data.sync.OutboxRepository
import com.eko.dcvisits.app.data.sync.SyncPayloads
import com.eko.dcvisits.app.data.sync.SyncWorker
import com.eko.dcvisits.app.data.sync.Uuidv7
import com.eko.dcvisits.app.camera.Watermark
import com.eko.dcvisits.app.util.Ist
import java.security.MessageDigest

/**
 * Turns a captured Bitmap into a `visit.photo` evidence op: burn the caption
 * into the pixels, SHA-256 over the final bytes (the server re-hashes — a
 * mismatch is flagged, never rejected), a Keystore ECDSA sidecar binding
 * hash + time + fix at capture, then journal it on tier T2 so photos lag T1
 * without ever blocking the field flow.
 */
class PhotoRepository(
    private val appContext: Context,
    private val outbox: OutboxRepository,
    private val session: SessionStore,
) {
    /** @return the client op id of the queued photo. */
    suspend fun capture(
        visitId: String,
        cspCode: String,
        category: String,
        bitmap: Bitmap,
        fix: Fix?,
    ): String {
        val s = session.current() ?: error("Not signed in")
        val nowIso = Ist.nowIso()

        val watermark = buildMap {
            put("captured_at_ist", Ist.display(nowIso))
            put("csp_code", cspCode)
            put("dc_name", s.user.name)
            put("device_id", s.deviceId)
            put("category", category)
            if (fix != null) put("fix", "${"%.5f".format(fix.lat)}, ${"%.5f".format(fix.lng)} ±${fix.accuracyM?.toInt() ?: "?"}m")
        }
        val caption = listOf(
            "${watermark["captured_at_ist"]} IST",
            watermark["fix"] ?: "location unavailable",
            "${watermark["csp_code"]} · ${watermark["dc_name"]}",
        )

        val wm = Watermark.apply(bitmap, caption)
        val sha = MessageDigest.getInstance("SHA-256").digest(wm.jpeg)
        val shaHex = sha.joinToString("") { "%02x".format(it) }
        val b64 = Base64.encodeToString(wm.jpeg, Base64.NO_WRAP)
        val monotonic = SystemClock.elapsedRealtime()
        val sidecar = DeviceKey.sign("$shaHex|$monotonic|${fix?.lat},${fix?.lng}".toByteArray())

        val id = Uuidv7.next()
        val payload = SyncPayloads.visitPhotoEvent(
            id = id,
            visitId = visitId,
            dcUserId = s.user.id,
            deviceId = s.deviceId,
            category = category,
            sha256Hex = shaHex,
            width = wm.width,
            height = wm.height,
            bytesB64 = b64,
            watermark = watermark,
            sidecarSignature = sidecar,
            fix = fix,
            wallIso = nowIso,
            monotonicMs = monotonic,
        )
        outbox.enqueue(SyncPayloads.OP_VISIT_PHOTO, payload, label = "Photo · $category")
        SyncWorker.kick(appContext)
        return id
    }
}
