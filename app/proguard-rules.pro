# The YouTube embed calls these from JavaScript by name.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Android Automotive OS classes exist only in cars (compileOnly through useLibrary 'android.car').
-dontwarn android.car.**

# kotlinx.serialization, Media3, OkHttp and Coil ship their own consumer rules.
