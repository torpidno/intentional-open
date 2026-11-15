# Pause uses standard AndroidX / Hilt / Room consumer rules, which are bundled
# with those libraries. The rules below only cover manifest-driven entry points
# and enums that are referenced from persisted data.

# Accessibility services are instantiated by the system from the manifest.
-keep class com.pause.app.service.PauseAccessibilityService { *; }

# Keep enum names: friction modes and outcomes are stored in Room as enum names.
-keepclassmembers enum com.pause.app.domain.model.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
