# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class com.dagmesh.app.data.** { *; }
# Nearby / Play Services
-keep class com.google.android.gms.nearby.** { *; }
-dontwarn com.google.android.gms.**
# osmdroid (çevrimdışı harita)
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
# Tink (şifreleme): derlemede kullanılmayan isteğe bağlı bağımlılıklar
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
-keep class com.google.crypto.tink.subtle.** { *; }
