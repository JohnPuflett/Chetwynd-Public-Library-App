# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- ZXING BARCODE ENGINE PROTECTIONS ---
# Prevents R8 from stripping or renaming the core barcode generation classes
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# --- ENUM & REFLECTION SAFEGUARDS ---
# Protects the attributes and metadata keys used for configurations (like MARGIN or CHARACTER_SET)
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- 1. PRESERVE THE MAIN ACTIVITY RUNTIME ---
# Stops R8 from stripping lifecycle hooks or interface handlers in your main class
-keep class com.cpl.cplmobileapp.MainActivity { *; }

# --- 2. PROTECT ALL DATA MODEL ENGINES ---
# This forces R8 to leave your data classes completely alone so serialization doesn't fail
-keep class com.cpl.cplmobileapp.** { *; }

# --- 3. GSON & PREFERENCES REFLECTION SAFEGUARDS ---
# If you are saving cards as JSON strings, these keep the serialization engine stable
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# --- 4. ANDROID JETPACK & STORAGE COMPONENT PROTECTIONS ---
-keep class androidx.preference.** { *; }
-keep class androidx.security.crypto.** { *; }