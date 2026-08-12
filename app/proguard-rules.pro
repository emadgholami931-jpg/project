# Room and Compose generate the required keep rules automatically.
# Keep the Application entry point and JSON-backed backup models readable in release builds.
-keep class com.vazheyar.app.VazheYarApp { *; }
-keep class com.vazheyar.app.data.** { *; }
