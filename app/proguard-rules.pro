# Shizuku binds GhostlockUserService by component name and talks over the
# generated AIDL stubs; R8 must keep both sides in release builds.
-keep class com.sesame.app.shizuku.GhostlockUserService { *; }
-keep interface com.sesame.app.shizuku.IGhostlockUserService { *; }
-keep interface com.sesame.app.shizuku.IGhostlockCallback { *; }
-keep class com.sesame.app.shizuku.IGhostlockUserService$Stub { *; }
-keep class com.sesame.app.shizuku.IGhostlockCallback$Stub { *; }
