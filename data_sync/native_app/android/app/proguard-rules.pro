# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /usr/local/Cellar/android-sdk/24.3.3/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# The health exporter preserves nested source metadata, unit values, stages and
# samples through SDK public properties. Keep their names if minification is enabled.
-keep class androidx.health.connect.client.records.** { public *; }
-keep class androidx.health.connect.client.units.** { public *; }
