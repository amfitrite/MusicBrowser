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
