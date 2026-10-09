# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class nl.tinnisoft.** {
    *** Companion;
}
-keepclasseswithmembers class nl.tinnisoft.** {
    kotlinx.serialization.KSerializer serializer(...);
}
