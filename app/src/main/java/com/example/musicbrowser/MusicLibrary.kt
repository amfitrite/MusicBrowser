package com.example.musicbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local metadata-only playlist and listening history shared across providers. */
object MusicLibrary {
    private const val PREFS = "BrowserPrefs"
    private const val PLAYLIST = "playlist_items"
    private const val HISTORY = "listening_history"
    private const val MAX_ITEMS = 200
    private val lock = Any()

    fun addToPlaylist(context: Context, track: MusicTrack): Boolean {
        if (track.title.isBlank() || track.url.isBlank()) return false
        synchronized(lock) {
            val items = read(context, PLAYLIST)
            items.removeAll { it.stableKey() == track.stableKey() }
            items.add(0, track.copy(isPlaying = false))
            write(context, PLAYLIST, items.take(MAX_ITEMS))
        }
        return true
    }

    fun recordHistory(context: Context, track: MusicTrack) {
        if (track.title.isBlank() || track.url.isBlank()) return
        synchronized(lock) {
            val items = read(context, HISTORY)
            items.removeAll { it.stableKey() == track.stableKey() }
            items.add(0, track)
            write(context, HISTORY, items.take(MAX_ITEMS))
        }
    }

    fun getTracks(context: Context, history: Boolean): List<MusicTrack> =
        synchronized(lock) { read(context, if (history) HISTORY else PLAYLIST) }

    fun removeTrack(context: Context, history: Boolean, index: Int) {
        synchronized(lock) {
            val key = if (history) HISTORY else PLAYLIST
            val items = read(context, key)
            if (index in items.indices) {
                items.removeAt(index)
                write(context, key, items)
            }
        }
    }

    private fun read(context: Context, key: String): MutableList<MusicTrack> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "[]") ?: "[]"
        val tracks = mutableListOf<MusicTrack>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                tracks.add(
                    MusicTrack(
                        title = item.optString("title", ""),
                        artist = item.optString("artist", ""),
                        url = item.optString("url", ""),
                        provider = item.optString("provider", "Web"),
                        artworkUrl = item.optString("artworkUrl", ""),
                        durationMs = item.optLong("durationMs", 0L),
                        positionMs = item.optLong("positionMs", 0L),
                        isPlaying = item.optBoolean("isPlaying", false)
                    )
                )
            }
        } catch (_: Exception) {
            // Corrupt preference data is ignored rather than crashing playback.
        }
        return tracks
    }

    private fun write(context: Context, key: String, tracks: List<MusicTrack>) {
        val array = JSONArray()
        tracks.forEach { t ->
            array.put(
                JSONObject()
                    .put("title", t.title)
                    .put("artist", t.artist)
                    .put("url", t.url)
                    .put("provider", t.provider)
                    .put("artworkUrl", t.artworkUrl)
                    .put("durationMs", t.durationMs)
                    .put("positionMs", t.positionMs)
                    .put("isPlaying", t.isPlaying)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, array.toString()).apply()
    }
}