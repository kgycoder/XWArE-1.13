# JavascriptInterface 메서드 보존
-keepclassmembers class com.xware.AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# 전체 패키지 보존
-keep class com.xware.** { *; }

# WebViewAssetLoader 보존
-keep class androidx.webkit.** { *; }
-dontwarn androidx.webkit.**

# 코루틴
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# WebViewClient
-keepclassmembers class * extends android.webkit.WebViewClient { public *; }
-keepclassmembers class * extends android.webkit.WebChromeClient { public *; }
-keepclassmembers class * extends androidx.webkit.WebViewClientCompat { public *; }

# JSON
-keep class org.json.** { *; }
-dontwarn org.json.**

-dontwarn android.**
-dontwarn androidx.**
