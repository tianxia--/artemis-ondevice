package com.artemis.ondevice;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The price of a model: the one the user entered for the service wins; otherwise the bundled
 * table ({@code assets/prices.json}, from LiteLLM's model price list, refreshed by
 * scripts/update-prices.py) is matched by model id. Self-hosted models are not in the table and
 * need a price entered by hand.
 */
final class Pricing {

    static final class Price {
        final double in, out;
        final String currency;
        /** True when it came from the bundled table, not from the user. */
        final boolean fromTable;

        Price(double in, double out, String currency, boolean fromTable) {
            this.in = in;
            this.out = out;
            this.currency = currency;
            this.fromTable = fromTable;
        }

        double cost(long tokensIn, long tokensOut) {
            return (tokensIn * in + tokensOut * out) / 1_000_000d;
        }
    }

    private static volatile Map<String, double[]> table;
    /** Which provider's price the table kept for a model (e.g. "openai", or a reseller like "together_ai"). */
    private static final Map<String, String> tableProvider = new HashMap<>();
    private static volatile String tableDate = "";

    private Pricing() {}

    /** The price for this model of this service, or null when none is known. */
    static Price of(Context ctx, ProviderStore.Provider p, String model) {
        if (p != null && p.hasPrice()) return new Price(p.priceIn, p.priceOut, p.currency, false);
        double[] t = lookup(ctx, model);
        return t == null ? null : new Price(t[0], t[1], "$", true);
    }

    /** Table price [in, out] per million tokens for a model id, or null. */
    static double[] lookup(Context ctx, String model) {
        if (model == null || model.isEmpty()) return null;
        Map<String, double[]> t = load(ctx);
        String id = model.toLowerCase(Locale.ROOT).trim();
        double[] hit = t.get(id);
        if (hit == null && id.contains("/")) hit = t.get(id.substring(id.lastIndexOf('/') + 1));
        return hit;
    }

    /** The provider whose price the table has for this model, or "". */
    static String tableSource(Context ctx, String model) {
        if (lookup(ctx, model) == null) return "";
        String id = model.toLowerCase(Locale.ROOT).trim();
        String p = tableProvider.get(id);
        if (p == null && id.contains("/")) p = tableProvider.get(id.substring(id.lastIndexOf('/') + 1));
        return p == null ? "" : p;
    }

    /** Date the bundled table was fetched, for display. */
    static String tableDate(Context ctx) {
        load(ctx);
        return tableDate;
    }

    private static Map<String, double[]> load(Context ctx) {
        Map<String, double[]> t = table;
        if (t != null) return t;
        synchronized (Pricing.class) {
            if (table != null) return table;
            Map<String, double[]> m = new HashMap<>();
            try (InputStream in = ctx.getAssets().open("prices.json")) {
                JSONObject o = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
                tableDate = o.optString("fetched");
                JSONObject models = o.getJSONObject("models");
                for (java.util.Iterator<String> it = models.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONArray v = models.getJSONArray(k);
                    m.put(k, new double[]{v.getDouble(0), v.getDouble(1)});
                    tableProvider.put(k, v.optString(2));
                }
            } catch (Exception e) {
                Log.w(AgentService.TAG, "price table not readable: " + e.getClass().getSimpleName());
            }
            table = m;
            return m;
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** Cost of usage rows: summed when every priced row shares a currency. */
    static final class Total {
        double cost;
        String currency;
        boolean priced, unpriced, mixed, fromTable;

        /** "$0.012", with "+" when some rows had no price; "" when nothing was priced or currencies differ. */
        String text() {
            return !priced || mixed ? "" : money(currency, cost) + (unpriced ? "+" : "");
        }
    }

    static Total total(Context ctx, Iterable<UsageStore.Row> rows) {
        Map<String, ProviderStore.Provider> providers = new HashMap<>();
        for (ProviderStore.Provider p : ProviderStore.list(ctx)) providers.put(p.id, p);
        Total t = new Total();
        for (UsageStore.Row r : rows) {
            Price price = of(ctx, providers.get(r.provider), r.model);
            if (price == null) {
                if (r.tokens() > 0) t.unpriced = true;
                continue;
            }
            if (t.currency != null && !t.currency.equals(price.currency)) t.mixed = true;
            t.currency = price.currency;
            t.cost += price.cost(r.in, r.out);
            t.priced = true;
            t.fromTable |= price.fromTable;
        }
        return t;
    }

    /** "$0.012" style. */
    static String money(String currency, double c) {
        return currency + (c < 0.01 ? String.format(Locale.US, "%.4f", c)
                : c < 10 ? String.format(Locale.US, "%.3f", c) : String.format(Locale.US, "%.2f", c));
    }
}
