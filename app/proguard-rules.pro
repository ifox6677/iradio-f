# Kotlin 协程必保持
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ExoPlayer（防止被误删）
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}

# Rome（防止被干掉）
-keep class com.rometools.** { *; }
-dontwarn com.rometools.**

# 防止 OkHttp 的警告
-dontwarn okhttp3.**
-dontwarn okio.**