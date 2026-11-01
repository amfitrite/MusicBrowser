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
            // Spotify desktop/web player and common player controls.
            "[data-testid='control-button-skip-forward']",
            "[data-testid='next-button']",
            "button[data-testid*='skip-forward']",
            // SoundCloud and YouTube / YouTube Music.
            ".skipControl__next", ".playControls__next", ".ytp-next-button",
            "ytmusic-player-bar .next-button",
            "ytmusic-player-bar button[aria-label*='Next' i]",
            // Generic accessible controls, including localized/alternate markup.
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

        // Prefer known player controls before generic labels to avoid clicking
        // unrelated navigation links that happen to contain the word "next".
        for (const selector of selectors) {
            for (const button of document.querySelectorAll(selector)) {
                if (usable(button)) return button;
            }
        }

        // Generic fallback for sites whose player uses ordinary buttons or
        // role=button elements rather than a known platform-specific selector.
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
            ].filter(Boolean).join(" ").trim().replace(/\\s+/g, " ");
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
                return;
            }
            if ((cmd === "pause" || (cmd === "play_pause" && !media.paused)) && !media.paused) {
                media.pause();
                return;
            }
        }

        const labels = cmd === "play" ? ["play"] : cmd === "pause" ? ["pause"] : ["play", "pause"];
        const button = Array.from(document.querySelectorAll("button")).find(b =>
            labels.some(label => (b.getAttribute("aria-label") || "").toLowerCase().includes(label))
        );
        if (button) button.click();
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
            // Replaying the same source starts a new outro-skip cycle.
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
                    // Android media keys have no reliable completion acknowledgement.
                    // The track-key guard below prevents repeated skips once playback advances.
                    browser.runtime.sendMessage({ action: "trigger_media_next" }).catch(() => {});
                    media.__musicBrowserSkipped = key;
                } else if (command("next")) {
                    // Only latch when a real Next control was found and clicked.
                    // If no control exists, retry on the next monitor tick instead of
                    // permanently marking the outro as skipped.
                    media.__musicBrowserSkipped = key;
                }
            }
        }
        report(false);
    }

    browser.runtime.onMessage.addListener(message => {
        if (message.type === "SET_CONFIG") {
            skipSeconds = Math.max(0, Math.min(30, Number(message.skipTime) || 0));
            skipMethod = message.skipMethod === "A" ? "A" : "B";
        } else if (message.type === "MEDIA_CMD") {
            command(message.cmd);
            report(true);
        }
    });

    browser.runtime.sendMessage({ action: "request_config" }).catch(() => {});

    // Avoid a permanent requestAnimationFrame loop on every page. It only
    // counted display-synchronized callbacks and added work without improving rendering.

    setInterval(monitor, 1000);
    monitor();
})();