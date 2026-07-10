# Android (Lane C/D)

The Kotlin DC app territory. Started with `:core` — a **pure-JVM module** holding
the client logic that matters most and needs no emulator:

| Component | Implements | Tests |
|-----------|-----------|-------|
| `core/outbox` | C3 sync client: one monotonic seq across op types, tier-ordered draining (T1 preempts), at-least-once byte-identical resends, duplicate-ack = success, quarantined kept-not-deleted, jittered exponential backoff honoring Retry-After, cold-start recovery | OutboxTest (9) |
| `core/dwell` | Auto check-in/out dwell matcher (design 0001 §4): tIn/minFixes entry, back-dated sustained-exit checkout, grey-zone drift immunity, dense-market overlap scoring with planned-stop tie-break + nearby_candidates evidence | DwellMatcherTest (8) |
| `core/geo` | Haversine + accuracy-inflated effective radius (ADR-0004), mirroring backend/src/geo.ts | covered via dwell tests |

## Build & test (JDK 17 only — no Android SDK needed)

```sh
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home  # macOS/brew
./gradlew :core:test
```

## What's next for this lane

- `:app` Android application module (requires ANDROID_HOME): Room journal
  persisting the outbox (commit-before-UI-confirm, ADR-0008 budgets), the
  foreground tracking service feeding `DwellMatcher` between Start Day and End
  Day only (DPDP hard stop), CameraX + watermark pipeline (C4, M1), login/
  device-binding, generated C2 client.
- Dwell thresholds (`DwellConfig`) become remote-config values, tuned in the
  Patna pilot; auto-check-out false-positive rate is a pilot exit metric.
- Physical-device tier (H2): battery ≤15%/10h, OEM kill-resistance, real GPS —
  never claimable by these JVM tests (BUILD_PLAN risk #2).
