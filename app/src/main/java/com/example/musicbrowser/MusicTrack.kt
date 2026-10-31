package com.example.musicbrowser

data class MusicTrack(
    val title: String,
    val artist: String = "",
    val url: String = "",
    val provider: String = "Web",
    val artworkUrl: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false
) {
    fun stableKey(): String = "$provider|$url|$title"
}