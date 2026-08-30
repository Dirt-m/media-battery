// The built-in tracked platforms. Loads as the first content script on every tracked
// page and exposes `window.TRACKED_PLATFORMS` and `window.currentPlatform()`. Users
// add their own on top of these from the options page (stored as `customSites`).
//
// To add one: add an entry below, then mirror its `matches` into
// `content_scripts[0].matches` and `host_permissions` in manifest.json. Hand synced;
// the manifest is plain JSON so it can't hold a comment pointing back here. Anything
// niche belongs in a user's custom list.
//
// `id` is the stable key for per platform on/off in `enabledSites`; `name` is what
// shows on the block screen.

(function () {
  const PLATFORMS = [
    { id: 'youtube',   name: 'YouTube',   hosts: ['youtube.com'],   matches: ['*://*.youtube.com/*'] },
    { id: 'reddit',    name: 'Reddit',    hosts: ['reddit.com'],    matches: ['*://*.reddit.com/*'] },
    { id: 'instagram', name: 'Instagram', hosts: ['instagram.com'], matches: ['*://*.instagram.com/*'] },
    { id: 'tiktok',    name: 'TikTok',    hosts: ['tiktok.com'],    matches: ['*://*.tiktok.com/*'] },
    { id: 'x',         name: 'X',         hosts: ['x.com', 'twitter.com'], matches: ['*://*.x.com/*', '*://*.twitter.com/*'] },
    { id: 'facebook',  name: 'Facebook',  hosts: ['facebook.com'],  matches: ['*://*.facebook.com/*'] },
    { id: 'twitch',    name: 'Twitch',    hosts: ['twitch.tv'],     matches: ['*://*.twitch.tv/*'] },
    { id: 'linkedin',  name: 'LinkedIn',  hosts: ['linkedin.com'],  matches: ['*://*.linkedin.com/*'] }
  ];

  // Suffix match, so www./m./old. subdomains resolve to the same platform.
  function currentPlatform() {
    const host = location.hostname;
    return PLATFORMS.find((p) =>
      p.hosts.some((h) => host === h || host.endsWith('.' + h))) || null;
  }

  globalThis.TRACKED_PLATFORMS = PLATFORMS;
  globalThis.currentPlatform = currentPlatform;
})();
