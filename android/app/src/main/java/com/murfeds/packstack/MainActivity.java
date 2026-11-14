package com.murfeds.packstack;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedDispatcher;
import androidx.webkit.WebViewAssetLoader;
import org.json.JSONObject;

/** The whole game is one web page bundled inside the app. This screen shows it, keeps it clear of the camera notch and the gesture bar,
 *  sends the phone's Back button to the game, and tells the game when the app is put away so it saves and goes quiet.
 *  It also gives the page one object, PackStackNative, through which the game asks for real ads (Ads) and real purchases (Billing). */
public class MainActivity extends Activity {
    private static final String HOME = "appassets.androidplatform.net";
    private WebView web;
    private Ads ads;
    private Billing billing;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B1220);
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);   // the game's page keeps top priority, even next to a hidden ad page
        // Keep the game inside the safe area. Android 15+ draws every app edge to edge, under the status bar, the camera hole
        // and the gesture bar. A WebView ignores its own padding, so the game sits inside a frame and the FRAME gets the padding:
        // the game is pushed clear of the status bar / camera at the top and the gesture bar at the bottom, and the strips
        // left over are painted in the game's background colour.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B1220);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets b = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(b.left, b.top, b.right, b.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            // the frame has used the insets up: the game page inside must not add the same space a second time
            return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
        });

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }
            // the game page never leaves the app: any other address opens in the phone's browser instead
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (HOME.equals(req.getUrl().getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl())); } catch (RuntimeException ignored) { }
                return true;
            }
        });
        ads = new Ads(this);
        billing = new Billing(this);
        web.addJavascriptInterface(new Bridge(), "PackStackNative");
        // Android 13+ (and required on Android 16): the Back gesture comes through this callback instead of onBackPressed
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
        if (saved != null) web.restoreState(saved);
        else web.loadUrl("https://" + HOME + "/assets/index.html");
        ads.start();
    }

    /** What the page can call. These arrive on a background thread, so every job is passed to the main thread first. */
    private final class Bridge {
        @JavascriptInterface public String info() { return "{\"app\":\"android\",\"ads\":true,\"store\":true,\"build\":\"" + BuildConfig.VERSION_CODE + "\"}"; }
        @JavascriptInterface public void call(final String id, final String op, final String arg) {
            runOnUiThread(() -> {
                try {
                    if ("ads.show".equals(op)) ads.show(id, arg);
                    else if ("ads.privacy".equals(op)) ads.privacy(id);
                    else if ("ads.prefetch".equals(op)) { ads.prefetch(); reply(id, true, "{}"); }
                    else if ("ads.hold".equals(op)) { ads.hold(); reply(id, true, "{}"); }
                    else if ("store.details".equals(op)) billing.details(id, arg);
                    else if ("store.buy".equals(op)) billing.buy(id, arg);
                    else if ("store.list".equals(op)) billing.list(id);
                    else if ("store.consume".equals(op)) billing.consume(id, arg);
                    else reply(id, false, "{\"name\":\"Error\",\"message\":\"unknown job\"}");
                } catch (RuntimeException e) { reply(id, false, "{\"name\":\"Error\",\"message\":\"the app could not do that\"}"); }
            });
        }
    }

    /** Answer one job the page started (see src/native.js in the game). Safe to call from any thread. */
    void reply(final String id, final boolean ok, final String json) {
        runOnUiThread(() -> web.evaluateJavascript("window.__psNative&&__psNative.done(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(json) + ")", null));
    }

    /** A purchase finished outside the normal flow: the game asks Google Play for the list again and hands it over. */
    void storeChanged() {
        runOnUiThread(() -> web.evaluateJavascript("try{window.dispatchEvent(new Event('packstackstore'))}catch(e){}", null));
    }

    @Override protected void onPause() { web.evaluateJavascript("try{if(window.__appPause)__appPause()}catch(e){}", null); super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); web.evaluateJavascript("try{if(window.__appResume)__appResume()}catch(e){}", null); }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

    /** Back closes the open page or pop-up, or goes from a level back to the map. Only on the Home screen does it leave the app. */
    private void goBack() {
        web.evaluateJavascript("(function(){try{return window.__appBack?__appBack():'exit'}catch(e){return 'exit'}})()",
                v -> { if (v == null || v.contains("exit")) moveTaskToBack(true); });
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { goBack(); }
}
