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

            // Uncapped High-Precision Web Engine FPS Counter
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
                requestAnimationFrame(measureWebFps);
            }
            requestAnimationFrame(measureWebFps);
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
})();
