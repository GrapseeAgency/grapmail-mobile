# Keep serialization models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.grapsee.grapmail.** {
    *** Companion;
}
-keepclasseswithmembers class com.grapsee.grapmail.** {
    kotlinx.serialization.KSerializer serializer(...);
}
