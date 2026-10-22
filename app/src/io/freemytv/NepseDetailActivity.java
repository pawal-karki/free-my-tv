package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * One stock (or the NEPSE index) on the full screen: the price, a chart you can move along with Left and Right, the
 * day's range and volume, and buttons for the watchlist, your holding and a price alert. Everything you enter stays on
 * the TV. Display only: no trading.
 */
public class NepseDetailActivity extends Activity {
    private static final int UP = 0xFF30D158, DOWN = 0xFFFF453A, DIM = 0xFF8E8E93, INK = 0xFFF2F2F7;
    private static final int[] RANGES = {30, 90, 180, 365};
    private static final String[] RANGE_NAMES = {"1M", "3M", "6M", "1Y"};

    private String symbol;
    private boolean index;
    private float d;
    private int range = 2;
    private double[][] hist;
    private Chart chart;
    private TextView holdingText, watchBtn;
    private final TextView[] rangeChips = new TextView[RANGES.length];
    private String error = "";

    private int dp(float v) {
        return Math.round(v * d);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        Nepse.init(this);
        symbol = getIntent().getStringExtra("symbol");
        index = "NEPSE".equals(symbol);
        Nepse.Quote q = index ? null : Nepse.quote(symbol);
        Nepse.Data data = Nepse.data();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0B0D);
        root.setPadding(dp(48), dp(28), dp(48), dp(24));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text(symbol, 34, INK, Typeface.BOLD));
        TextView name = text(index ? "Nepal Stock Exchange index" : q == null ? "" : q.name, 18, DIM, Typeface.NORMAL);
        name.setPadding(dp(20), 0, 0, 0);
        name.setSingleLine(true);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(top);

        double ltp = index ? (data == null ? 0 : data.index) : q == null ? 0 : q.ltp;
        double pct = index ? (data == null ? 0 : data.pct) : q == null ? 0 : q.pct;
        double change = index ? (data == null ? 0 : data.change) : q == null ? 0 : q.ltp - q.ltp / (1 + q.pct / 100);
        LinearLayout price = new LinearLayout(this);
        price.setGravity(Gravity.BOTTOM);
        price.addView(text(String.format(Locale.US, "%,.2f", ltp), 56, INK, Typeface.BOLD));
        TextView chg = text(String.format(Locale.US, "  %s %.2f (%+.2f%%)", change >= 0 ? "▲" : "▼", Math.abs(change), pct), 24, pct >= 0 ? UP : DOWN, Typeface.BOLD);
        chg.setPadding(dp(16), 0, 0, dp(8));
        price.addView(chg);
        root.addView(price);

        if (!index && q != null) {
            LinearLayout stats = new LinearLayout(this);
            stats.addView(stat("DAY HIGH", String.format(Locale.US, "%,.2f", q.high)));
            stats.addView(stat("DAY LOW", String.format(Locale.US, "%,.2f", q.low)));
            stats.addView(stat("VOLUME", String.format(Locale.US, "%,.0f", q.qty)));
            root.addView(stats);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(12), 0, dp(8));
        actions.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < RANGES.length; i++) {
            final int at = i;
            TextView chip = button(RANGE_NAMES[i], () -> { range = at; load(); });
            rangeChips[i] = chip;
            actions.addView(chip, margin());
        }
        if (!index) {
            watchBtn = button("", this::toggleWatch);
            actions.addView(watchBtn, margin());
            actions.addView(button("My holding", this::editHolding), margin());
            actions.addView(button("Price alert", this::editAlert), margin());
        }
        root.addView(actions);

        holdingText = text("", 18, INK, Typeface.NORMAL);
        holdingText.setPadding(0, 0, 0, dp(6));
        root.addView(holdingText);

        chart = new Chart(this);
        root.addView(chart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        refreshTexts();
        chart.requestFocus();   // Left and Right move the marker along the chart; Up reaches the buttons
        load();
    }

    private LinearLayout.LayoutParams margin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(10);
        return lp;
    }

    private View stat(String label, String value) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(0, dp(6), dp(48), 0);
        c.addView(text(label, 12, DIM, Typeface.BOLD));
        c.addView(text(value, 22, INK, Typeface.BOLD));
        return c;
    }

    private TextView text(String s, float sp, int color, int style) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }

    private TextView button(String label, Runnable action) {
        TextView t = text(label, 16, INK, Typeface.BOLD);
        t.setPadding(dp(18), dp(9), dp(18), dp(9));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(v -> action.run());
        t.setOnFocusChangeListener((v, f) -> paintButtons());
        return t;
    }

    private void paintButtons() {
        for (int i = 0; i < rangeChips.length; i++) style(rangeChips[i], i == range);
        if (watchBtn != null) style(watchBtn, false);
        ViewGroup actions = (ViewGroup) rangeChips[0].getParent();
        for (int i = 0; i < actions.getChildCount(); i++) {
            View v = actions.getChildAt(i);
            if (v instanceof TextView && !isRange((TextView) v)) style((TextView) v, false);
        }
    }

    private boolean isRange(TextView t) {
        for (TextView r : rangeChips) if (r == t) return true;
        return false;
    }

    private void style(TextView t, boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(20));
        g.setColor(t.isFocused() ? 0xFFFFFFFF : selected ? 0x55FFFFFF : 0x1AFFFFFF);
        t.setBackground(g);
        t.setTextColor(t.isFocused() ? 0xFF111111 : INK);
    }

    private void refreshTexts() {
        if (watchBtn != null) watchBtn.setText(Nepse.watched(symbol) ? "★ Watching" : "☆ Add to watchlist");
        Nepse.Holding h = Nepse.holding(symbol);
        Nepse.Quote q = Nepse.quote(symbol);
        StringBuilder sb = new StringBuilder();
        if (h != null && h.units > 0 && q != null) {
            double value = h.units * q.ltp, cost = h.units * h.buy;
            sb.append(String.format(Locale.US, "You hold %,.0f units · value %,.2f", h.units, value));
            if (h.buy > 0) sb.append(String.format(Locale.US, " · profit/loss %+,.2f (%+.2f%%)", value - cost, cost == 0 ? 0 : (value - cost) / cost * 100));
        }
        if (h != null && (h.above > 0 || h.below > 0)) {
            if (sb.length() > 0) sb.append("   ");
            sb.append("Alert:");
            if (h.above > 0) sb.append(String.format(Locale.US, " above %,.2f", h.above));
            if (h.below > 0) sb.append(String.format(Locale.US, " below %,.2f", h.below));
        }
        holdingText.setText(sb.toString());
        paintButtons();
    }

    private void toggleWatch() {
        if (Nepse.watched(symbol)) Nepse.unwatch(symbol); else Nepse.save(new Nepse.Holding(symbol));
        refreshTexts();
    }

    private void editHolding() {
        Nepse.Holding h = Nepse.holding(symbol);
        if (h == null) h = new Nepse.Holding(symbol);
        final Nepse.Holding hold = h;
        EditText units = field("Units you hold", hold.units), buy = field("Average buy price", hold.buy);
        ask("My holding (stays on this TV)", units, buy, () -> {
            hold.units = value(units);
            hold.buy = value(buy);
            Nepse.save(hold);
            refreshTexts();
        });
    }

    private void editAlert() {
        Nepse.Holding h = Nepse.holding(symbol);
        if (h == null) h = new Nepse.Holding(symbol);
        final Nepse.Holding hold = h;
        EditText above = field("Alert when price is at or above", hold.above), below = field("Alert when price is at or below", hold.below);
        ask("Price alert (0 = none)", above, below, () -> {
            hold.above = value(above);
            hold.below = value(below);
            Nepse.save(hold);
            refreshTexts();
        });
    }

    private EditText field(String hint, double v) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (v > 0) e.setText(String.valueOf(v == Math.rint(v) ? (long) v + "" : String.valueOf(v)));
        return e;
    }

    private static double value(EditText e) {
        try {
            return Math.max(0, Double.parseDouble(e.getText().toString().trim()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private void ask(String title, EditText a, EditText b, Runnable save) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(a);
        box.addView(b);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setView(box)
                .setPositiveButton("Save", (dlg, w) -> save.run()).setNegativeButton("Cancel", null).show();
    }

    private void load() {
        paintButtons();
        error = "";
        hist = null;
        chart.invalidate();
        final int days = RANGES[range], mine = range;
        new Thread(() -> {
            double[][] h = null;
            String err = "";
            try {
                h = Nepse.history(symbol, days);
            } catch (java.io.IOException e) {
                err = "No chart available right now.";
            }
            final double[][] result = h;
            final String message = err;
            runOnUiThread(() -> {
                if (mine != range) return;
                hist = result;
                error = message;
                chart.cursor = hist == null ? 0 : hist[1].length - 1;
                chart.invalidate();
            });
        }).start();
    }

    /** The closing prices as a line with a soft fill; Left and Right move a marker that reads the date and price. */
    private final class Chart extends View {
        int cursor;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path(), area = new Path();
        private final SimpleDateFormat fmt = new SimpleDateFormat("d MMM yyyy", Locale.ENGLISH);

        Chart(Context c) {
            super(c);
            setFocusable(true);
            fmt.setTimeZone(TimeZone.getTimeZone("Asia/Kathmandu"));
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(2.5f));
            line.setStrokeJoin(Paint.Join.ROUND);
            txt.setTextSize(dp(15));
        }

        @Override public boolean onKeyDown(int code, KeyEvent e) {
            if (hist == null || hist[1].length == 0) return super.onKeyDown(code, e);
            int step = Math.max(1, hist[1].length / 40);
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) cursor = Math.max(0, cursor - step);
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) cursor = Math.min(hist[1].length - 1, cursor + step);
            else return super.onKeyDown(code, e);
            invalidate();
            return true;
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), padL = dp(12), padR = dp(90), padT = dp(10), padB = dp(40);
            txt.setColor(DIM);
            if (hist == null || hist[1].length < 2) {
                c.drawText(error.isEmpty() ? "Loading chart…" : error, padL, h / 2, txt);
                return;
            }
            double[] v = hist[1];
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (double x : v) { min = Math.min(min, x); max = Math.max(max, x); }
            if (max - min < 1e-9) max = min + 1;
            boolean up = v[v.length - 1] >= v[0];
            int color = up ? UP : DOWN;
            float cw = w - padL - padR, ch = h - padT - padB;
            path.reset();
            area.reset();
            for (int i = 0; i < v.length; i++) {
                float x = padL + cw * i / (v.length - 1), y = padT + ch * (float) (1 - (v[i] - min) / (max - min));
                if (i == 0) { path.moveTo(x, y); area.moveTo(x, padT + ch); area.lineTo(x, y); } else { path.lineTo(x, y); area.lineTo(x, y); }
            }
            area.lineTo(padL + cw, padT + ch);
            area.close();
            fill.setShader(new LinearGradient(0, padT, 0, padT + ch, (color & 0x00FFFFFF) | 0x55000000, (color & 0x00FFFFFF), Shader.TileMode.CLAMP));
            c.drawPath(area, fill);
            line.setColor(color);
            c.drawPath(path, line);
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setColor(DIM);
            c.drawText(String.format(Locale.US, "%,.2f", max), padL + cw + dp(8), padT + dp(12), txt);
            c.drawText(String.format(Locale.US, "%,.2f", min), padL + cw + dp(8), padT + ch, txt);
            c.drawText(fmt.format(new Date((long) hist[0][0] * 1000)), padL, h - dp(10), txt);
            txt.setTextAlign(Paint.Align.RIGHT);
            c.drawText(fmt.format(new Date((long) hist[0][v.length - 1] * 1000)), padL + cw, h - dp(10), txt);
            int i = Math.min(cursor, v.length - 1);
            float x = padL + cw * i / (v.length - 1), y = padT + ch * (float) (1 - (v[i] - min) / (max - min));
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(0x55FFFFFF);
            dot.setStrokeWidth(dp(1));
            c.drawLine(x, padT, x, padT + ch, dot);
            dot.setColor(Color.WHITE);
            c.drawCircle(x, y, dp(5), dot);
            String label = fmt.format(new Date((long) hist[0][i] * 1000)) + "  ·  " + String.format(Locale.US, "%,.2f", v[i]);
            txt.setColor(INK);
            txt.setTextAlign(x > w / 2 ? Paint.Align.RIGHT : Paint.Align.LEFT);
            c.drawText(label, x > w / 2 ? x - dp(8) : x + dp(8), padT + dp(14), txt);
        }
    }
}
