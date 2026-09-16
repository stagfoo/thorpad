# Both are named in the manifest and built by the system, so R8 has no call
# site to trace and will strip them. That failure is invisible in a debug build
# and shows up only as the service never connecting.
-keep class com.thorpad.app.TapService { *; }
-keep class com.thorpad.app.MainActivity { *; }

# Shizuku starts the stick reader in another process and finds it by class
# name, so R8 has no call site to trace and strips it — the app then builds,
# installs, and fails the moment sticks are switched on.
-keep class com.thorpad.app.StickService { *; }
-keep class com.thorpad.app.IStickService { *; }
-keep class com.thorpad.app.IStickService$* { *; }
-keep class com.thorpad.app.IStickCallback { *; }
-keep class com.thorpad.app.IStickCallback$* { *; }
-keep class rikka.shizuku.** { *; }
