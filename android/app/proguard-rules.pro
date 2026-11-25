# Keep serialization models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.grapsee.grapmail.** {
    *** Companion;
}
-keepclasseswithmembers class com.grapsee.grapmail.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Credential Manager (passkeys, saved passwords): on Android 13 and older the
# Play-services provider is found by class name at runtime, so R8 must not
# strip or rename it. This is the rule the androidx.credentials docs give.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** {
  *;
}
