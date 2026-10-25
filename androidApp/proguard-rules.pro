# kotlinx.serialization: keep generated serializers for the engine's and app's models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.imad.downloader.** {
    *** Companion;
}
-keepclasseswithmembers class com.imad.downloader.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.imad.downloader.**$$serializer { *; }

# OkHttp optional platform integrations.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Audio tools: jump3r (LAME) and JAAD carry javax.sound helpers the app never uses (absent on Android).
-dontwarn javax.sound.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-keep class de.sciss.jump3r.** { *; }
-keep class net.sourceforge.jaad.aac.** { *; }
