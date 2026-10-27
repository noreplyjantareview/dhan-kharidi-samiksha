# Add project specific ProGuard rules here.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.vsk.attendance.** {
    *** Companion;
}
-keepclasseswithmembers class com.vsk.attendance.** {
    kotlinx.serialization.KSerializer serializer(...);
}
