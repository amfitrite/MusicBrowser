(function () {
    let skipSeconds = 0;
    let skipMethod = "B";
    let lastReport = 0;
    let lastTrackKey = "";
    let lastTime = 0;
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
            "[data-testid='control-button-skip-forward']", "[data-testid='next-button']",
            ".skipControl__next", ".ytp-next-button", "ytmusic-player-bar .next-button",
            "button[aria-label*='Next' i]", "button[title*='Next' i]"
        ];
        const previous = [
            "[data-testid='control-button-skip-back']", "[data-testid='previous-button']",
            ".skipControl__previous", ".ytp-prev-button", "ytmusic-player-bar .previous-button",
            "button[aria-label*='Previous' i]", "button[title*='Previous' i]"
        ];
        for (const selector of kind === "next" ? next : previous) {
            const button = document.querySelector(selector);
            if (button && !button.disabled && button.getAttribute("aria-disabled") !== "true") return button;
        }
        return null;
    }

    function command(cmd) {
        const media = getMedia();
        if (cmd === "next" || cmd === "prev") {
            const button = findButton(cmd);
            if (button) button.click();
            else if (media && cmd === "prev" && media.currentTime > 3) media.currentTime = 0;
            return;
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
            if (remaining > 0 && remaining <= skipSeconds && media.__musicBrowserSkipped !== key) {
                media.__musicBrowserSkipped = key;
                if (skipMethod === "A") browser.runtime.sendMessage({ action: "trigger_media_next" }).catch(() => {});
                else command("next");
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

    // Uncapped High-Precision Web Engine FPS Counter
    let webFrames = 0;
    let lastWebFpsTime = performance.now();
    function measureWebFps() {
        webFrames++;
        const now = performance.now();
        if (now - lastWebFpsTime >= 1000) {
            const rawFps = Math.round((webFrames * 1000) / (now - lastWebFpsTime));
            browser.runtime.sendMessage({ action: "report_web_fps", fps: rawFps }).catch(() => {});
            webFrames = 0;
            lastWebFpsTime = now;
        }
        requestAnimationFrame(measureWebFps);
    }
    requestAnimationFrame(measureWebFps);

    setInterval(monitor, 1000);
    monitor();
})();