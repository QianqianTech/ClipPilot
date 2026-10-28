# R8 configuration for release builds.
#
# Components (MainActivity, AutoSwipeAccessibilityService, SessionCommandReceiver) are referenced
# from the manifest and kept automatically. The app uses no reflection, serialization or JNI, and
# org.json is part of the Android framework, so no extra keep rules are required today.
#
# Keep source file names and line numbers so crash reports from release builds stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
