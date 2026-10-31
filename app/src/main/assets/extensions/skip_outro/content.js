(function() {
    let skipTime = 0;
    let skipMethod = 'B';
    let monitorInterval = null;

    // Listen for config messages from Android
    browser.runtime.onMessage.addListener((message) => {
        if (message.type === 'SET_CONFIG') {
            skipTime = message.skipTime;
            skipMethod = message.skipMethod;
            startMonitoring();
        } else if (message.type === 'MEDIA_CMD') {
            handleMediaCmd(message.cmd);
        }
    });

    function handleMediaCmd(cmd) {
        if (cmd === 'play_pause') {
            const medias = document.querySelectorAll('video, audio');
            for (let i = 0; i < medias.length; i++) {
                if (medias[i].paused) medias[i].play();
                else medias[i].pause();
                break;
            }
        } else if (cmd === 'next') {
            const nextBtn = document.querySelector('.ytp-next-button') || document.querySelector('.icon-next') || document.querySelector('.ytm-next-button') || document.querySelector('.spoticon-skip-forward-16') || document.querySelector('button[aria-label="Next"]');
            if (nextBtn) nextBtn.click();
        } else if (cmd === 'prev') {
            const prevBtn = document.querySelector('.ytp-prev-button') || document.querySelector('.icon-previous') || document.querySelector('.ytm-previous-button') || document.querySelector('.spoticon-skip-back-16') || document.querySelector('button[aria-label="Previous"]');
            if (prevBtn) prevBtn.click();
        }
    }

    function startMonitoring() {
        if (skipTime <= 0) {
            if (monitorInterval) clearInterval(monitorInterval);
            return;
        }

        if (monitorInterval) clearInterval(monitorInterval);

        monitorInterval = setInterval(() => {
            const medias = document.querySelectorAll('video, audio');
            if (medias.length === 0) return;

            let activeMedia = null;
            for (let i = 0; i < medias.length; i++) {
                if (!medias[i].paused && medias[i].duration > 0) {
                    activeMedia = medias[i];
                    break;
                }
            }

            if (activeMedia) {
                const timeLeft = activeMedia.duration - activeMedia.currentTime;
                if (timeLeft > 0 && timeLeft <= skipTime) {
                    clearInterval(monitorInterval); // Trigger only once per song

                    if (skipMethod === 'A') {
                        browser.runtime.sendMessage({ action: 'trigger_media_next' });
                    } else {
                        handleMediaCmd('next');
                    }
                }
            }
        }, 1000);
    }

    // Request config on load
    browser.runtime.sendMessage({ action: 'request_config' });
})();
