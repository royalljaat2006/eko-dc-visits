// Lane C/D territory (BUILD_PLAN §8). :core is pure-JVM Kotlin — the outbox
// sync engine (C3) and the auto check-in/out dwell matcher (design 0001 §4) —
// testable with `./gradlew :core:test` on any JDK-17 machine, no Android SDK.
// The :app Android module is added once ANDROID_HOME is configured (README).
rootProject.name = "eko-dc-visits-android"
include(":core")
