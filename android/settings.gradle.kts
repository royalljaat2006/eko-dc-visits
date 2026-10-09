// Lane C/D territory (BUILD_PLAN §8).
//
//  :core  — pure-JVM Kotlin: the C3 outbox sync engine and the design-0001
//           auto check-in/out dwell matcher. `./gradlew :core:test` on any
//           JDK-17+ machine, no Android SDK needed (CI gates this).
//  :app   — the Kotlin DC / Circle Head field app (BUILD_PLAN M1). Requires
//           the Android SDK (local.properties sdk.dir, or ANDROID_HOME).
//           Depends on :core for the offline outbox + dwell logic.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "eko-dc-visits-android"
include(":core")

// :app needs the Android SDK. Include it only when one is configured so that
// `./gradlew :core:test` still runs on a bare JDK-17 machine (android/README.md,
// CI android-core job). Set ANDROID_HOME / ANDROID_SDK_ROOT, or let Android
// Studio write android/local.properties, and the app module joins the build.
val androidSdkConfigured = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    (file("local.properties").takeIf { it.exists() }?.readText()?.contains("sdk.dir") == true)

if (androidSdkConfigured) {
    include(":app")
} else {
    logger.lifecycle(
        "[settings] Android SDK not configured — building :core only. " +
            "Set ANDROID_HOME or create android/local.properties to include :app.",
    )
}
