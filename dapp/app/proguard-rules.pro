# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep Solana Mobile Stack classes
-keep class com.solanamobile.** { *; }
-keep class com.solana.** { *; }

# Compose
-keep class androidx.compose.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
