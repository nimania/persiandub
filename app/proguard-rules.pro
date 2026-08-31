# Keep OkHttp / Okio (they ship their own rules, this is just belt-and-braces).
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
