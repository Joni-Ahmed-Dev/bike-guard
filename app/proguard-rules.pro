# BikeGuard release (R8) rules — v1.2.21, review item #23.
# The libraries below ship their own consumer rules too; these are the
# app-side keeps for the things R8 cannot see: reflection-driven
# serialization, Room entities/DAOs, and the MapLibre offline API.
# If the release build ever breaks at runtime after an R8 change, the first
# suspect is a missing keep here — CI going green only proves compilation.

# kotlinx.serialization: keep the generated serializers and the model classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keep class com.bikeguard.app.data.** { *; }

# Room: keep entities, DAOs and the database implementation.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep class com.bikeguard.app.data.*Dao { *; }

# MapLibre: the map and offline download are driven through the SDK API.
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**

# OkHttp / Okio: keep the platform detection (ships its own rules as well).
-dontwarn okhttp3.**
-dontwarn okio.**

# WorkManager: keep the workers the OS starts by class name.
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker

# Compose: keep the compiler-generated helpers R8 already knows about.
-dontwarn androidx.compose.**
