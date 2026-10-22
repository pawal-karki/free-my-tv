package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NEPSE (Nepal Stock Exchange) for the drawer card: the index and the day's top gainers and losers, read from the
 * pages Merolagani serves to anyone (no login, no key; certificates checked as usual). Display only. There is no free
 * official feed, so this is page reading: if the site changes its pages the card says "No data" until it is adapted.
 */
final class Nepse {
    private Nepse() {}

    static final class Quote {
        final String symbol, name;
        final double ltp, pct, high, low, qty;

        Quote(String symbol, String name, double ltp, double pct, double high, double low, double qty) {
            this.symbol = symbol;
            this.name = name;
            this.ltp = ltp;
            this.pct = pct;
            this.high = high;
            this.low = low;
            this.qty = qty;
        }
    }

    static final class Data {
        double index, change, pct;
        String indexDate = "", asOf = "";
        final List<Quote> gainers = new ArrayList<>(), losers = new ArrayList<>(), all = new ArrayList<>();
    }

    private static final String BASE = "https://merolagani.com/";
    private static final long EVERY_MS = 10 * 60_000L, RETRY_MS = 3 * 60_000L;
    private static final Pattern INDEX = Pattern.compile(
            "<td class=\"text-center\">(\\d{4}/\\d\\d/\\d\\d)</td>\\s*<td class=\"text-right\">([\\d,.]+)</td>\\s*"
                    + "<td class=\"text-right\">(-?[\\d,.]+)</td>\\s*<td class=\"text-right\">(-?[\\d.]+)%</td>");
    private static final Pattern ROW = Pattern.compile("<tr class='[a-z]+-row'>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("title='[^'(]*\\((.*?)\\)'");
    private static final Pattern AS_OF = Pattern.compile("As of (\\d{4}/\\d\\d/\\d\\d \\d\\d:\\d\\d)");

    private static volatile Data data;
    private static volatile boolean loading;
    private static volatile long at, failedAt;

    private static Context app;

    /** Once, at start: keeps the Context for notices and loads the data saved last time. */
    static void init(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        if (data == null) {
            try {
                data = parseCache(Prefs.nepseCache());
            } catch (RuntimeException ignored) {
            }
        }
    }

    static Data data() {
        return data;
    }

    static Quote quote(String symbol) {
        Data d = data;
        if (d != null) for (Quote q : d.all) if (q.symbol.equals(symbol)) return q;
        return null;
    }

    // --- Watchlist, holdings and alerts (all on the TV: nothing leaves it)

    static final class Holding {
        final String symbol;
        double units, buy, above, below;

        Holding(String symbol) {
            this.symbol = symbol;
        }
    }

    static List<Holding> watch() {
        List<Holding> out = new ArrayList<>();
        for (String part : Prefs.nepseWatch().split(";")) {
            String[] f = part.split(",");
            if (f.length < 1 || f[0].isEmpty()) continue;
            Holding h = new Holding(f[0]);
            if (f.length > 4) {
                h.units = num(f[1]);
                h.buy = num(f[2]);
                h.above = num(f[3]);
                h.below = num(f[4]);
            }
            out.add(h);
        }
        return out;
    }

    static Holding holding(String symbol) {
        for (Holding h : watch()) if (h.symbol.equals(symbol)) return h;
        return null;
    }

    private static void saveWatch(List<Holding> list) {
        StringBuilder sb = new StringBuilder();
        for (Holding h : list) {
            sb.append(sb.length() == 0 ? "" : ";").append(h.symbol).append(',').append(h.units).append(',').append(h.buy)
                    .append(',').append(h.above).append(',').append(h.below);
        }
        Prefs.setNepseWatch(sb.toString());
    }

    static void save(Holding h) {
        List<Holding> list = watch();
        for (int i = 0; i < list.size(); i++) if (list.get(i).symbol.equals(h.symbol)) { list.set(i, h); saveWatch(list); return; }
        list.add(h);
        saveWatch(list);
    }

    static void unwatch(String symbol) {
        List<Holding> list = watch();
        for (int i = 0; i < list.size(); i++) if (list.get(i).symbol.equals(symbol)) { list.remove(i); break; }
        saveWatch(list);
    }

    static boolean watched(String symbol) {
        return holding(symbol) != null;
    }

    /** Fires each alert once per direction and day: the price is at or past what was set. */
    private static final java.util.Set<String> fired = new java.util.HashSet<>();

    private static void checkAlerts(Data d) {
        if (app == null) return;
        for (Holding h : watch()) {
            Quote q = null;
            for (Quote x : d.all) if (x.symbol.equals(h.symbol)) { q = x; break; }
            if (q == null) continue;
            if (h.above > 0 && q.ltp >= h.above && fired.add(h.symbol + ">" + d.indexDate)) {
                HomeMode.notice(app, "NEPSE alert: " + h.symbol + " is " + String.format(java.util.Locale.US, "%,.2f", q.ltp) + " (above " + String.format(java.util.Locale.US, "%,.2f", h.above) + ")");
            }
            if (h.below > 0 && q.ltp <= h.below && fired.add(h.symbol + "<" + d.indexDate)) {
                HomeMode.notice(app, "NEPSE alert: " + h.symbol + " is " + String.format(java.util.Locale.US, "%,.2f", q.ltp) + " (below " + String.format(java.util.Locale.US, "%,.2f", h.below) + ")");
            }
        }
    }

    /** True while the exchange trades (Sunday to Thursday, about 11:00 to 15:00 Nepal time, with a margin). */
    static boolean tradingHours() {
        java.time.ZonedDateTime n = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kathmandu"));
        int day = n.getDayOfWeek().getValue();   // Monday 1 .. Sunday 7
        if (day == 5 || day == 6) return false;
        java.time.LocalTime t = n.toLocalTime();
        return t.isAfter(java.time.LocalTime.of(10, 45)) && t.isBefore(java.time.LocalTime.of(15, 10));
    }

    /** "Live · 14:35", "Closed · holiday: Bijaya Dashami", "Closed · weekend" or "Closed · last trading day 2026/10/16". */
    static String status(Context c, Data d) {
        if (tradingHours() && d.asOf.startsWith(java.time.LocalDate.now().toString().replace('-', '/'))) {
            return "Live · " + d.asOf.substring(Math.min(11, d.asOf.length()));
        }
        java.time.ZonedDateTime n = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kathmandu"));
        int day = n.getDayOfWeek().getValue();
        if (day == 5 || day == 6) return "Closed · weekend (last trading day " + d.indexDate + ")";
        Patro.Month m = Patro.find(c, n.toLocalDate());
        if (m != null) {
            int bs = (int) (n.toLocalDate().toEpochDay() - m.start.toEpochDay()) + 1;
            if (m.holiday[bs]) {
                String f = m.fest[bs].isEmpty() ? "public holiday" : m.fest[bs].split(";")[0];
                return "Closed · holiday: " + f + " (last trading day " + d.indexDate + ")";
            }
        }
        return "Closed · last trading day " + d.indexDate;
    }

    /** Daily closes of a stock (or "NEPSE" for the index) over the last `days` days: {times in seconds, closes}. */
    static double[][] history(String symbol, int days) throws IOException {
        long now = System.currentTimeMillis() / 1000;
        try {
            JSONObject o = new JSONObject(get("handlers/TechnicalChartHandler.ashx?type=get_advanced_chart&symbol=" + symbol
                    + "&resolution=1D&rangeStartDate=" + (now - days * 86400L) + "&rangeEndDate=" + (now + 86400)
                    + "&from=&isAdjust=1&currencyCode=NPR"));
            JSONArray t = o.getJSONArray("t"), cl = o.getJSONArray("c");
            double[][] out = new double[2][t.length()];
            for (int i = 0; i < t.length(); i++) {
                out[0][i] = t.getDouble(i);
                out[1][i] = cl.getDouble(i);
            }
            return out;
        } catch (org.json.JSONException e) {
            throw new IOException("no history");
        }
    }

    // --- Saved copy of the last data

    private static String serialize(Data d) {
        StringBuilder sb = new StringBuilder();
        sb.append("I|").append(d.index).append('|').append(d.change).append('|').append(d.pct).append('|').append(d.indexDate).append('|').append(d.asOf).append('\n');
        for (Quote q : d.all) {
            sb.append("Q|").append(q.symbol).append('|').append(q.name.replace('|', ' ')).append('|').append(q.ltp).append('|').append(q.pct)
                    .append('|').append(q.high).append('|').append(q.low).append('|').append(q.qty).append('\n');
        }
        return sb.toString();
    }

    private static Data parseCache(String text) {
        if (text.isEmpty()) return null;
        Data d = new Data();
        List<Quote> active = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] f = line.split("\\|", -1);
            if (f[0].equals("I") && f.length >= 6) {
                d.index = num(f[1]);
                d.change = num(f[2]);
                d.pct = num(f[3]);
                d.indexDate = f[4];
                d.asOf = f[5];
            } else if (f[0].equals("Q") && f.length >= 8) {
                Quote q = new Quote(f[1], f[2], num(f[3]), num(f[4]), num(f[5]), num(f[6]), num(f[7]));
                d.all.add(q);
                if (q.qty > 0) active.add(q);
            }
        }
        if (d.all.isEmpty() || d.index == 0) return null;
        rank(d, active);
        return d;
    }

    private static void rank(Data d, List<Quote> active) {
        Collections.sort(active, (x, y) -> Double.compare(y.pct, x.pct));
        for (int i = 0; i < 3 && i < active.size(); i++) if (active.get(i).pct > 0) d.gainers.add(active.get(i));
        for (int i = active.size() - 1; i >= Math.max(0, active.size() - 3); i--) if (active.get(i).pct < 0) d.losers.add(active.get(i));
    }

    /** Fetches in the background if the last data is older than ten minutes (a failure waits three before the next try). */
    static void refresh() {
        refresh(EVERY_MS);
    }

    /** Same, with the age (ms) after which the data counts as old: the full screen asks for fresher data. */
    static void refresh(long everyMs) {
        long now = System.currentTimeMillis();
        if (loading || (data != null && now - at < everyMs) || now - failedAt < RETRY_MS) return;
        loading = true;
        new Thread(() -> {
            try {
                Data fresh = fetch();
                data = fresh;
                at = System.currentTimeMillis();
                Prefs.setNepseCache(serialize(fresh));
                checkAlerts(fresh);
            } catch (IOException | RuntimeException e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("nepse: " + e.getMessage());
            } finally {
                loading = false;
            }
        }, "nepse").start();
    }

    private static Data fetch() throws IOException {
        Data d = new Data();
        Matcher m = INDEX.matcher(get("Indices.aspx"));
        if (!m.find()) throw new IOException("index not found");
        d.indexDate = m.group(1);
        d.index = num(m.group(2));
        d.change = num(m.group(3));
        d.pct = num(m.group(4));
        String page = get("LatestMarket.aspx");
        Matcher a = AS_OF.matcher(page);
        if (a.find()) d.asOf = a.group(1);
        List<Quote> all = new ArrayList<>();
        Matcher rows = ROW.matcher(page);
        while (rows.find()) {
            List<String> cells = new ArrayList<>();
            Matcher c = CELL.matcher(rows.group(1));
            String name = "";
            while (c.find()) {
                if (cells.isEmpty()) {
                    Matcher t = TITLE.matcher(c.group(1));
                    if (t.find()) name = t.group(1);
                }
                cells.add(c.group(1).replaceAll("<[^>]+>", "").trim());
            }
            if (cells.size() < 7) continue;
            Quote q = new Quote(cells.get(0), name, num(cells.get(1)), num(cells.get(2)), num(cells.get(3)), num(cells.get(4)), num(cells.get(6)));
            d.all.add(q);
            if (q.qty > 0) all.add(q);
        }
        if (all.isEmpty()) throw new IOException("no stock rows");
        rank(d, all);
        return d;
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String get(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) FreeMyTV");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
