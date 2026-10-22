package io.freemytv;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The Bikram Sambat (Nepali) calendar from assets/patro.txt: each month's first day in AD and length, the festivals
 * and the public holidays. BS month lengths follow no rule, so the file is the calendar: it covers 2083 and 2084,
 * and a date outside it has no month (find returns null).
 */
final class Patro {
    private Patro() {}

    static final String[] MONTHS = {"बैशाख", "जेष्ठ", "असार", "श्रावण", "भाद्र", "आश्विन", "कार्तिक", "मंसिर", "पौष", "माघ", "फाल्गुन", "चैत्र"};
    static final String[] MONTHS_EN = {"Baisakh", "Jestha", "Ashadh", "Shrawan", "Bhadra", "Ashwin", "Kartik", "Mangsir", "Poush", "Magh", "Falgun", "Chaitra"};
    /** Sunday first, like the grid. */
    static final String[] DAYS = {"आइत", "सोम", "मंगल", "बुध", "बिहि", "शुक्र", "शनि"};

    static final class Month {
        int year, month, length;
        LocalDate start;
        final String[] fest = new String[33];   // by BS day; "" if none, names separated by ";"
        final boolean[] holiday = new boolean[33];

        /** AD date of BS day d. */
        LocalDate date(int d) { return start.plusDays(d - 1); }
    }

    private static List<Month> months;

    static synchronized List<Month> months(Context c) {
        if (months != null) return months;
        List<Month> out = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getAssets().open("patro.txt"), "UTF-8"))) {
            Month m = null;
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("M ")) {
                    String[] p = line.split(" ");
                    m = new Month();
                    m.year = Integer.parseInt(p[1]);
                    m.month = Integer.parseInt(p[2]);
                    m.start = LocalDate.parse(p[3]);
                    m.length = Integer.parseInt(p[4]);
                    java.util.Arrays.fill(m.fest, "");
                    out.add(m);
                } else if (m != null) {
                    String[] p = line.split("\\|", 3);
                    int d = Integer.parseInt(p[0]);
                    m.holiday[d] = p[1].equals("H");
                    m.fest[d] = p.length > 2 ? p[2] : "";
                }
            }
        } catch (Exception e) {
            AppLog.w("patro: " + e.getMessage());
        }
        return months = out;
    }

    /** The month that has this AD date, or null. */
    static Month find(Context c, LocalDate d) {
        for (Month m : months(c)) if (!d.isBefore(m.start) && d.isBefore(m.start.plusDays(m.length))) return m;
        return null;
    }

    /** 2083 as "२०८३". */
    static String digits(int n) {
        StringBuilder s = new StringBuilder();
        for (char ch : String.valueOf(n).toCharArray()) s.append((char) ('०' + (ch - '0')));
        return s.toString();
    }
}
