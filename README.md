# Pack Stack

A gem block puzzle with collectible robot cards: 600 levels across 40 worlds, card packs that act as power-ups, achievements, seasons and daily challenges.

**Play / install:** https://murfeds.github.io/pack-stack/

On Android (Chrome): open the link, tap the menu, then **Install app** (or **Add to Home screen**). On iPhone (Safari): Share, then **Add to Home Screen**. It works offline after the first open and shows "New version ready" when there's an update.

## What's in here

- `index.html` – the whole game in one file. `sw.js` keeps it working offline. `manifest.webmanifest` and the icons make it installable.
- `live.json` – events, offers, the shop and seasons. Change it and raise `version`; players get it next time they're online, no new build needed.
- `android/` – the Android app (the game inside a full-screen WebView). Every push to `main` that changes the game builds it on GitHub (see the Actions tab). The test APK and the Play Store bundle land on the `android-builds` branch.
- `music/` – seven lo-fi tracks by HoliznaCC0, public domain (CC0): free to use, no credit required (credited in the game's About page anyway). Source: https://opengameart.org/content/happy-lo-fi-lofi-collection
- `privacy.html` – privacy policy page (needed for the Play Store).
