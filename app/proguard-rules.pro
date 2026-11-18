# Keep Room entities, enums, and Gson backup models
-keep class com.feiji.bulletnotes.data.** { *; }

# Keep generic signatures & Gson TypeToken subclasses for R8 full mode
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
