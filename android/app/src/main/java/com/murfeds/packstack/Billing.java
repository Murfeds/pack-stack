package com.murfeds.packstack;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real purchases (Google Play Billing). The game asks for four things: what is on sale (details), take a payment (buy),
 *  what was paid for but not yet handed over (list), and mark one as handed over (consume). Everything sold is a one-time
 *  product that is used up (packs, the Continue). Every job answers the game exactly once. */
final class Billing implements PurchasesUpdatedListener {
    private static final int OK = BillingClient.BillingResponseCode.OK;
    private final MainActivity act;
    private final BillingClient client;
    private final Map<String, ProductDetails> known = new ConcurrentHashMap<>();
    private final List<Runnable[]> waiting = new ArrayList<>();   // jobs queued while the connection to Google Play is being made: { run, fail }
    private boolean connecting;
    private String buyCall, buyProduct;                            // the purchase the player is in the middle of, if any

    Billing(MainActivity act) {
        this.act = act;
        client = BillingClient.newBuilder(act).setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection().build();
    }

    private void fail(String id, String name, String message) {
        try { act.reply(id, false, new JSONObject().put("name", name).put("message", message).toString()); }
        catch (Exception e) { act.reply(id, false, "{\"name\":\"Error\"}"); }
    }

    /** Run a job once the connection to Google Play is up. Called on the main thread only. */
    private void connected(final String id, Runnable job) {
        if (client.isReady()) { job.run(); return; }
        waiting.add(new Runnable[] { job, () -> fail(id, "Error", "Google Play is not available") });
        if (connecting) return;
        connecting = true;
        client.startConnection(new BillingClientStateListener() {
            @Override public void onBillingSetupFinished(BillingResult r) {
                act.runOnUiThread(() -> {
                    connecting = false;
                    List<Runnable[]> jobs = new ArrayList<>(waiting); waiting.clear();
                    for (Runnable[] j : jobs) j[r.getResponseCode() == OK ? 0 : 1].run();
                });
            }
            @Override public void onBillingServiceDisconnected() { }
        });
    }

    private static JSONObject describe(Purchase p) throws Exception {
        return new JSONObject().put("itemId", p.getProducts().isEmpty() ? "" : p.getProducts().get(0)).put("purchaseToken", p.getPurchaseToken())
                .put("purchaseState", p.getPurchaseState() == Purchase.PurchaseState.PURCHASED ? "purchased" : "pending");
    }

    /** What is on sale here, with Google Play's own price text for this player's country. */
    void details(final String id, final String idsJson) {
        connected(id, () -> {
            try {
                JSONArray ids = new JSONArray(idsJson);
                List<QueryProductDetailsParams.Product> list = new ArrayList<>();
                for (int i = 0; i < ids.length(); i++)
                    list.add(QueryProductDetailsParams.Product.newBuilder().setProductId(ids.getString(i)).setProductType(BillingClient.ProductType.INAPP).build());
                if (list.isEmpty()) { act.reply(id, true, "[]"); return; }
                client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(list).build(), (r, result) -> {
                    if (r.getResponseCode() != OK) { fail(id, "Error", "Google Play could not list the shop (" + r.getResponseCode() + ")"); return; }
                    try {
                        JSONArray out = new JSONArray();
                        for (ProductDetails d : result.getProductDetailsList()) {
                            ProductDetails.OneTimePurchaseOfferDetails o = d.getOneTimePurchaseOfferDetails();
                            if (o == null) continue;
                            known.put(d.getProductId(), d);
                            out.put(new JSONObject().put("itemId", d.getProductId()).put("priceText", o.getFormattedPrice())
                                    .put("price", new JSONObject().put("currency", o.getPriceCurrencyCode()).put("value", String.valueOf(o.getPriceAmountMicros() / 1000000.0))));
                        }
                        act.reply(id, true, out.toString());
                    } catch (Exception e) { fail(id, "Error", "could not read the shop list"); }
                });
            } catch (Exception e) { fail(id, "Error", "bad product list"); }
        });
    }

    /** Open Google Play's payment sheet for one product. The answer comes later, in onPurchasesUpdated. */
    void buy(final String id, final String product) {
        connected(id, () -> {
            ProductDetails d = known.get(product);
            if (d == null) { fail(id, "Error", "that product is not on sale"); return; }
            if (buyCall != null) { fail(id, "Error", "another purchase is still open"); return; }
            BillingFlowParams flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(
                    Collections.singletonList(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build())).build();
            buyCall = id; buyProduct = product;
            BillingResult r = client.launchBillingFlow(act, flow);
            if (r.getResponseCode() != OK) { buyCall = null; buyProduct = null; answerNotBought(id, product, r.getResponseCode()); }
        });
    }

    private void answerNotBought(final String id, final String product, int code) {
        if (code == BillingClient.BillingResponseCode.USER_CANCELED) { fail(id, "AbortError", "the player backed out"); return; }
        if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            // an earlier payment for this product was never handed over: hand that one over now instead of charging again
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(), (r, list) -> {
                try {
                    if (r.getResponseCode() == OK) for (Purchase p : list) if (p.getProducts().contains(product)) { act.reply(id, true, describe(p).toString()); return; }
                } catch (Exception ignored) { }
                fail(id, "Error", "already owned");
            });
            return;
        }
        fail(id, "Error", "Google Play said no (" + code + ")");
    }

    @Override public void onPurchasesUpdated(final BillingResult r, final List<Purchase> list) {
        act.runOnUiThread(() -> {
            final String id = buyCall, product = buyProduct; buyCall = null; buyProduct = null;
            if (id == null) { act.storeChanged(); return; }   // a payment that finished later (a slow card, cash at a shop): the game asks for the list again
            if (r.getResponseCode() == OK && list != null) {
                try { for (Purchase p : list) if (p.getProducts().contains(product)) { act.reply(id, true, describe(p).toString()); return; } } catch (Exception ignored) { }
                fail(id, "Error", "no purchase came back");
            } else answerNotBought(id, product, r.getResponseCode());
        });
    }

    /** Everything paid for and not yet used up. */
    void list(final String id) {
        connected(id, () -> client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(), (r, list) -> {
            if (r.getResponseCode() != OK) { fail(id, "Error", "Google Play could not list purchases (" + r.getResponseCode() + ")"); return; }
            try { JSONArray out = new JSONArray(); for (Purchase p : list) out.put(describe(p)); act.reply(id, true, out.toString()); }
            catch (Exception e) { fail(id, "Error", "could not read purchases"); }
        }));
    }

    /** Mark a purchase as handed over, so the same product can be bought again. */
    void consume(final String id, final String token) {
        connected(id, () -> client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(token).build(), (r, t) -> {
            if (r.getResponseCode() == OK) act.reply(id, true, "{}"); else fail(id, "Error", "could not finish the purchase (" + r.getResponseCode() + ")");
        }));
    }
}
