# Keep standalone CLI entry point invoked via app_process
-keep class com.chiller3.alterinstaller.Main {
    public static void main(java.lang.String[]);
}

# Keep the full logic classes so app_process can call them at runtime
-keep class com.chiller3.alterinstaller.** { *; }

# Keep Activity
-keep class com.chiller3.alterinstaller.MainActivity { *; }
