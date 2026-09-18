import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// The API base URL is machine/deployment-specific. Order of precedence:
//   1. -PapiBaseUrl=... on the Gradle command line
//   2. api.base.url=... in local.properties
//   3. debug  -> http://10.0.2.2:3000/api/v1/  (emulator -> host loopback)
//      release -> https://dc-visit-app.vercel.app/api/v1/
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun apiBaseUrl(default: String): String =
    (project.findProperty("apiBaseUrl") as String?)
        ?: localProps.getProperty("api.base.url")
        ?: default

// Same precedence, for the debug build's OTP-skip convenience: -PskipOtp=false
// builds a debug (installable, auto-signed) APK that exercises the real
// phone -> OTP screen -> verify flow against a backend, instead of the
// number-only fast path. Useful for testing the Eko gateway end to end.
fun skipOtpDefault(default: Boolean): Boolean =
    (project.findProperty("skipOtp") as String?)?.toBooleanStrictOrNull()
        ?: localProps.getProperty("skip.otp")?.toBooleanStrictOrNull()
        ?: default

// Release signing. Each value resolves from an environment variable first (how
// CI injects secrets), then the matching key in local.properties. If no
// keystore file is resolvable the release build simply stays unsigned — dev
// machines that only ever build debug don't need one, and `assembleRelease`
// still succeeds (it just emits an -unsigned APK).
fun secret(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: localProps.getProperty(prop)?.takeIf { it.isNotBlank() }

val releaseStoreFile = secret("EKO_KEYSTORE_FILE", "release.keystore.file")?.let { rootProject.file(it) }
val hasReleaseKeystore = releaseStoreFile?.exists() == true

android {
    namespace = "com.eko.dcvisits.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eko.dcvisits"
        minSdk = 29          // BUILD_PLAN §1 decision 5: BYOD, Android 10+
        targetSdk = 35
        versionCode = 4
        versionName = "0.13.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = secret("EKO_KEYSTORE_PASSWORD", "release.keystore.password")
                keyAlias = secret("EKO_KEY_ALIAS", "release.key.alias")
                keyPassword = secret("EKO_KEY_PASSWORD", "release.key.password")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // Shrink so the debug APK stays small enough to hand around. Full
            // R8 debugging clarity is an M3 concern; the rules in
            // proguard-rules.pro keep serialization / Retrofit / Room working.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl("http://10.0.2.2:3000/api/v1/")}\"")
            // Testing convenience: skip the OTP screen — enter a number and log
            // straight in with the dev-stub OTP (000000). Only works against a
            // backend without PILOT_OTP/Eko set. Override with -PskipOtp=false
            // to test the real OTP screen. Release always keeps the real flow.
            buildConfigField("boolean", "SKIP_OTP", "${skipOtpDefault(true)}")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl("https://dc-visit-app.vercel.app/api/v1/")}\"")
            buildConfigField("boolean", "SKIP_OTP", "false")
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Compress native libs + dex — this AGP/Gradle combo was inserting
        // huge (>10–50 MB) page-alignment zero-gaps before the uncompressed
        // lib/ and classes.dex entries, bloating the APK. Legacy packaging
        // compresses them and drops the alignment requirement.
        jniLibs.useLegacyPackaging = true
        dex.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.location)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
