# Metrickle: keep kotlinx.serialization serializers for the wire models (JSON field names must not change).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keep,includedescriptorclasses class com.metrickle.**$$serializer { *; }
-keepclassmembers class com.metrickle.** {
    *** Companion;
}
-keepclasseswithmembers class com.metrickle.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.metrickle.** { *; }
