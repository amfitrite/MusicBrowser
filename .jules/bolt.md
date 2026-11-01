## 2024-10-31 - SharedPreferences in High-Frequency UI Events
**Learning:** Querying `SharedPreferences` (e.g., `getBoolean`, `getString`) within high-frequency UI events like `onScrollChanged` and `onFling` in Android can introduce lock contention and micro-stutters.
**Action:** Cache `SharedPreferences` values in instance properties for high-frequency access points, initializing them in `onCreate` and updating them only when the settings change, to avoid main-thread jank.
## 2024-05-18 - Uncap GeckoView FPS via YAML Configuration
**Learning:** Mozilla GeckoView internal preferences (like `layout.frame_rate` for uncapping rendering FPS above 60Hz) cannot be easily set via a Java/Kotlin method argument directly on the builder. The standard mechanism is to write a YAML configuration file representing the preferences and provide its path to `GeckoRuntimeSettings.Builder().configFilePath()`.
**Action:** When needing to configure low-level GeckoView preferences that lack a dedicated API method, generate the `gecko_config.yaml` file dynamically at runtime and inject its path into the runtime settings builder.
