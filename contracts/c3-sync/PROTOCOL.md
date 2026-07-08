# C3 — Sync Protocol (contracts v0.1.0, M0 scope)

The most load-bearing artifact in the system. The Android outbox, the backend ingest, the
sync simulator, and every dashboard's freshness semantics implement THIS document.
Breaking changes after M1 require an ADR + adoption tickets for all impacted lanes.

## 1. Identity & ordering

- Every field-originated record carries a **client-generated UUIDv7** (`id`). This is the
  idempotency key for its whole life.
- Every record also carries a **per-device monotonic sequence number** (`seq`) — one counter
  across all record types, persisted in the client's local store. Server-side sequence gap
  detection is the primary telemetry for the sync-success KPI (C7).

## 2. Batch envelope

```json
{
  "batch_id":        "uuidv7 (client)",
  "device_id":       "uuid",
  "seq_from":        123,
  "seq_to":          130,
  "client_time":     "date-time (device wall clock at send)",
  "app_version":     "string",
  "contract_version":"0.1.0",
  "queue_depth_by_tier": { "t1": 0, "t2": 4, "t3": 1, "t4": 0 },
  "oldest_unsynced_age_s": 3600,
  "health": { "battery_pct": 61, "network": "3g", "storage_free_mb": 4096 },
  "ops": [ { "op_id": "record uuidv7", "seq": 124, "type": "visit.checkin", "payload": { } } ],
  "signature": "base64 (device Keystore key over canonical envelope; M0: field present, verification off)"
}
```

M0 op types: `visit.checkin`. (M1 adds: `attendance.start/end`, `visit.checkout`,
`visit.photo_meta`, `visit.outcome`, `form.submission`, `track.chunk`, `complaint.*`.)

## 3. Apply semantics

- **At-least-once, idempotent.** The client retries any unacked batch forever (jittered
  exponential backoff, honoring `Retry-After` on 429). The server dedupes on `op_id`.
- **Per-op result codes**: `accepted` | `accepted-flagged` | `duplicate` | `quarantined` | `rejected`.
  - `accepted-flagged`: stored with server-derived flags (e.g. `OUTSIDE_FLAGGED` geofence).
    Evidence is never rejected for radius (ADR-0004).
  - `duplicate`: already-seen `op_id`; response body is byte-identical to the original
    disposition; zero writes.
  - `quarantined`: schema-invalid or referentially impossible payloads are **persisted raw**
    in a quarantine store, acked (so the client stops retrying), and human-reviewed.
    An audit system never discards data a device swears it captured (ADR-0003).
  - `rejected`: reserved for unparseable garbage (cannot even be quarantined with an op_id).
- **Partial failure never poisons a batch**: each op gets its own disposition; the batch
  response is an array aligned with `ops`.
- **Convergence invariant (the standing property test):** applying any permutation and any
  duplication of a set of batches converges to an identical database state.

## 4. Timestamps

Three on every evidence record: `device_wall_time`, `monotonic_ms`, `server_received_at`
(server-set). Dashboards display occurred-time; auditors see both. Skew >2min is a flag,
never a gate.

## 5. Priority tiers (client outbox discipline)

T1 tiny records (attendance, check-ins/outs, outcomes, forms) → T2 photos → T3 track chunks
→ T4 voice notes. Separate outboxes; T1 preempts. Dashboards run on T1 so compliance is
visible while media lags. Client stores ≥5 days of full activity (ADR-0008).

## 6. Downstream (server → client)

Delta pull per collection with `updated_since` cursors: locations, beat plans, form
templates, remote config. Beat plans for date D must be pullable from D-1 evening.
Clients never write these collections. Offline drafts complete on the template version
they started.

## 7. Media (M1)

Metadata op in-batch (SHA-256, size, category, watermark payload) → pre-signed resumable
upload direct to object storage → server-side hash verification → orphan-metadata monitoring.

## 8. KPI measurement (C7 binding)

Sync success = 99% of day-D T1 records server-acknowledged by end of D+1 (photos D+2),
measured per-record via sequence accounting + the envelope's `queue_depth_by_tier` and
`oldest_unsynced_age_s`. A device silent >24h on a working day pages a human.
