# Both are named in the manifest and built by the system, so R8 has no call
# site to trace and will strip them. That failure is invisible in a debug build
# and shows up only as the service never connecting.
-keep class com.thorpad.app.TapService { *; }
-keep class com.thorpad.app.MainActivity { *; }
