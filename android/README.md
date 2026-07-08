# Android (Lane C/D) — deferred to a JDK-equipped environment

The Kotlin DC app is on the M0→M1 critical path, but this territory is intentionally empty
for now: the machine that scaffolded this repo has no JDK/Android SDK, and unverifiable
generated Kotlin is worse than none (CONTRIBUTING: agents must self-verify).

What M0 requires from this lane (BUILD_PLAN §9): app shell, Room/SQLDelight journal,
outbox sync engine implementing contracts/c3-sync/PROTOCOL.md, login (stub OTP), beat-plan
list, check-in screen with client-side effective-radius check (UX only — server judges),
visible sync status. The M0 e2e story is meanwhile proven by the device-emulating sync
simulator at backend/tools/sync-sim (the plan's own escape hatch).

First ticket for this lane: C-001 — scaffold the Gradle project + Room schema + outbox
state machine (JVM-unit-testable, no device needed), verified with ./gradlew test on a
machine with Android Studio / JDK 17.
