(function () {
    let skipSeconds = 0;
    let skipMethod = "B";
    let lastReport = 0;
    let lastTrackKey = "";
    let lastTime = 0;
    let lastSkipAttemptAt = 0;
    let activeMedia = null;
    const attached = new WeakSet();

    function providerName() {
        const host = location.hostname.toLowerCase();
        if (host.includes("music.youtube.com")) return "YouTube Music";
        if (host.includes("youtube.com") || host.includes("youtu.be")) return "YouTube";
        if (host.includes("spotify.com")) return "Spotify";
        if (host.includes("soundcloud.com")) return "SoundCloud";
        if (host.includes("music.apple.com")) return "Apple Music";
        return "Web";
    }

    function textFrom(selectors) {
        for (const selector of selectors) {
            const node = document.querySelector(selector);
            const value = node && (node.getAttribute("content") || node.getAttribute("aria-label") || node.textContent);
            if (value && value.trim()) return value.trim();
        }
        return "";
    }

    function metadata() {
        const provider = providerName();
        const native = navigator.mediaSession && navigator.mediaSession.metadata;
        let title = native && native.title || "";
        let artist = native && native.artist || "";
        let artwork = native && native.artwork && native.artwork.length ? native.artwork[native.artwork.length - 1].src : "";

        if (!title && provider === "YouTube Music") {
            title = textFrom(["ytmusic-player-bar .title", ".ytmusic-player-bar .title"]);
            artist = artist || textFrom(["ytmusic-player-bar .byline", ".ytmusic-player-bar .byline"]);
            const image = document.querySelector("ytmusic-player-bar img");
            artwork = artwork || (image && (image.currentSrc || image.src)) || "";
        } else if (!title && provider === "YouTube") {
            title = textFrom([".ytp-title-link", "h1.ytd-watch-metadata", "meta[property='og:title']"]);
            artist = artist || textFrom(["ytd-channel-name a", "#upload-info a"]);
            const image = document.querySelector("meta[property='og:image']");
            artwork = artwork || (image && image.content) || "";
        } else if (!title && provider === "Spotify") {
            title = textFrom(["[data-testid='context-item-link']", "[data-testid='nowplaying-track-link']"]);
            artist = artist || textFrom(["[data-testid='context-item-info-artist']", "[data-testid='nowplaying-artist']"]);
            const image = document.querySelector("[data-testid='cover-art-image'] img");
            artwork = artwork || (image && (image.currentSrc || image.src)) || "";
        } else if (!title && provider === "SoundCloud") {
            title = textFrom([".playbackSoundBadge__title", ".playbackSoundBadge__titleLink"]);
            artist = artist || textFrom([".playbackSoundBadge__lightLink", ".playbackSoundBadge__artist"]);
            const image = document.querySelector(".playbackSoundBadge__avatar img");
            artwork = artwork || (image && (image.currentSrc || image.src)) || "";
        } else if (!title && provider === "Apple Music") {
            title = textFrom(["[data-testid='track-title']", ".web-chrome-playback-lcd__song-name", ".now-playing__track-name"]);
            artist = artist || textFrom(["[data-testid='track-artist']", ".web-chrome-playback-lcd__by-line"]);
            const image = document.querySelector("[data-testid='artwork-component'] img");
            artwork = artwork || (image && (image.currentSrc || image.src)) || "";
        }

        if (!title) {
            title = document.title.replace(/\s*[-|]\s*(YouTube Music|YouTube|Spotify|SoundCloud|Apple Music).*$/i, "").trim();
        }
        return { title: title || "Unknown track", artist, artwork, provider };
    }

    function mediaList() {
        return Array.from(document.querySelectorAll("audio, video"));
    }

    function getMedia() {
        const items = mediaList();
        const playing = items.filter(m => !m.paused && !m.ended && m.readyState >= 1);
        if (playing.length) {
            activeMedia = playing.sort((a, b) => (b.duration || 0) - (a.duration || 0))[0];
        } else if (!activeMedia || !items.includes(activeMedia)) {
            activeMedia = items.find(m => m.readyState >= 1) || items[0] || null;
        }
        return activeMedia;
    }

    function findButton(kind) {
        const next = [
            "[data-testid='control-button-skip-forward']",
            "[data-testid='next-button']",
            "button[data-testid*='skip-forward']",
            ".skipControl__next", ".playControls__next", ".ytp-next-button",
            "ytmusic-player-bar .next-button",
            "ytmusic-player-bar button[aria-label*='Next' i]",
            "button[aria-label*='Next track' i]", "button[aria-label*='Next song' i]",
            "button[aria-label*='Next' i]", "button[title*='Next' i]",
            "button[aria-label*='Berikutnya' i]", "button[title*='Berikutnya' i]"
        ];
        const previous = [
            "[data-testid='control-button-skip-back']", "[data-testid='previous-button']",
            ".skipControl__previous", ".playControls__prev", ".ytp-prev-button",
            "ytmusic-player-bar .previous-button",
            "button[aria-label*='Previous' i]", "button[title*='Previous' i]"
        ];
        const selectors = kind === "next" ? next : previous;
        const usable = button => button && !button.disabled &&
            button.getAttribute("aria-disabled") !== "true" &&
            button.getAttribute("disabled") === null &&
            button.getAttribute("aria-hidden") !== "true" &&
            button.getClientRects().length > 0;

        for (const selector of selectors) {
            for (const button of document.querySelectorAll(selector)) {
                if (usable(button)) return button;
            }
        }

        const labels = kind === "next"
            ? /^(next|next track|next song|skip forward|berikutnya|lagu berikutnya|selanjutnya|siguiente|suivant|weiter|下一首|次の曲)$/i
            : /^(previous|previous track|previous song|back|lagu sebelumnya|sebelumnya|anterior|précédent|zurück|上一首|前の曲)$/i;
        for (const button of document.querySelectorAll("button, [role='button']")) {
            if (!usable(button)) continue;
            const label = [
                button.getAttribute("aria-label"),
                button.getAttribute("title"),
                button.getAttribute("data-tooltip"),
                button.innerText
            ].filter(Boolean).join(" ").trim().replace(/\s+/g, " ");
            if (labels.test(label)) return button;
        }
        return null;
    }

    function command(cmd) {
        const media = getMedia();
        if (cmd === "next" || cmd === "prev") {
            const button = findButton(cmd);
            if (button) {
                button.click();
                return true;
            }
            if (media && cmd === "prev" && media.currentTime > 3) {
                media.currentTime = 0;
                return true;
            }
            return false;
        }

        if (media) {
            if ((cmd === "play" || (cmd === "play_pause" && media.paused)) && media.paused) {
                const promise = media.play();
                if (promise && promise.catch) promise.catch(() => {});
                return true;
            }
            if ((cmd === "pause" || (cmd === "play_pause" && !media.paused)) && !media.paused) {
                media.pause();
                return true;
            }
        }

        const labels = cmd === "play" ? ["play"] : cmd === "pause" ? ["pause"] : ["play", "pause"];
        const button = Array.from(document.querySelectorAll("button")).find(b =>
            labels.some(label => (b.getAttribute("aria-label") || "").toLowerCase().includes(label))
        );
        if (button) {
            button.click();
            return true;
        }
        return false;
    }

    function report(force) {
        const media = getMedia();
        const meta = metadata();
        const playing = !!media && !media.paused && !media.ended;
        const key = [location.href, meta.title, media && (media.currentSrc || media.src || "")].join("|");

        if (key !== lastTrackKey) {
            lastTrackKey = key;
            lastTime = media ? media.currentTime : 0;
        } else if (media && media.currentTime + 2 < lastTime) {
            lastTime = media.currentTime;
            media.__musicBrowserCycle = (media.__musicBrowserCycle || 0) + 1;
            media.__musicBrowserSkipped = "";
        } else if (media) {
            lastTime = media.currentTime;
        }

        const now = Date.now();
        if (!force && now - lastReport < 3000) return;
        lastReport = now;
        browser.runtime.sendMessage({
            type: "MEDIA_STATE",
            title: meta.title,
            artist: meta.artist,
            provider: meta.provider,
            artwork: meta.artwork,
            url: location.href,
            duration: media && Number.isFinite(media.duration) ? media.duration : 0,
            currentTime: media ? media.currentTime : 0,
            isPlaying: playing
        }).catch(() => {});
    }

    function monitor() {
        const list = mediaList();
        list.forEach(media => {
            if (attached.has(media)) return;
            attached.add(media);
            ["playing", "pause", "loadedmetadata", "durationchange", "ended", "emptied"].forEach(name => {
                media.addEventListener(name, () => {
                    activeMedia = media;
                    report(true);
                });
            });
        });

        const media = getMedia();
        if (media && !media.paused && !media.ended && skipSeconds > 0 && Number.isFinite(media.duration)) {
            const remaining = media.duration - media.currentTime;
            const cycle = media.__musicBrowserCycle || 0;
            const key = lastTrackKey + "|" + cycle;
            const now = Date.now();
            if (remaining > 0 && remaining <= skipSeconds &&
                media.__musicBrowserSkipped !== key && now - lastSkipAttemptAt >= 2000) {
                lastSkipAttemptAt = now;
                if (skipMethod === "A") {
                    browser.runtime.sendMessage({ action: "trigger_media_next" }).catch(() => {});
                    media.__musicBrowserSkipped = key;
                } else if (command("next")) {
                    media.__musicBrowserSkipped = key;
                }
            }
        }
        report(false);
    }

    // Speculative preload engine: nearby links, posters, and HTML media.
    // The setting is received through this extension's existing native bridge.
    let quicklinkEnabled = false;
    let quicklinkStarted = false;
    let pageObserver = null;
    let mediaObserver = null;
    let domObserver = null;
    let scanScheduled = false;
    let pagePrefetchCount = 0;
    let mediaAutoPreloadCount = 0;
    const MAX_PAGE_PREFETCHES = 12;
    const MAX_MEDIA_METADATA = 6;
    const MAX_MEDIA_AUTO_PRELOADS = 1;
    const prefetchedPages = new Set();
    const pageHintNodes = new Map();
    const mediaOriginalPreload = new Map();
    const mediaPrefetchers = [];
    const prefetchedMediaTargets = new Set();
    let observedLinks = new WeakSet();
    let observedMedia = new WeakSet();

    function prefetchNetworkAllowed() {
        const connection = navigator.connection || navigator.mozConnection || navigator.webkitConnection;
        if (!connection) return true;
        const type = String(connection.effectiveType || "").toLowerCase();
        return !connection.saveData &&
            !/^(slow-2g|2g)$/.test(type) &&
            !(Number(connection.downlink) > 0 && Number(connection.downlink) < 0.6);
    }

    function normalizePrefetchUrl(rawUrl) {
        try {
            const url = new URL(rawUrl, location.href);
            if (url.protocol !== "http:" && url.protocol !== "https:") return null;
            if (url.href.split("#")[0] === location.href.split("#")[0]) return null;
            return url.href;
        } catch (_) {
            return null;
        }
    }

    function isActionLikeUrl(url) {
        return /(?:logout|log-out|signout|sign-out|delete|remove-account|unsubscribe|purchase|checkout|payment|add-to-cart)(?:[/?#=_-]|$)/i.test(url);
    }

    function appendPrefetchHint(url) {
        if (!quicklinkEnabled || !prefetchNetworkAllowed()) return null;
        const normalized = normalizePrefetchUrl(url);
        if (!normalized || pageHintNodes.has(normalized)) return null;
        try {
            const hint = document.createElement("link");
            hint.rel = "prefetch";
            hint.href = normalized;
            hint.referrerPolicy = "strict-origin-when-cross-origin";
            (document.head || document.documentElement).appendChild(hint);
            pageHintNodes.set(normalized, hint);
            return hint;
        } catch (_) {
            return null;
        }
    }

    function prefetchPage(url) {
        if (pagePrefetchCount >= MAX_PAGE_PREFETCHES || prefetchedPages.has(url)) return;
        if (!appendPrefetchHint(url)) return;
        prefetchedPages.add(url);
        pagePrefetchCount++;
    }

    function mediaUrlFromElement(media) {
        if (media.srcObject) return "";
        const raw = media.currentSrc || media.src ||
            (media.querySelector("source[src]") && media.querySelector("source[src]").src) || "";
        if (!raw || /^(blob:|data:)/i.test(raw)) return "";
        return normalizePrefetchUrl(raw) || "";
    }

    function prefetchPoster(media) {
        const poster = media.poster;
        if (poster) appendPrefetchHint(poster);
    }

    function warmMediaElement(media, userIntent) {
        if (!quicklinkEnabled || !prefetchNetworkAllowed() || !media || media.error) return;
        if (media.paused === false || media.ended) return;
        const mediaUrl = mediaUrlFromElement(media);
        // MSE players (including many adaptive/DRM services) use blob: or
        // MediaSource-managed segment requests. Changing their preload hint
        // cannot safely prefetch those segments, so leave them to the player.
        if (!mediaUrl) return;

        prefetchPoster(media);
        if (!mediaOriginalPreload.has(media)) {
            mediaOriginalPreload.set(media, media.getAttribute("preload"));
        }

        const isVideo = media.tagName === "VIDEO";
        const canUseAuto = isVideo &&
            mediaAutoPreloadCount < MAX_MEDIA_AUTO_PRELOADS &&
            (userIntent || media.getBoundingClientRect().top < window.innerHeight);
        if (canUseAuto) {
            media.preload = "auto";
            mediaAutoPreloadCount++;
        } else {
            const metadataCount = Array.from(mediaOriginalPreload.keys()).length;
            if (metadataCount > MAX_MEDIA_METADATA) return;
            media.preload = "metadata";
        }

        if (media.readyState === 0) {
            try { media.load(); } catch (_) {}
        }
    }

    function isDirectMediaUrl(url) {
        return /\.(?:mp4|m4v|webm|mov|m3u8|mpd)(?:$|[?#])/i.test(url);
    }

    function prefetchDirectMediaLink(url) {
        if (prefetchedMediaTargets.has(url) || !prefetchNetworkAllowed()) return;
        if (!isDirectMediaUrl(url) || mediaPrefetchers.length >= 2) return;
        prefetchedMediaTargets.add(url);

        // HLS/DASH manifests are small hints only. Their media segments and
        // DRM/license requests are controlled by the site's playback engine.
        if (/\.(?:m3u8|mpd)(?:$|[?#])/i.test(url)) {
            appendPrefetchHint(url);
            return;
        }

        try {
            const media = document.createElement("video");
            media.preload = "metadata";
            media.muted = true;
            media.playsInline = true;
            media.src = url;
            media.load();
            mediaPrefetchers.push(media);
        } catch (_) {}
    }

    function eligiblePreloadLink(link) {
        if (!link || link.hasAttribute("download")) return null;
        if ((link.getAttribute("rel") || "").toLowerCase().split(/\s+/).includes("nofollow")) return null;
        const url = normalizePrefetchUrl(link.href);
        if (!url || isActionLikeUrl(url)) return null;
        return url;
    }

    function preloadLinkTarget(url) {
        if (isDirectMediaUrl(url)) prefetchDirectMediaLink(url);
        else prefetchPage(url);
    }

    function observePageLink(link) {
        if (!quicklinkEnabled || observedLinks.has(link) || !pageObserver) return;
        const url = eligiblePreloadLink(link);
        if (!url) return;
        observedLinks.add(link);
        pageObserver.observe(link);
    }

    function observeMediaElement(media) {
        if (!quicklinkEnabled || observedMedia.has(media) || !mediaObserver) return;
        observedMedia.add(media);
        mediaObserver.observe(media);
    }

    function scanPreloadCandidates() {
        scanScheduled = false;
        if (!quicklinkEnabled || !document.documentElement) return;
        document.querySelectorAll("a[href]").forEach(observePageLink);
        document.querySelectorAll("video, audio").forEach(observeMediaElement);
    }

    function schedulePreloadScan() {
        if (scanScheduled || !quicklinkEnabled) return;
        scanScheduled = true;
        if ("requestIdleCallback" in window) {
            requestIdleCallback(scanPreloadCandidates, { timeout: 1200 });
        } else {
            setTimeout(scanPreloadCandidates, 250);
        }
    }

    function onPreloadIntent(event) {
        if (!quicklinkEnabled || !prefetchNetworkAllowed()) return;
        const target = event.target && event.target.closest ?
            event.target.closest("a[href], video, audio") : null;
        if (!target) return;
        if (target.tagName === "A") {
            const url = eligiblePreloadLink(target);
            if (url) preloadLinkTarget(url);
        } else {
            warmMediaElement(target, true);
        }
    }

    function stopQuicklink() {
        if (pageObserver) pageObserver.disconnect();
        if (mediaObserver) mediaObserver.disconnect();
        if (domObserver) domObserver.disconnect();
        pageObserver = null;
        mediaObserver = null;
        domObserver = null;
        quicklinkStarted = false;
        scanScheduled = false;
        document.removeEventListener("pointerover", onPreloadIntent, true);
        document.removeEventListener("touchstart", onPreloadIntent, true);
        document.removeEventListener("focusin", onPreloadIntent, true);
        pageHintNodes.forEach(node => node.remove());
        pageHintNodes.clear();
        mediaOriginalPreload.forEach((original, media) => {
            if (!media || !media.isConnected) return;
            if (original === null) media.removeAttribute("preload");
            else media.setAttribute("preload", original);
        });
        mediaOriginalPreload.clear();
        mediaPrefetchers.forEach(media => {
            try { media.pause(); media.removeAttribute("src"); media.load(); } catch (_) {}
        });
        mediaPrefetchers.length = 0;
        prefetchedMediaTargets.clear();
        pagePrefetchCount = 0;
        mediaAutoPreloadCount = 0;
        prefetchedPages.clear();
        observedLinks = new WeakSet();
        observedMedia = new WeakSet();
    }

    function startQuicklink() {
        if (!quicklinkEnabled || quicklinkStarted || !prefetchNetworkAllowed()) return;
        if (!document.documentElement) return;
        quicklinkStarted = true;
        pageObserver = new IntersectionObserver(entries => {
            entries.forEach(entry => {
                if (!entry.isIntersecting) return;
                const link = entry.target;
                const url = eligiblePreloadLink(link);
                if (url) preloadLinkTarget(url);
                pageObserver.unobserve(link);
            });
        }, { rootMargin: "250px 0px" });

        mediaObserver = new IntersectionObserver(entries => {
            entries.forEach(entry => {
                if (!entry.isIntersecting) return;
                warmMediaElement(entry.target, false);
                mediaObserver.unobserve(entry.target);
            });
        }, { rootMargin: "400px 0px" });

        document.addEventListener("pointerover", onPreloadIntent, true);
        document.addEventListener("touchstart", onPreloadIntent, { capture: true, passive: true });
        document.addEventListener("focusin", onPreloadIntent, true);
        schedulePreloadScan();

        domObserver = new MutationObserver(schedulePreloadScan);
        domObserver.observe(document.documentElement, { childList: true, subtree: true });
    }

    function configureQuicklink(enabled) {
        quicklinkEnabled = enabled === true;
        if (!quicklinkEnabled) {
            stopQuicklink();
            return;
        }
        startQuicklink();
    }

    const networkConnection = navigator.connection || navigator.mozConnection || navigator.webkitConnection;
    if (networkConnection && networkConnection.addEventListener) {
        networkConnection.addEventListener("change", () => {
            if (quicklinkEnabled && prefetchNetworkAllowed()) startQuicklink();
            else if (quicklinkStarted && !prefetchNetworkAllowed()) stopQuicklink();
        });
    }

    let resumedMediaKey = "";

    browser.runtime.onMessage.addListener(message => {
        if (message.type === "SET_CONFIG") {
            skipSeconds = Math.max(0, Math.min(30, Number(message.skipTime) || 0));
            skipMethod = message.skipMethod === "A" ? "A" : "B";
            configureQuicklink(message.quicklinkEnabled === true);

            const targetUrl = message.resumeMediaUrl;
            const targetTime = Number(message.resumeMediaPositionSec) || 0;

            if (targetUrl && targetTime > 3 && location.href.includes(targetUrl.split('#')[0].split('?')[0])) {
                const key = targetUrl + "|" + Math.floor(targetTime);
                if (resumedMediaKey !== key) {
                    resumedMediaKey = key;
                    let attempts = 5;
                    function attemptResume() {
                        const media = getMedia();
                        if (media && media.readyState >= 1) {
                            if (Math.abs(media.currentTime - targetTime) > 3) {
                                media.currentTime = targetTime;
                            }
                        } else if (attempts > 0) {
                            attempts--;
                            setTimeout(attemptResume, 1000);
                        }
                    }
                    setTimeout(attemptResume, 1000);
                }
            }
        } else if (message.type === "MEDIA_CMD") {
            command(message.cmd);
            report(true);
        }
    });

    browser.runtime.sendMessage({ action: "request_config" }).catch(() => {});

    setInterval(monitor, 1000);
    monitor();
})();