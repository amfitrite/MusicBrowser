## 2024-10-31 - SharedPreferences in High-Frequency UI Events
**Learning:** Querying `SharedPreferences` (e.g., `getBoolean`, `getString`) within high-frequency UI events like `onScrollChanged` and `onFling` in Android can introduce lock contention and micro-stutters.
**Action:** Cache `SharedPreferences` values in instance properties for high-frequency access points, initializing them in `onCreate` and updating them only when the settings change, to avoid main-thread jank.

## 2026-11-01 - Optimize String Evaluation in High-Frequency Events
**Learning:** Evaluating strings (e.g., comparing string settings) in high-frequency Android UI events like `onScrollChanged` and `onFling` causes micro-overhead that can reduce FPS and introduce micro-stutters.
**Action:** Convert String-based configurations to primitive booleans and cache them as instance properties to avoid runtime string comparisons in high-frequency UI access points, ensuring main-thread stability and max FPS.
