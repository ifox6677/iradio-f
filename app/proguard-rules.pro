# ExoPlayer（必保）
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson（模型类被反射）
-keep class com.google.code.gson.** { *; }   # ← 改成你自己的包名
-keepattributes Signature
-keepattributes *Annotation*

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule

# ROME
-keep class com.rometools.** { *; }
-dontwarn com.rometools.**

# AndroidX & Kotlin
-dontwarn androidx.**
-dontwarn kotlin.**