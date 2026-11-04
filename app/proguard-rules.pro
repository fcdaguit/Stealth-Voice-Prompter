# Vosk uses JNA, which resolves native bindings reflectively.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**

# Vosk JNI surface
-keep class org.vosk.** { *; }

# --- Document import libraries ---------------------------------------------
# Currently unused (see the commented-out deps in build.gradle.kts), kept here
# so re-enabling them does not reintroduce the R8 failure.
#
# Important: R8's "Missing class X" error is silenced by a -dontwarn naming
# the MISSING class, not the class that references it. -dontwarn org.apache.poi.**
# does nothing for a missing org.osgi.framework.Bundle referenced from POI's
# transitive log4j-api. The first three rules below are the ones that matter.
-dontwarn aQute.bnd.annotation.**
-dontwarn org.osgi.framework.**
-dontwarn org.apache.logging.log4j.**

-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn com.tom_roush.pdfbox.**
-dontwarn javax.xml.stream.**
-dontwarn org.w3c.dom.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
