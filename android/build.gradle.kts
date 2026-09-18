// Root build — plugins are resolved here and applied in the modules that need
// them. :core keeps its own inline `kotlin("jvm")` (pure-JVM, no Android SDK).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
