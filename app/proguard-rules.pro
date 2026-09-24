# Experimental R8 rules for the performance branch.
# Android manifest components receive generated keep rules from AGP, but keep the
# process entry points explicit while testing more aggressive shrinking.
-keep class com.saas.x11manager.X11Application { *; }
-keep class com.saas.x11manager.MainActivity { *; }
-keep class com.saas.x11manager.operations.LogOperationService { *; }
-keep class com.saas.x11manager.operations.OperationNotificationReceiver { *; }

# Preserve any managed declarations whose names are bound directly from JNI.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Lorie's Java/native boundary is upstream code and uses name-based JNI callbacks.
# Keep it stable while allowing the Manager's own Kotlin/Compose code to shrink.
-keep class com.termux.x11.** { *; }

# libsu may use reflection/proxy internals; keep its current safety boundary.
-keep class com.topjohnwu.superuser.** { *; }
