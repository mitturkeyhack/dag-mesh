# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class com.dagmesh.app.data.** { *; }
# Nearby / Play Services
-keep class com.google.android.gms.nearby.** { *; }
-dontwarn com.google.android.gms.**
