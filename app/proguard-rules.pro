# ProCam — keep Camera2 metadata keys reachable via reflection-free paths.
-keepclassmembers class * extends android.hardware.camera2.CameraMetadata { *; }
