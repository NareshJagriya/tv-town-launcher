# The trailer page calls back into this object through JavaScript.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# Views named in layouts, and the services named in the manifest, are kept by AAPT's own rules.

# The red button's downloader: the library finds parts of itself by name, and its script engine needs its classes.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn org.jsoup.helper.Re2jRegex**
-dontwarn com.google.re2j.**
-dontwarn javax.annotation.**
-keepclasseswithmembernames class com.nareshjagriya.tvtown.Mp3Lame { native <methods>; }

# Started by name through the debugging connection, outside the app (RemoteKeys).
-keep class com.nareshjagriya.tvtown.KeyHelper { public static void main(java.lang.String[]); }
