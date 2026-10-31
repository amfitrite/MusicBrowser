package com.example.musicbrowser

import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

/** Owns one Gecko runtime/session for this app process. */
object BrowserSessionStore {
    @Volatile var runtime: GeckoRuntime? = null
    @Volatile var session: GeckoSession? = null
    @Volatile var sessionOpened: Boolean = false
}