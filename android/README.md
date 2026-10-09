# Android (Lane C/D)

The Kotlin DC / Circle Head field app. Two Gradle modules:

## `:core` — pure-JVM, no emulator

The client logic that matters most and needs no Android SDK:

| Component | Implements | Tests |
|-----------|-----------|-------|
| `core/outbox` | C3 sync client: one monotonic seq across op types, tier-ordered draining (T1 preempts), at-least-once byte-identical resends, duplicate-ack = success, quarantined kept-not-deleted, jittered exponential backoff honoring Retry-After, cold-start recovery | OutboxTest (9) |
| `core/dwell` | Auto check-in/out dwell matcher (design 0001 §4): tIn/minFixes entry, back-dated sustained-exit checkout, grey-zone drift immunity, dense-market overlap scoring with planned-stop tie-break + nearby_candidates evidence | DwellMatcherTest (8) |
| `core/geo` | Haversine + accuracy-inflated effective radius (ADR-0004), mirroring backend/src/geo.ts | covered via dwell tests |

```sh
# JDK 17+ only — no Android SDK needed (CI android-core job)
./gradlew :core:test
```

## `:app` — the Android application (needs the Android SDK)

Compose / Material 3 (glass/iOS-style theme), `minSdk 29`, offline-first.
Present in the build only when an SDK is configured (`ANDROID_HOME` /
`ANDROID_SDK_ROOT`, or a `local.properties` with `sdk.dir` written by Android
Studio) — otherwise `settings.gradle.kts` skips it so `:core` still builds on
a bare JDK.

```sh
export ANDROID_HOME=/path/to/Android/sdk
./gradlew :app:testDebugUnitTest :app:assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
# API base URL: -PapiBaseUrl=... , or api.base.url=... in local.properties.
# Debug default http://10.0.2.2:3000/api/v1/ (emulator -> host; `cd backend && npm run dev`). Release has NO default: pass -PapiBaseUrl.
```

Login always uses the real phone → OTP flow (there is no skip-OTP shortcut).

### Release signing

`assembleRelease` signs the APK when a keystore is resolvable, otherwise it
emits `app-release-unsigned.apk` and still succeeds (dev machines that only
build debug need nothing). Each value comes from an **env var first** (CI
secrets), then the matching key in `local.properties`:

| env var | `local.properties` key | meaning |
|---|---|---|
| `EKO_KEYSTORE_FILE` | `release.keystore.file` | path to the `.jks`, relative to `android/` |
| `EKO_KEYSTORE_PASSWORD` | `release.keystore.password` | store password |
| `EKO_KEY_ALIAS` | `release.key.alias` | key alias |
| `EKO_KEY_PASSWORD` | `release.key.password` | key password |

Keystores and `keystore.properties` are git-ignored repo-wide. Generate one with
`keytool -genkeypair -v -keystore android/keystore/eko.jks -alias eko -keyalg RSA -keysize 2048 -validity 10000`.

## Implemented (M1 slice, contracts v0.10.0)

| Area | What it does | Contract |
|------|-------------|----------|
| Login | phone + OTP → session + device binding; DataStore-persisted; **silent token refresh** (`/auth/token/refresh`, single-use rotation) via an OkHttp Authenticator | C2 `/auth/otp/*`, `/auth/token/refresh` |
| Attendance-first nav | Attendance tab first; DC's other tabs disabled until Check In — gate is **local** (derived from the outbox), unlocks offline | spec §3 |
| Check In / End Day / **Resume Day** | `attendance.start` / `.end` evidence ops; GPS fix attached when available, never gated (ADR-0004); 21:00 IST auto-close shown from the server row. After End Day a confirmed **Resume Day** (same IST date) emits a fresh `attendance.start` — the server keeps `min(START)`/`max(END)` so the day spans the whole worked period; route capture restarts | C1 attendance-event, C3 |
| **Route capture** | Foreground service, Start Day → End Day only (DPDP hard stop, 21:00 IST cutoff), adaptive ~20s sampling, gap counting, batched `track.chunk` ops → real "KM today" | C1 track-chunk, C3 §5 T3 |
| **Auto check-in / check-out** | The service feeds every fix to the design-0001 `DwellMatcher`; a sustained dwell auto-emits `visit.checkin` (`AUTO_GEOFENCE`); sustained exit auto-emits `visit.checkout`, correlated by a session-local cspId→visit_id map | C1 checkin/checkout-event, design 0001 §4 |
| Log a visit (manual) | pick an assigned CSP → one-shot fix → advisory effective-radius check → out-of-radius asks for a reason code + remarks, then checks in anyway | C1 checkin-event, ADR-0004 |
| **Check out** | manual "Check out" button closes the open visit (with or without photos first) | C1 checkout-event |
| **Photos** | in-app camera only (CameraX, no gallery path — hard gate #1), 4 category slots, watermark burned into the pixels, SHA-256 + Keystore-signed sidecar, `visit.photo` op (T2) | C1 visit-photo, C4 (interim) |
| My CSPs (CSP Details) | assigned list cached in Room (offline), distance-from-here, last-visit date, **Get Directions** (Google Maps universal link), **Suggest edit** → pending change request | spec §3, C2 `/dc/csp-details`, `/dc/csp-change-requests` |
| **Approvals** (Circle Head) | pending DC-proposed CSP edits, old-vs-proposed, approve (applies to master) / reject with reason | C2 `/circle/csp-change-requests*` |
| Scorecard | dc_score_v1 card (animated count-up) + **My Dashboard** link (own `dashboard_url` only) | spec §3, design 0002 |
| Outbox + sync | Room journal, commit-before-UI-confirm; `SyncWorker` (WorkManager) drains T1-first to `POST /sync/batches` on connectivity + a 15-min backstop; transition rules mirror `:core` `Outbox`; **every envelope is ECDSA-signed** with a per-device AndroidKeyStore key registered at enrollment | C3, BUILD_PLAN §15.1 |
| **Glass UI** | animated blurred-gradient background, frosted glass cards/buttons/nav, spring press animations, staggered list entrances, animated success checks, floating pill bottom nav | — |

`app/src/test` — `SyncPayloadsTest` (9): op payloads match the `additionalProperties:false`
C1 schemas exactly; batch seq derivation; IST date/minutes mirror the backend.

## Not yet built

- **Checklists / forms / exception outcomes** (CSP closed, official unavailable,
  photo refused) — the biggest remaining piece; needs the C5 template language.
- Real SMS OTP (needs a gateway account — `PILOT_OTP` shared secret works today).
- SQLCipher-at-rest (envelope **signing** is done; local DB **encryption** isn't).
- Complaint capture, training mode.
- Dwell thresholds (`DwellConfig`) as remote config, pilot-tuned.
- Physical-device tier (H2): battery ≤15%/10h, OEM kill-resistance, real GPS —
  never claimable by JVM tests (BUILD_PLAN risk #2).
