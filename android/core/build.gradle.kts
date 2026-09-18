plugins {
    // Version comes from the root build's plugin classpath (see ../build.gradle.kts
    // + gradle/libs.versions.toml). Kept version-less so `:core` and `:app` can't
    // request the Kotlin plugin at two versions.
    kotlin("jvm")
}

// Repositories come from settings.gradle.kts (dependencyResolutionManagement).

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
