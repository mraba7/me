# Keep the JS bridge
-keepclassmembers class com.iptv.player.MainActivity$NativeBridge {
   public *;
}
-keep class androidx.media3.** { *; }
