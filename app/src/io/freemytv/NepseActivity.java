package io.freemytv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The whole NEPSE market on the full screen: every stock with its price and change, a search box, a few sort buttons. */
public class NepseActivity extends Activity {
    private static final int UP = 0xFF30D158, DOWN = 0xFFFF453A, DIM = 0xFF8E8E93, INK = 0xFFF2F2F7;
    private static final String[] SORTS = {"A–Z", "Top gainers", "Top losers", "Volume", "★ Watchlist"};

    private final List<Nepse.Quote> shown = new ArrayList<>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final TextView[] chips = new TextView[SORTS.length];
    private BaseAdapter adapter;
    private TextView indexChip;
    private TextView index, change, status, portfolio;
    private String filter = "";
    private int sort;
    private Nepse.Data seen;
    private float d;

    private int dp(float v) {
        return Math.round(v * d);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        Nepse.init(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0B0D);
        root.setPadding(dp(40), dp(24), dp(40), 0);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("NEPSE", 30, INK, Typeface.BOLD);
        top.addView(title);
        index = text("", 34, INK, Typeface.BOLD);
        index.setPadding(dp(28), 0, dp(16), 0);
        top.addView(index);
        change = text("", 20, UP, Typeface.BOLD);
        top.addView(change);
        status = text("Loading…", 15, DIM, Typeface.NORMAL);
        status.setGravity(Gravity.END);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(top);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(10), 0, dp(6));
        EditText search = new EditText(this);
        search.setHint("Search symbol or company");
        search.setSingleLine(true);
        search.setTextColor(INK);
        search.setHintTextColor(0x99FFFFFF);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int n) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int n) {}
            @Override public void afterTextChanged(Editable s) {
                filter = s.toString().trim().toLowerCase(Locale.ROOT);
                rebuild();
            }
        });
        bar.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        for (int i = 0; i < SORTS.length; i++) {
            final int at = i;
            TextView chip = text(SORTS[i], 16, INK, Typeface.BOLD);
            chip.setPadding(dp(16), dp(8), dp(16), dp(8));
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setOnClickListener(v -> { sort = at; rebuild(); });
            chip.setOnFocusChangeListener((v, f) -> paintChips());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(10);
            bar.addView(chip, lp);
            chips[i] = chip;
        }
        TextView chartChip = text("Index chart", 16, INK, Typeface.BOLD);
        chartChip.setPadding(dp(16), dp(8), dp(16), dp(8));
        chartChip.setFocusable(true);
        chartChip.setClickable(true);
        chartChip.setOnClickListener(v -> startActivity(new android.content.Intent(this, NepseDetailActivity.class).putExtra("symbol", "NEPSE")));
        chartChip.setOnFocusChangeListener((v, f) -> paintChips());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(10);
        bar.addView(chartChip, clp);
        indexChip = chartChip;
        root.addView(bar);
        portfolio = text("", 16, INK, Typeface.NORMAL);
        portfolio.setPadding(dp(10), 0, 0, dp(4));
        root.addView(portfolio);
        root.addView(row("SYMBOL", "COMPANY", "LTP", "CHANGE", "HIGH", "LOW", "VOLUME", DIM, Typeface.BOLD, 13));

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0x33FFFFFF));
        list.setDrawSelectorOnTop(false);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(40));
        adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) {
                Nepse.Quote q = shown.get(i);
                int color = q.pct > 0 ? UP : q.pct < 0 ? DOWN : INK;
                return row((Nepse.watched(q.symbol) ? "★ " : "") + q.symbol, q.name, String.format(Locale.US, "%,.2f", q.ltp), String.format(Locale.US, "%+.2f%%", q.pct),
                        String.format(Locale.US, "%,.2f", q.high), String.format(Locale.US, "%,.2f", q.low),
                        String.format(Locale.US, "%,.0f", q.qty), color, Typeface.NORMAL, 18);
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, i, id) -> startActivity(new android.content.Intent(this, NepseDetailActivity.class).putExtra("symbol", shown.get(i).symbol)));
        list.setOnItemLongClickListener((p, v, i, id) -> {
            String sym = shown.get(i).symbol;
            if (Nepse.watched(sym)) Nepse.unwatch(sym); else Nepse.save(new Nepse.Holding(sym));
            rebuild();
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        search.setOnClickListener(v -> ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(v, 0));
        list.requestFocus();   // start on the list; the keyboard opens when OK is pressed on the search box
        paintChips();
        tick();
    }

    private TextView text(String s, float sp, int color, int style) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }

    private LinearLayout row(String sym, String name, String ltp, String pct, String high, String low, String vol, int color, int style, float sp) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), dp(style == Typeface.BOLD ? 4 : 11), dp(10), dp(style == Typeface.BOLD ? 4 : 11));
        int[] widths = {130, 0, 120, 110, 110, 110, 130};
        String[] cells = {sym, name, ltp, pct, high, low, vol};
        for (int i = 0; i < cells.length; i++) {
            TextView t = text(cells[i], sp, i == 0 ? (style == Typeface.NORMAL ? INK : color) : (i == 3 ? color : (style == Typeface.NORMAL ? INK : color)), i == 0 ? Typeface.BOLD : style);
            if (i == 1) t.setTextColor(style == Typeface.NORMAL ? 0xFF9A9AA0 : color);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setGravity(i >= 2 ? Gravity.END : Gravity.START);
            LinearLayout.LayoutParams lp = widths[i] == 0 ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)
                    : new LinearLayout.LayoutParams(dp(widths[i]), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i == 1) lp.leftMargin = dp(10);
            r.addView(t, lp);
        }
        return r;
    }

    private void paintChips() {
        for (int i = 0; i < chips.length; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(20));
            g.setColor(chips[i].isFocused() ? 0xFFFFFFFF : i == sort ? 0x44FFFFFF : 0x1AFFFFFF);
            chips[i].setBackground(g);
            chips[i].setTextColor(chips[i].isFocused() ? 0xFF111111 : INK);
        }
        if (indexChip != null) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(20));
            g.setColor(indexChip.isFocused() ? 0xFFFFFFFF : 0x1AFFFFFF);
            indexChip.setBackground(g);
            indexChip.setTextColor(indexChip.isFocused() ? 0xFF111111 : INK);
        }
    }

    /** Every second: new data repaints; every 25 seconds the data is asked for again (the market moves while it trades). */
    private void tick() {
        Nepse.refresh(25_000);
        Nepse.Data data = Nepse.data();
        if (data != seen) {
            seen = data;
            if (data != null) {
                index.setText(String.format(Locale.US, "%,.2f", data.index));
                change.setText(String.format(Locale.US, "%s %.2f (%+.2f%%)", data.change >= 0 ? "▲" : "▼", Math.abs(data.change), data.pct));
                change.setTextColor(data.change >= 0 ? UP : DOWN);
                status.setText(Nepse.status(this, data));
            }
            rebuild();
        }
        h.postDelayed(this::tick, 1000);
    }

    private void rebuild() {
        shown.clear();
        Nepse.Data data = Nepse.data();
        if (data != null) {
            for (Nepse.Quote q : data.all) {
                if (sort == 4 && !Nepse.watched(q.symbol)) continue;
                if (filter.isEmpty() || q.symbol.toLowerCase(Locale.ROOT).contains(filter) || q.name.toLowerCase(Locale.ROOT).contains(filter)) shown.add(q);
            }
            if (sort == 1) Collections.sort(shown, (a, b) -> Double.compare(b.pct, a.pct));
            else if (sort == 2) Collections.sort(shown, (a, b) -> Double.compare(a.pct, b.pct));
            else if (sort == 3) Collections.sort(shown, (a, b) -> Double.compare(b.qty, a.qty));
        }
        paintChips();
        adapter.notifyDataSetChanged();
        portfolioLine();
    }

    /** Your holdings in one line: value, profit or loss since you bought, and today's move. Display only. */
    private void portfolioLine() {
        double value = 0, cost = 0, today = 0;
        int n = 0;
        for (Nepse.Holding h : Nepse.watch()) {
            Nepse.Quote q = Nepse.quote(h.symbol);
            if (q == null || h.units <= 0) continue;
            n++;
            value += h.units * q.ltp;
            cost += h.units * h.buy;
            today += h.units * (q.ltp - q.ltp / (1 + q.pct / 100));
        }
        if (n == 0) {
            portfolio.setText("");
            return;
        }
        String s = String.format(Locale.US, "My holdings: value %,.2f · today %+,.2f", value, today);
        if (cost > 0) s += String.format(Locale.US, " · since buying %+,.2f (%+.2f%%)", value - cost, (value - cost) / cost * 100);
        portfolio.setText(s);
        portfolio.setTextColor(today >= 0 ? UP : DOWN);
    }

    @Override protected void onResume() {
        super.onResume();
        rebuild();   // a star or a holding may have changed in the detail screen
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
