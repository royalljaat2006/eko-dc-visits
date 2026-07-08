# ADR-0003: Evidence is append-only; sync is idempotent at-least-once
Status: Accepted (locked)
Field records: client UUIDv7, per-device monotonic sequence, triple timestamps (device wall,
monotonic anchor, server receive), Keystore-signed envelopes. Server result codes:
accepted / accepted-flagged / duplicate / quarantined / rejected. Validation failures are
QUARANTINED, never discarded. Judgments are annotation records; corrections are amendments.
Acceptance criterion forever: any permutation+duplication of batches converges to identical DB state.
