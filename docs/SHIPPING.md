# Shipping Pack Stack as a real app

This is the short version, in plain words. The game is one web page. A "wrapper" puts that page inside a real Android or iPhone app, so it can go in the stores, show real ads and take real payments.

## What is in this folder

| File | What it is for |
|---|---|
| `index.html` | The whole game, as a stand-alone page. This is what the app shows. |
| `sw.js` | The offline helper (service worker). Keeps the game on the phone so it opens with no internet. |
| `manifest.webmanifest` | Tells phones the app's name, icon, colours and "portrait only". |
| `live.json` | The live config. Change events, offers, shop, seasons here without rebuilding. Raise `version` each time. |
| `icon-192.png`, `icon-512.png`, `icon-maskable-512.png`, `apple-touch-icon.png`, `favicon-32.png` | App icons. |
| `launch-*.png` | iPhone launch screens for the home-screen web app. |
| `icon-source-1024.png`, `splash-source-2732.png` | Big source pictures. The Capacitor tool makes every store size from these two. |

The name and logo live in ONE place: `src/config.js` (`name`, `logo`, `tagline`, `version`, `theme`, `contact`). Change them there, rebuild, and the splash, home sign, About page, manifest and icons' labels all follow.

## Step 1: try it as a home-screen web app (no store needed)

1. Put this folder on any web host that uses `https` (GitHub Pages, Netlify, your own site).
2. Open the address on a phone. In Chrome: menu, **Add to Home screen**. In Safari: Share, **Add to Home Screen**.
3. It now opens full screen with the icon and launch screen, and works offline after the first visit.

## Step 2: wrap it with Capacitor (the real app)

You need a computer with Node.js. For Android you need Android Studio. For iPhone you need a Mac with Xcode.

```
npm init -y
npm install @capacitor/core @capacitor/cli @capacitor/android @capacitor/ios
npx cap init "Pack Stack" com.yourname.packstack --web-dir pub
npx cap add android
npx cap add ios
npm install --save-dev @capacitor/assets
npx capacitor-assets generate --iconBackgroundColor "#0B1220" --splashBackgroundColor "#0B1220"
npx cap sync
npx cap open android     (or: npx cap open ios)
```

Before `capacitor-assets generate`, copy `icon-source-1024.png` to `assets/icon.png` and `splash-source-2732.png` to `assets/splash.png`.

In `capacitor.config.json`, lock the screen upright: on Android set `android:screenOrientation="portrait"` in `AndroidManifest.xml`; on iPhone tick only **Portrait** in Xcode.

## Step 3: real ads

Every "watch an ad" button in the game goes through one module: **`AdService`** (in `src/hub3.js`).

- Today `AdService.play(rewarded)` shows a 3-second stand-in screen and returns a promise.
- To use a real SDK (for example AdMob through `@capacitor-community/admob`), replace ONLY the body of `play()`:
  - `rewarded === true`: show a rewarded ad. Resolve `true` if the player earned the reward, `false` if they closed it early.
  - `rewarded === false`: show an interstitial. Resolve when it closes.
- Keep `showRewarded(place)` and `showInterstitial()` as they are. They write the offer log and the play stats.
- Pacing (seconds, how often, from which level) comes from `live.json` under `ads`.
- The master switch is Settings, **Offers and ad placeholders**. Keep it.

## Step 4: real purchases

All payments go through one module: **`Shop`** (in `src/ui-b.js`), which talks to a "store" object with four jobs:

- `getDetails(ids)`: what is on sale and for how much
- `purchase(id)`: take the payment, return a purchase token
- `listPurchases()`: things paid for but not yet handed over
- `consume(token)`: mark one as handed over

On Android, supply that object as `window.PackStackStore` from the app (Google Play Billing). On iPhone, write the same four functions on top of StoreKit (for example with `@capgo/capacitor-purchases` or RevenueCat). The game already handles failed payments, backing out, and packs that were paid for but not yet delivered.

- Product ids and pack sizes come from `live.json` under `shop.packs`. They must match the ids you create in the stores.
- **The Endless Continue** (new in 1.5) is one more consumable product: id `continue_1`, shown as $1.99 until the store gives its own price. After a lost Endless run it clears the board (jammer blocks stay), keeps the score, and works once per run. Its id, fallback price and an on/off switch are in `live.json` under `shop.cont` (`{ "id": "continue_1", "usd": 1.99, "on": true }`). Create a consumable in-app product with that id in the Play Console.
  - It goes through the same four functions as the packs: `getDetails` is asked about it together with the packs, `purchase("continue_1")` takes the payment, `consume(token)` is called once it has been handed over.
  - The game only acts on a purchase token the store returned. Each token is remembered (as a fingerprint) and never pays out twice.
  - If the app is closed in the middle of paying, `listPurchases()` hands the purchase back at the next start. It is then used on the lost run if that run is still waiting, or kept in the save for the next lost run ("Continue, already paid for").
  - `purchase()` and `listPurchases()` may add `purchaseState` to what they return. Anything other than `"purchased"` (or `1`, Google's number for it) is treated as not paid: pending, cancelled and refunded purchases hand over nothing. A store that leaves `purchaseState` out must only ever return finished purchases.
  - What the game cannot do by itself: take a Continue back after Google refunds it later. That needs a server that listens to Google's refund notices. Until then a refund after use is simply a free Continue for that player.
  - With no store object (the plain web page, the test APK) the button is shown switched off and nothing can be bought.
- The Store tab's **Restore purchases** button is a placeholder. Wire it to the store's restore call for one-time purchases such as Remove ads.
- The Starter bundle, Remove ads, Robot Pass and Premium pass are designs only. Decide which ones you will really sell.

## Step 5: analytics (optional)

One module: **`Analytics`** (in `src/polish.js`). Every event passes through `Analytics.send(ev)`. Today it keeps events on the phone. To use a real service, call its SDK inside `send`. Events: `level_start`, `level_win`, `level_fail` (with the reason and how close), `ad_offered`, `ad_accepted`, `ad_finished`, `pack_opened`, `screen`, `session_start`, `session_end`. It only runs if the player said yes on the consent screen (Settings, Privacy).

## Checklist: Google Play

- [ ] App icon 512 x 512, feature graphic 1024 x 500
- [ ] At least 2 phone screenshots (home screen, a level, pack opening, album)
- [ ] Content rating questionnaire. Answer honestly about ads and random paid items
- [ ] Data safety form: what is collected (see the "How we use your data" page), whether it is shared, how to delete it
- [ ] Privacy policy web address (host the reviewed version of the in-game page)
- [ ] Declare ads: **Contains ads** = yes once real ads are in
- [ ] In-app products created with the same ids as `live.json`
- [ ] **Loot box odds**: Play requires the odds of random paid items to be shown before purchase. The game shows them above the prices and on the Pack odds page. Keep them true.
- [ ] If children may play: join the Families programme, use child-safe ad settings, no personalised ads

## Checklist: Apple App Store

- [ ] App icon 1024 x 1024 (no transparency), screenshots for 6.7" and 6.5" iPhones
- [ ] Age rating questionnaire (gambling-style mechanics: packs are random; answer accordingly)
- [ ] Privacy "nutrition labels": list every data type the app or its SDKs collect
- [ ] Privacy policy web address
- [ ] App Tracking Transparency prompt if an ad SDK tracks across apps (avoid this if you can)
- [ ] In-app purchases created and reviewed, **Restore purchases** working
- [ ] **Loot box odds** disclosed before purchase (guideline 3.1.1). Already in the game: keep the numbers in sync with the real odds
- [ ] A way to delete the player's data (Settings, Erase everything)

## Before you publish

- [ ] A lawyer or privacy professional has replaced the placeholder Privacy Policy, Terms and data page
- [ ] `contact` in `src/config.js` is a real address
- [ ] You have chosen the final name and checked it is free in both stores and as a trademark
- [ ] Tested on a real small phone and a real large phone, with sound on
