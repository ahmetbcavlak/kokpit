# Kokpit Ana Ekran: no reflection, no serialization, no JNI.
# The default proguard-android-optimize.txt rules keep the Activity from the manifest.
# Keep source file and line numbers so crash reports from the head unit stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
