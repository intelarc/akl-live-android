# AKL Live. Compose, MapLibre, Glance and WorkManager bring their own rules;
# these cover what's ours that the system creates by name.
-keep class nz.aryan.akllive.system.** { *; }
-keepclassmembers class * extends androidx.work.ListenableWorker { public <init>(...); }
-keep class * implements androidx.glance.appwidget.action.ActionCallback { public <init>(); }
# MapLibre's optional HTTP and location extras
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.android.gms.**
