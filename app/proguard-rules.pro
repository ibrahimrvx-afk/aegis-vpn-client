# Xray-core (gomobile) uses JNI + reflection under the hood; never strip it.
-keep class libv2ray.** { *; }
-keep class go.** { *; }
-dontwarn libv2ray.**
-dontwarn go.**

# Gson model classes (keep field names for JSON (de)serialization)
-keep class com.aegis.vpnclient.network.** { *; }
