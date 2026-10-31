let nativePort = null;
let selectedTabId = null;
let playingTabId = null;

function connectNative() {
    if (nativePort) return;
    try {
        nativePort = browser.runtime.connectNative("musicbrowser.internal");
        nativePort.onMessage.addListener(message => {
            if (!message || !message.type) return;
            if (message.type === "SET_CONFIG") {
                browser.tabs.query({}).then(tabs => {
                    tabs.forEach(tab => {
                        if (tab.id !== undefined) browser.tabs.sendMessage(tab.id, message).catch(() => {});
                    });
                });
            } else if (message.type === "MEDIA_CMD") {
                const target = playingTabId !== null ? playingTabId : selectedTabId;
                if (target !== null && target !== undefined) {
                    browser.tabs.sendMessage(target, message).catch(() => {});
                }
            }
        });
        nativePort.onDisconnect.addListener(() => { nativePort = null; });
    } catch (error) {
        nativePort = null;
        console.error("Unable to connect media bridge", error);
    }
}

function sendNative(message) {
    connectNative();
    if (!nativePort) return;
    try { nativePort.postMessage(message); }
    catch (error) { nativePort = null; console.error("Native media bridge disconnected", error); }
}

browser.tabs.onActivated.addListener(info => { selectedTabId = info.tabId; });
browser.tabs.onRemoved.addListener(tabId => {
    if (selectedTabId === tabId) selectedTabId = null;
    if (playingTabId === tabId) playingTabId = null;
});
browser.tabs.query({ active: true, currentWindow: true }).then(tabs => {
    if (tabs[0]) selectedTabId = tabs[0].id;
});

browser.runtime.onMessage.addListener((message, sender) => {
    if (!message) return;
    if (message.action === "request_config" || message.action === "trigger_media_next") {
        sendNative(message);
        return;
    }
    if (message.type === "MEDIA_STATE" && sender && sender.tab && sender.tab.id !== undefined) {
        const tabId = sender.tab.id;
        const wasPlayingTab = playingTabId === tabId;
        if (message.isPlaying) playingTabId = tabId;
        else if (wasPlayingTab) playingTabId = null;

        // Ignore paused media from background tabs while another tab owns playback.
        const shouldForward = message.isPlaying || wasPlayingTab ||
            (playingTabId === null && tabId === selectedTabId) ||
            tabId === playingTabId;
        if (shouldForward) sendNative(message);
    }
});

connectNative();