# Release shrinking rules. Library consumer rules (Retrofit, OkHttp, kotlinx.serialization, Stripe,
# CameraX, ML Kit, Play Integrity, Firebase) are applied automatically; only gaps are listed here.

# Generated Retrofit interfaces are resolved reflectively through their annotations and generic
# suspend signatures (Response<T>); keep the metadata R8 would otherwise strip.
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keep,allowobfuscation interface org.spaceworks.generated.api.**

# kotlinx.serialization: keep generated serializers and companions of the generated client models.
-keepclassmembers @kotlinx.serialization.Serializable class org.spaceworks.generated.model.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class org.spaceworks.generated.model.**$$serializer { *; }
-keep class org.spaceworks.generated.model.**Serializer { *; }

# Credential Manager loads its Play Services provider reflectively.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }

# Razorpay Checkout (vendor-documented rules).
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keepattributes JavascriptInterface
-keepattributes *Annotation*
-dontwarn com.razorpay.**
-keep class com.razorpay.** { *; }
-optimizations !method/inlining/*
-keepclasseswithmembers class * { public void onPayment*(...); }

# Optional Google Play services/annotation references that are not packaged.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.impl.**
-dontwarn proguard.annotation.**
