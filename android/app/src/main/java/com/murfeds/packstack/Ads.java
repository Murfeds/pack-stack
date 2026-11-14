package com.murfeds.packstack;

import android.os.Handler;
import android.os.Looper;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real ads (Google AdMob). One rewarded ad and one break ad are kept loaded in the background so they open at once.
 *  Nothing is loaded until Google's consent check says ads may be requested (it shows its own screen only where the law needs one).
 *  Every show() answers the game exactly once: { shown, earned }. All methods run on the main thread. */
final class Ads {
    private final MainActivity act;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean started = new AtomicBoolean(false);
    private ConsentInformation consent;
    private RewardedAd rewarded;
    private InterstitialAd inter;
    private boolean loadingR, loadingI, ready;
    private int failsR, failsI;

    Ads(MainActivity act) { this.act = act; }

    /** Ask Google whether a consent choice is needed here, show it if so, then start the ad SDK. */
    void start() {
        consent = UserMessagingPlatform.getConsentInformation(act);
        ConsentRequestParameters params = new ConsentRequestParameters.Builder().build();
        try {
            consent.requestConsentInfoUpdate(act, params,
                    () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(act, formError -> { if (consent.canRequestAds()) startSdk(); }),
                    requestError -> { if (consent.canRequestAds()) startSdk(); });
        } catch (RuntimeException ignored) { }
        if (consent.canRequestAds()) startSdk();   // a choice made on an earlier launch still counts
    }

    private void startSdk() {
        if (started.getAndSet(true)) return;
        new Thread(() -> {
            try { MobileAds.initialize(act, status -> main.post(() -> { ready = true; loadRewarded(); loadInter(); })); }
            catch (RuntimeException ignored) { }
        }).start();
    }

    private long backoff(int fails) { return Math.min(300000L, 15000L << Math.min(fails, 5)); }   // 30 s, 1 min, 2 min ... at most 5 min

    private void loadRewarded() {
        if (!ready || rewarded != null || loadingR) return;
        loadingR = true;
        RewardedAd.load(act, BuildConfig.AD_REWARDED, new AdRequest.Builder().build(), new RewardedAdLoadCallback() {
            @Override public void onAdLoaded(RewardedAd ad) { loadingR = false; failsR = 0; rewarded = ad; }
            @Override public void onAdFailedToLoad(LoadAdError e) { loadingR = false; rewarded = null; failsR++; main.postDelayed(Ads.this::loadRewarded, backoff(failsR)); }
        });
    }

    private void loadInter() {
        if (!ready || inter != null || loadingI) return;
        loadingI = true;
        InterstitialAd.load(act, BuildConfig.AD_INTERSTITIAL, new AdRequest.Builder().build(), new InterstitialAdLoadCallback() {
            @Override public void onAdLoaded(InterstitialAd ad) { loadingI = false; failsI = 0; inter = ad; }
            @Override public void onAdFailedToLoad(LoadAdError e) { loadingI = false; inter = null; failsI++; main.postDelayed(Ads.this::loadInter, backoff(failsI)); }
        });
    }

    private static String result(boolean shown, boolean earned) { return "{\"shown\":" + shown + ",\"earned\":" + earned + "}"; }

    /** Show an ad now. kind is "rewarded" or "inter". If none is loaded the game is told so at once (and one is fetched for next time). */
    void show(final String id, String kind) {
        final AtomicBoolean answered = new AtomicBoolean(false);
        final boolean[] earned = { false };
        if ("rewarded".equals(kind)) {
            final RewardedAd ad = rewarded;
            if (ad == null) { loadRewarded(); act.reply(id, true, result(false, false)); return; }
            rewarded = null;
            ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override public void onAdDismissedFullScreenContent() { if (!answered.getAndSet(true)) act.reply(id, true, result(true, earned[0])); loadRewarded(); }
                @Override public void onAdFailedToShowFullScreenContent(AdError e) { if (!answered.getAndSet(true)) act.reply(id, true, result(false, false)); loadRewarded(); }
            });
            ad.show(act, reward -> earned[0] = true);
        } else {
            final InterstitialAd ad = inter;
            if (ad == null) { loadInter(); act.reply(id, true, result(false, false)); return; }
            inter = null;
            ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override public void onAdDismissedFullScreenContent() { if (!answered.getAndSet(true)) act.reply(id, true, result(true, false)); loadInter(); }
                @Override public void onAdFailedToShowFullScreenContent(AdError e) { if (!answered.getAndSet(true)) act.reply(id, true, result(false, false)); loadInter(); }
            });
            ad.show(act);
        }
    }

    /** Settings > Privacy > Ad privacy choices: reopen Google's consent screen where one applies. */
    void privacy(final String id) {
        if (consent != null && consent.getPrivacyOptionsRequirementStatus() == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED)
            UserMessagingPlatform.showPrivacyOptionsForm(act, formError -> act.reply(id, true, "{\"shown\":true}"));
        else act.reply(id, true, "{\"shown\":false}");
    }
}
