(function() {
    function injectScript() {
        const script = document.createElement('script');
        script.textContent = `
            Object.defineProperty(document, 'visibilityState', { get: () => 'visible' });
            Object.defineProperty(document, 'hidden', { get: () => false });
            Object.defineProperty(document, 'webkitVisibilityState', { get: () => 'visible' });
            Object.defineProperty(document, 'webkitHidden', { get: () => false });

            const block = e => {
                e.stopImmediatePropagation();
                e.stopPropagation();
            };
            document.addEventListener('visibilitychange', block, true);
            document.addEventListener('webkitvisibilitychange', block, true);
            window.addEventListener('pagehide', block, true);
            window.addEventListener('blur', block, true);
            document.hasFocus = function() { return true; };

            // Instant Touch Response CSS (Zero-Delay Click)
            const style = document.createElement('style');
            style.textContent = '* { touch-action: manipulation !important; -webkit-tap-highlight-color: transparent !important; }';
            (document.head || document.documentElement).appendChild(style);

            // Uncapped High-Precision Web Engine FPS Counter (Unthrottled Timer Loop)
            let webFrames = 0;
            let lastWebTime = performance.now();
            function measureWebFps() {
                webFrames++;
                const now = performance.now();
                if (now - lastWebTime >= 1000) {
                    const rawFps = Math.round((webFrames * 1000) / (now - lastWebTime));
                    window.__uncapped_web_fps = rawFps;
                    webFrames = 0;
                    lastWebTime = now;
                }
                setTimeout(measureWebFps, 0);
            }
            setTimeout(measureWebFps, 0);
        `;

        try {
            const target = document.head || document.documentElement;
            if (!target) return false;
            target.appendChild(script);
            script.remove();
            return true;
        } catch (e) {
            return false;
        }
    }

    if (!injectScript()) {
        document.addEventListener('DOMContentLoaded', injectScript, { once: true });
    }

    // --- GOOGLE CHROME LABS QUICKLINK (INSTANT PAGE PREFETCHING) ---
    let quicklinkEnabled = true;
    const prefetchedUrls = new Set();

    function initQuicklink() {
        if (!quicklinkEnabled) return;
        if (navigator.connection && (navigator.connection.saveData || /2g/.test(navigator.connection.effectiveType))) {
            return;
        }

        const observer = new IntersectionObserver((entries) => {
            entries.forEach(entry => {
                if (entry.isIntersecting && quicklinkEnabled) {
                    const link = entry.target;
                    const url = link.href;
                    if (url && !prefetchedUrls.has(url) && url.startsWith(location.origin) && !url.includes('#')) {
                        prefetchedUrls.add(url);
                        prefetchLink(url);
                    }
                    observer.unobserve(link);
                }
            });
        }, { rootMargin: '50px' });

        function prefetchLink(url) {
            try {
                const req = document.createElement('link');
                req.rel = 'prefetch';
                req.href = url;
                req.as = 'document';
                (document.head || document.documentElement).appendChild(req);
            } catch (e) {}

            try {
                fetch(url, { mode: 'no-cors', credentials: 'same-origin', priority: 'low' }).catch(() => {});
            } catch (e) {}
        }

        function scanAndObserve() {
            if (!quicklinkEnabled) return;
            const links = document.querySelectorAll('a[href]');
            for (let i = 0; i < links.length; i++) {
                const link = links[i];
                if (link.href && !prefetchedUrls.has(link.href) && link.href.startsWith(location.origin) && !link.href.includes('#')) {
                    observer.observe(link);
                }
            }
        }

        if ('requestIdleCallback' in window) {
            requestIdleCallback(() => scanAndObserve());
        } else {
            setTimeout(scanAndObserve, 1000);
        }

        try {
            const domObserver = new MutationObserver(() => {
                if (quicklinkEnabled) scanAndObserve();
            });
            domObserver.observe(document.body || document.documentElement, { childList: true, subtree: true });
        } catch (e) {}
    }

    if (document.readyState === 'complete' || document.readyState === 'interactive') {
        initQuicklink();
    } else {
        document.addEventListener('DOMContentLoaded', initQuicklink, { once: true });
    }
})();
