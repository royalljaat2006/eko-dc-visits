# --- Kotlin / coroutines -------------------------------------------------
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-keepattributes Signature, RuntimeVisibleAnnotations, AnnotationDefault, InnerClasses, EnclosingMethod

# --- kotlinx.serialization --------------------------------------------
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { @kotlinx.serialization.Serializable <methods>; }
-keep,includedescriptorclasses class com.eko.dcvisits.app.data.net.**$$serializer { *; }
-keep class com.eko.dcvisits.app.data.net.** { *; }
-keepclassmembers class com.eko.dcvisits.app.data.net.** {
    public static ** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Retrofit ----------------------------------------------------------
# Retrofit 2.11 ships most rules; keep our typed API surface explicitly.
-keep,allowobfuscation,allowshrinking interface com.eko.dcvisits.app.data.net.C2Api
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# --- WorkManager -----------------------------------------------------
-keep class com.eko.dcvisits.app.data.sync.SyncWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# --- :core (pure-JVM outbox + dwell) -------------------------------
-keep class com.eko.dcvisits.core.** { *; }
