## 2024-05-19 - SharedPreferences I/O on Main Thread
**Learning:** Reading `SharedPreferences` inside high-frequency callbacks like `onScrollChanged` or `onDraw` causes severe frame drops and lag, as it forces synchronous disk I/O on the main UI thread.
**Action:** Always cache `SharedPreferences` values in memory for high-frequency access and only update the cache when the user explicitly saves or changes the settings.
