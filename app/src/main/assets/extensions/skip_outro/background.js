let port = null;

function connectToNative() {
    try {
        port = browser.runtime.connectNative("musicbrowser.internal");

        port.onMessage.addListener((response) => {
            // Broadcast the config/cmd to all content scripts
            if (response.type === 'SET_CONFIG' || response.type === 'MEDIA_CMD') {
                browser.tabs.query({}).then(tabs => {
                    for (let tab of tabs) {
                        browser.tabs.sendMessage(tab.id, response).catch(e => {});
                    }
                });
            }
        });

        port.onDisconnect.addListener(() => {
            port = null;
        });
    } catch(e) {
        console.error("Failed to connect to native app: ", e);
        port = null;
    }
}

connectToNative();

// Listen for messages from content scripts
browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (!port) {
        connectToNative();
    }
    if (message.action === 'trigger_media_next' && port) {
        // Forward the trigger to Android native app
        port.postMessage(message);
    } else if (message.action === 'request_config' && port) {
        port.postMessage(message);
    }
});
