# Keep Python and PyTorch classes
-keep class com.chaquo.python.** { *; }
-keep class org.pytorch.** { *; }
-keep class torch.** { *; }
-keep class numpy.** { *; }
-keep class librosa.** { *; }
-keep class scipy.** { *; }
-keep class soundfile.** { *; }
-keep class pyworld.** { *; }
-keep class crepe.** { *; }
-keep class rmvpe.** { *; }
-keep class faiss.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class omegaconf.** { *; }
-keep class hydra.** { *; }
-keep class sklearn.** { *; }
-keep class numba.** { *; }
-keep class resampy.** { *; }
-keep class matplotlib.** { *; }
-keep class tqdm.** { *; }
-keep class requests.** { *; }
-keep class packaging.** { *; }

# Keep Ultimate RVC classes
-keep class python.** { *; }
-keep class ultimate_rvc.** { *; }
-keep class main.** { *; }
-keep class download_weights.** { *; }

# Keep Flutter plugin classes
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.** { *; }
-keep class io.flutter.util.** { *; }
-keep class io.flutter.view.** { *; }
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }

# Keep method channels
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keepattributes JavascriptInterface
-keepattributes *Annotation*

# Don't warn about missing classes
-dontwarn com.chaquo.python.**
-dontwarn org.pytorch.**
-dontwarn torch.**
-dontwarn numpy.**
-dontwarn librosa.**
-dontwarn scipy.**
-dontwarn soundfile.**
-dontwarn pyworld.**
-dontwarn crepe.**
-dontwarn rmvpe.**
-dontwarn faiss.**
-dontwarn ai.onnxruntime.**
-dontwarn omegaconf.**
-dontwarn hydra.**
-dontwarn sklearn.**
-dontwarn numba.**
-dontwarn resampy.**
-dontwarn matplotlib.**
-dontwarn tqdm.**
-dontwarn requests.**
-dontwarn packaging.**
-dontwarn python.**
-dontwarn ultimate_rvc.**
-dontwarn main.**
-dontwarn download_weights.**
