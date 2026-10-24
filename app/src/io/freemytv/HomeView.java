package io.freemytv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the small crypto quotes
 *    (logo + price) at the right end of the tab row;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without one, a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours
 *    and quotes with the 24 h change); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: icons in each brand's color, 48 apart, and the glass bar hugging them with 40 of
    // padding (the focused icon grows 20% without touching its edge). The icons are 16:9, the shape of the apps' TV
    // banners, so those are never stretched: up to 224 × 126, 178 × 100 with eight apps.
    private static final float SHELF_PAD = 40, TILE_GAP = 48;
    private static final float TILE_RADIUS = 22;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, MENU = 8;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
    private static final float L_TITLE_BASE = 130, L_TABBAR_TOP = 158, L_TABBAR_H = 68, L_CARDS_TOP = 262, L_CARD_H = 442;
    private static final float L_TALL_W = 320, L_CARD_GAP = 40, L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 103;
    private static final float L_PLATTER_TOP = 814, L_TILE_H = 126;
    private static final float L_DATE_BASE = 330, L_CLOCK_BASE = 540, L_WEATHER_BASE = 626, L_QUOTES_BASE = 694;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static final String[] TABS = {"Explorar", "Filmes", "Séries"};
    private static final String[] TABS_RU = {"Обзор", "Фильмы", "Сериалы"};
    private static final String[] TABS_EN = {"Explore", "Movies", "Shows"};
    private static final String[] TABS_ES = {"Explorar", "Películas", "Series"};
    private static final String[] TABS_DE = {"Entdecken", "Filme", "Serien"};
    private static final String[] TAB_KIND = {null, "movie", "tv"};
    private static final int Z_TOP = 4, Z_CARDS = 5, Z_PATRO = 6, Z_APPS = 7;
    // All the apps as rows of square icons under the shelf (in the drawer's page, which scrolls).
    private static final float APPS_TOP = 492, APP_ROW = 190;
    private static final int APP_COLS = 7;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, menuLayer;
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    private int widgetIndex;    // with TalkBack on, the drawer's weather (0) or quotes (1) card can take the focus
    private List<Recs.Pick> cards = new ArrayList<>();
    private final Spring[] topFocus = new Spring[8];
    private final RectF[] topRects = new RectF[8];
    private final Spring[] cardFocus = new Spring[3];
    private final RectF[] cardRects = new RectF[3];
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lQuote, lTip, lDate, lClock, lWeather, lClockQuote;
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;

    // Moving apps on the shelf (as on Apple TV): hold OK on a tile, then ← → move it, OK drops it, Back cancels. The
    // lifted tile wiggles; the others slide to their new places (each slot's x offset is a spring that returns to 0).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.5f;
    private boolean reordering, reorderAnim, okHeld, holdArmed;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) showShelfMenu(); };

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen;
    private int gridIndex;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving, idle;
    private long lastNanos, resumedAt, lastStep;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "", weatherLine = "";
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private String weatherLineLanguage = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Map<Bitmap, BitmapShader> imageShaders = new WeakHashMap<>();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();
    private LinearGradient[] tileShaders = new LinearGradient[0];
    private LinearGradient tileGlow, cardSheen;
    private float tileShaderH, cardSheenH;
    private LinearGradient[] lightSheens = new LinearGradient[0], lightRims = new LinearGradient[0];
    private float[] lightShaderW = new float[0];
    private android.graphics.ColorFilter veilFilter;
    private float veilAmount = -1;
    private int veilColor;
    private HomeData.Weather textWeather;
    private long textPricesAt = -1, textMinute = -1;
    private int textPrefs = -1;
    private String textLanguage;
    private String weatherTitle, nowTemp, highLow, sunriseText, sunsetText, staleText;
    private final String[] hourText = new String[6], hourTempText = new String[6];
    private final String[] priceText = new String[HomeData.COINS.length], changeText = new String[HomeData.COINS.length];

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                case MENU: drawShelfMenu(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final Runnable reloadSoon = this::reload;

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // three new ones every hour
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        Nepse.init(a);
        recs = Recs.get(a);
        click = new Click(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        menuLayer = new Layer(a, MENU);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 9 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        for (View v : new View[] {heroLayer, status, platter, tiles, drawerLayer, sheetLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, menuLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("Free My TV");
        drawerLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < 8; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < 3; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardRects[i] = new RectF();
        }
        makeLightButtons();
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        checkNight(false);
        resumedAt = SystemClock.uptimeMillis();
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        checkNotices();
        reload();
        updatePalette();
        updatePicks();
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        cancelReorder(true);
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reloadSoon);
        data.removeListener(this);
        recs.removeListener(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        wake();
        cancelReorder(!animated);
        zone = Z_SHELF;
        shelfIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        if (tab != 0) selectTab(0);
        retarget(!animated);
        motionDirty = true;
    }

    /** The three corner buttons, with their names in the current language. */
    private void makeLightButtons() {
        lightButtons.clear();
        lightButtons.add(HomeApps.action(HomeApps.LIVE_TV, "live", Ui.tr("TV aberta", "Live TV"), "live_tv"));
        lightButtons.add(HomeApps.action(HomeApps.INPUTS, "inputs", Ui.tr("Entradas HDMI", "HDMI Inputs"), "hdmi"));
        lightButtons.add(HomeApps.action(HomeApps.WIFI, "wifi", "Wi-Fi", "wifi"));
        lightButtons.add(HomeApps.action(HomeApps.THEME, "theme", Ui.tr("Modo escuro", "Dark mode"), "bedtime"));
        lightButtons.add(HomeApps.action(HomeApps.SETTINGS, "settings", Ui.tr("Configurações", "Settings"), "settings"));
    }

    private static String[] tabs() {
        return Ui.ru ? TABS_RU : Ui.es ? TABS_ES : Ui.de ? TABS_DE : Ui.en ? TABS_EN : TABS;
    }

    @Override public void onData() {
        wake();
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, Math.round(790 * kk));
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(320 * kk));
        measure(tiles, width, Math.round(270 * kk));
        measure(drawerLayer, width, Math.round((600 + appsHeight()) * kk));
        measure(sheetLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(750 * kk));
        place(tiles, 0, Math.round(790 * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
        menuLayer.layout(0, 0, width, height);   // the shelf menu is drawn over the whole screen
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        hero = null;
        heroFrom = null;
        updatePicks();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        lClock = Ui.numbers(Ui.light, 200 * k, L_INK);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(Ui.medium, 34 * k, L_DIM);
        lClockQuote = Ui.numbers(Ui.medium, 28 * k, L_DIM);
        lightGradient = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        if (reorderAnim) tilesMoving |= stepReorder(dt, up);
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt);
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving | stepPatro(dt);
        boolean scrollMoving = homeScroll.step(dt);
        if (scrollMoving) motionDirty = true;
        if (appFocus.step(dt)) drawerDirty = true;
        boolean menuMoving = menuAnim.step(dt);
        if (menuMoving) menuLayer.invalidate();
        else if (!menuOpen && menuLayer.getVisibility() == VISIBLE) menuLayer.setVisibility(INVISIBLE);
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);

        if (drawerMoving || sheetMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading) heroLayer.invalidate();
        weatherFxFrame(up);
        nepseTick(up);
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (drawerMoving || bgFading) sky.invalidate();
        if (bgFading || (drawerWasMoving && !drawerMoving)) platter.invalidate();
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
            if (!picksOn()) heroLayer.invalidate();
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || sheetMoving || toastFading || heroFading || bgFading || nightMoving || sheetOpen || sheet.value > 0.001f
                || drawerOpen || drawer.value > 0.001f || toast != null || !toasts.isEmpty() || revealShot != null || menuMoving || menuOpen || scrollMoving;
        if (revealShot != null) invalidate();
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, 1000);
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float sc = homeScroll.value;
        float lift = -d * shift - sc;
        show(heroLayer, top, lift);
        show(status, top, lift);
        show(platter, home, lift);
        show(tiles, home, lift);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift - sc);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        lDate.setColor(tone(L_DIM, 0xFF98989D));
        lClock.setColor(tone(L_INK, WHITE));
        lWeather.setColor(tone(L_DIM, 0xFF98989D));
        lClockQuote.setColor(tone(L_DIM, 0xFF98989D));
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toast != null) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        amount = Math.round(amount * 255) / 255f;
        if (amount == veilAmount && color == veilColor) return veilFilter;
        veilAmount = amount;
        veilColor = color;
        float keep = 1 - amount;
        return veilFilter = new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        tileShaders = new LinearGradient[0];
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        int n = shelf.size();
        float pad = SHELF_PAD * k, gap = TILE_GAP * k;
        float full = (CONTENT_R - MARGIN) * k;
        // Tiles in the TV banners' own 16:9, so an app's art is never stretched; never taller than L_TILE_H. The glass
        // keeps its bottom edge, so with more apps it only gets lower.
        float tw = Math.min(L_TILE_H * 16f / 9 * k, (full - Math.max(0, n - 1) * gap) / Math.max(1, n));
        float th = tw * 9f / 16;
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float top = (L_PLATTER_TOP + L_TILE_H) * k - th;
        platterRect.set(left - pad, top, left + width + pad, top + th + 2 * pad);
        float x = left, y = top + pad;
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty()) return;
        float radius = (TILE_RADIUS + SHELF_PAD) * k;
        tmp.set(platterRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(tone(0x1F000000, 0x52000000));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        // White glass by day; smoked glass at night.
        scene.liquid(c, platterRect, radius, tone(0xFFFFFFFF, SMOKE), tone(0.42f, 0.12f), tone(0.03f, 0.08f),
                tone(30, 34) * k, tone(22, 28) * k, tone(0.65f, 0.95f), 1);
    }

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    private Quotes quotes;

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar. */
    private void drawQuotes(Canvas c) {
        boolean clock = !picksOn();
        float right = platterRect.isEmpty() ? CONTENT_R * k : platterRect.right - SHELF_PAD * k;
        applyTone();
        Paint text = clock ? lClockQuote : lQuote;
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        float fade = 1;
        for (int i = 3; i < 3 + lightButtons.size() && !clock; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        int n = HomeData.coinCount();
        if (n == 0) { text.setColor(textColor); return; }
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        int[] coins = HomeData.active();
        Quotes q = quotes;
        if (q == null || !q.matches(coins, data.prices, text.getTextSize())) {
            q = quotes = new Quotes(coins, data.prices, text);
        }
        // On the tab row, at the right.
        float base = clock ? L_QUOTES_BASE * k : (L_TABBAR_TOP + L_TABBAR_H / 2) * k + q.halfCap;
        float cap = q.cap, gap = (clock ? 8 : 6) * k, spacing = (clock ? 32 : 24) * k;
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) total += q.glyphs[i] + gap + q.widths[i];
        float x = clock ? MARGIN * k : right - total;
        int coinColor = clock ? text.getColor() : Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade);
        for (int i = 0; i < n; i++) {
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap, coinColor);
            x += q.glyphs[i] + gap;
            c.drawText(q.values[i], x, base, text);
            x += q.widths[i] + spacing;
        }
        text.setColor(textColor);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (stale.isEmpty()) return;
        if (clock) c.drawText(stale, x - spacing + 24 * k + staleQuote.measureText(stale), base, staleQuote);
        else c.drawText(stale, right - total - 24 * k, base, staleQuote);
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                float angle = i == liftIndex ? wiggle * moveLift.value : 0;
                if (dx == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        float l = i == liftIndex ? Math.max(0, moveLift.value) : 0;
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f + LIFT_GROW * l;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f + 6 * l) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc + 0.14f * Ui.clamp(l)));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            if (tileShaders.length != shelf.size() || tileShaderH != bh) {
                tileShaders = new LinearGradient[shelf.size()];
                tileShaderH = bh;
                tileGlow = new LinearGradient(0, -bh / 2, 0, 0, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            if (tileShaders[i] == null) {
                tileShaders[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, style[0], style[1], Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(tileShaders[i]);
            tileFill.setAlpha(255);
            c.drawRoundRect(tmp, r0, r0, tileFill);
            if (fc > 0.01f) {
                tileFill.setShader(tileGlow);
                tileFill.setAlpha(Math.round(255 * fc));
                c.drawRoundRect(tmp, r0, r0, tileFill);
                tileFill.setAlpha(255);
            }
            tileFill.setShader(null);
            c.restore();
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else {
            if (it.tile != null) roundImage(c, it.tile, cell, r);
        }
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`. Android 12+ clips to the rounded shape; on Android 9 to
     * 11 that anti-aliased clip costs the GPU a mask on every frame of a focus animation (measured on a Mi Box 3: most of
     * a 49 ms frame), so there the image is the shader of a rounded rectangle instead, which looks the same.
     */
    private void roundImage(Canvas c, Bitmap image, RectF cell, float r) {
        if (!Ui.LEGACY) {
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(image, null, cell, bitmapPaint);
            c.restore();
            return;
        }
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        imageMatrix.setScale(cell.width() / image.getWidth(), cell.height() / image.getHeight());
        imageMatrix.postTranslate(cell.left, cell.top);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size and to the resting one: focused or at
     * rest it is drawn pixel for pixel, without resampling (on a 720p UI, shrinking at draw time made it look jagged).
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        boolean rest = s < 1 + FOCUS_GROW / 2;
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null, rest);
        if (logo == null) return;
        float scale = rest ? s : s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (rest ? Math.abs(scale - 1) < 0.002f : scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark, boolean rest) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width()) + (rest ? "@rest" : "");
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        if (rest) scale /= 1 + FOCUS_GROW;
        Bitmap out = downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    /** Halves the size while it can (a 2×2 average, no aliasing), then finishes at the exact size. */
    private static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts());
        java.util.Iterator<HomeApps.Item> each = list.iterator();
        while (each.hasNext()) if (each.next().kind == HomeApps.ALL_APPS) each.remove();   // the apps are right there now
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = (ROW_TOP + appsHeight()) * k;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            x += tw + gap;
        }
        float cardsTop = (CARDS_TOP + appsHeight()) * k, split = left + (right - left) * 0.585f;
        boolean wx = Prefs.weather(), qt = Prefs.nepse();
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, qt ? split - 16 * k : right, cardsTop + CARDS_H * k);
        quotesCard.set(wx ? split + 16 * k : left, cardsTop, right, cardsTop + CARDS_H * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty() && others.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = shelf.isEmpty() ? 0 : nearest(drawerRects, tileRects[shelfIndex].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - dk));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        pricePaint.setColor(drawerInk(0.95f));
        drawApps(c);
        float titleBase = (ROW_TITLE + appsHeight()) * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Entradas", "Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerItems.size()) c.drawText(Ui.tr("Atalhos", "Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (Prefs.weather()) drawWeatherCard(c, weatherCard);
        if (Prefs.nepse()) drawQuotesCard(c, quotesCard);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        fitPaint.set(drawerLabel);
        String label = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    /** Weather for your location: now (large), the next 6 hours, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (!Ui.LEGACY) drawWeatherFx(c, r);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private Paint nxTick;

    /** Under the weather line on the home screen: the NEPSE index and up to four of your watched stocks. */
    private void drawNepseTicker(Canvas c) {
        Nepse.Data d = Nepse.data();
        if (d == null) return;
        if (nxTick == null) nxTick = Ui.numbers(Ui.medium, 27 * k, L_DIM);
        float x = MARGIN * k, y = 704 * k;
        int up = tone(0xFF248A3D, UP_COLOR), down = tone(0xFFD70015, DOWN_COLOR);
        nxTick.setColor(tone(L_DIM, 0xADFFFFFF));
        String head = "NEPSE  " + String.format(java.util.Locale.US, "%,.2f", d.index) + "  ";
        c.drawText(head, x, y, nxTick);
        x += nxTick.measureText(head);
        nxTick.setColor(d.change >= 0 ? up : down);
        c.drawText((d.change >= 0 ? "▲ " : "▼ ") + String.format(java.util.Locale.US, "%.2f%%", Math.abs(d.pct)), x, y, nxTick);
        x = MARGIN * k;
        int shown = 0;
        for (Nepse.Holding h : Nepse.watch()) {
            Nepse.Quote q = Nepse.quote(h.symbol);
            if (q == null || shown >= 4) continue;
            nxTick.setColor(tone(L_DIM, 0xADFFFFFF));
            String sym = (shown == 0 ? "★ " : "") + q.symbol + " ";
            c.drawText(sym, x, y + 40 * k, nxTick);
            x += nxTick.measureText(sym);
            nxTick.setColor(q.pct >= 0 ? up : down);
            String pct = String.format(java.util.Locale.US, "%+.1f%%", q.pct) + "   ";
            c.drawText(pct, x, y + 40 * k, nxTick);
            x += nxTick.measureText(pct);
            shown++;
        }
    }

    private Paint nxBig, nxRow, nxHead;

    /** NEPSE: the index with its change, and the day's top three gainers and losers. */
    private void drawNepse(Canvas c, RectF r) {
        if (nxBig == null) {
            nxBig = Ui.numbers(Ui.medium, 44 * k, 0xFFFFFFFF);
            nxRow = Ui.numbers(Ui.medium, 20 * k, 0xFFFFFFFF);
            nxHead = Ui.text(Ui.semibold, 18 * k, 0xADFFFFFF);
        }
        nxBig.setColor(drawerInk(1));
        nxRow.setColor(drawerInk(0.95f));
        nxHead.setColor(tone(L_DIM, 0xADFFFFFF));
        float x = r.left + 34 * k, right = r.right - 34 * k;
        if (zone == WIDGETS && widgetIndex == 1) {   // focused: a ring and the way in
            phStrokeInit();
            phStroke.setStrokeWidth(3 * k);
            phStroke.setColor(tone(0xFF0A84FF, 0xFF64A8FF));
            c.drawRoundRect(r, 30 * k, 30 * k, phStroke);
            c.drawText(Ui.tr("OK abre", "OK to open") + " ›", right, r.bottom - 22 * k, cardDimRight);
        }
        c.drawText("NEPSE", x, r.top + 54 * k, cardTitle);
        Nepse.Data d = Nepse.data();
        if (d == null) {
            c.drawText(Ui.tr("Carregando…", "Loading…"), x, r.top + 130 * k, cardDim);
            return;
        }
        String when = Nepse.status(getContext(), d);
        int paren = when.indexOf(" (");
        if (paren > 0) when = when.substring(0, paren);
        c.drawText(when.length() > 34 ? when.substring(0, 33) + "…" : when, right, r.top + 54 * k, cardDimRight);
        int up = tone(0xFF248A3D, UP_COLOR), down = tone(0xFFD70015, DOWN_COLOR);
        int color = d.change >= 0 ? up : down;
        String value = String.format(java.util.Locale.US, "%,.2f", d.index);
        c.drawText(value, x - Ui.leftBearing(nxBig, value), r.top + 136 * k, nxBig);
        String chg = (d.change >= 0 ? "▲ " : "▼ ") + String.format(java.util.Locale.US, "%.2f  (%+.2f%%)", Math.abs(d.change), d.pct);
        changePaint.setColor(color);
        float cw = changePaint.measureText(chg) + 36 * k;
        tmp.set(x, r.top + 168 * k, x + cw, r.top + 206 * k);
        fill.setShader(null);
        fill.setColor(Ui.alpha(color, 0.2f));
        c.drawRoundRect(tmp, 19 * k, 19 * k, fill);
        changePaint.setTextAlign(Paint.Align.CENTER);
        c.drawText(chg, tmp.centerX(), tmp.centerY() + Ui.halfCap(changePaint), changePaint);
        float col = r.left + 318 * k, colW = (right - col) / 2;
        for (int side = 0; side < 2; side++) {
            List<Nepse.Quote> list = side == 0 ? d.gainers : d.losers;
            boolean mine = false;
            if (side == 1 && !Prefs.nepseWatch().isEmpty()) {   // your watchlist takes the losers' column
                List<Nepse.Quote> w = new java.util.ArrayList<>();
                for (Nepse.Holding h : Nepse.watch()) {
                    Nepse.Quote q = Nepse.quote(h.symbol);
                    if (q != null && w.size() < 3) w.add(q);
                }
                if (!w.isEmpty()) {
                    list = w;
                    mine = true;
                }
            }
            float cx = col + colW * side;
            c.drawText(side == 0 ? Ui.tr("MAIORES ALTAS", "TOP GAINERS") : mine ? Ui.tr("MINHA LISTA", "WATCHLIST") : Ui.tr("MAIORES BAIXAS", "TOP LOSERS"), cx, r.top + 108 * k, nxHead);
            for (int i = 0; i < list.size(); i++) {
                Nepse.Quote q = list.get(i);
                float base = r.top + (150 + 44 * i) * k;
                nxRow.setTextAlign(Paint.Align.LEFT);
                nxRow.setColor(drawerInk(0.95f));
                c.drawText(q.symbol.length() > 7 ? q.symbol.substring(0, 7) : q.symbol, cx, base, nxRow);
                nxRow.setTextAlign(Paint.Align.RIGHT);
                nxRow.setColor(q.pct >= 0 ? up : down);
                c.drawText(String.format(java.util.Locale.US, "%+.2f%%", q.pct), cx + colW - 14 * k, base, nxRow);
            }
            if (list.isEmpty()) c.drawText("—", cx, r.top + 150 * k, nxRow);
        }
        changePaint.setTextAlign(Paint.Align.CENTER);
    }

    // MARK: - Weather card animation: a turning sun, drifting clouds, rain, snow, thunder, twinkling stars

    private static final float[] FX = new float[240];
    static {
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < FX.length; i++) FX[i] = rnd.nextFloat();
    }
    private Paint fxPaint;
    private android.graphics.RadialGradient sunGlow;
    private final Path fxClip = new Path();
    private long fxAt;

    /** True while the weather card is on screen and its animation needs frames (about 20 a second). */
    private Nepse.Data nepseShown, nepseHome;
    private long nepseTickAt;
    private String nepseWatchSeen = "";

    /** Once a second: keep the watchlist fresh during trading hours, and redraw the home ticker when new data came. */
    private void nepseTick(long up) {
        if (up - nepseTickAt < 1000) return;
        nepseTickAt = up;
        if (!Prefs.nepse()) return;
        if (!Prefs.nepseWatch().isEmpty() && Nepse.tradingHours()) Nepse.refresh(5 * 60_000L);
        String watch = Prefs.nepseWatch();
        if (Nepse.data() != nepseHome || !watch.equals(nepseWatchSeen)) {
            nepseWatchSeen = watch;
            nepseHome = Nepse.data();
            heroDirty = true;
            drawerDirty = true;
        }
    }

    private boolean weatherFxFrame(long up) {
        if (drawerOpen && Prefs.nepse()) {
            Nepse.refresh();
            if (Nepse.data() != nepseShown) {
                nepseShown = Nepse.data();
                drawerDirty = true;
            }
        }
        if (!(drawerOpen || drawer.value > 0.001f) || !Prefs.weather() || Double.isNaN(data.weather.temp)) return false;
        if (up - fxAt < 50) return true;
        fxAt = up;
        drawerLayer.invalidate();
        return true;
    }

    private void drawWeatherFx(Canvas c, RectF r) {
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp) || wx.code < 0) return;
        if (fxPaint == null) {
            fxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            sunGlow = new android.graphics.RadialGradient(0, 0, 150 * k, new int[] {0x66FFB300, 0x22FFB300, 0x00FFB300},
                    new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP);
        }
        float t = SystemClock.uptimeMillis() / 1000f, w = r.width(), h = r.height();
        int code = wx.code;
        boolean rain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95;
        boolean snow = (code >= 71 && code <= 77) || code == 85 || code == 86;
        boolean cloudy = code >= 2 && code != 0 && code != 1;
        fxClip.reset();
        fxClip.addRoundRect(r, 30 * k, 30 * k, Path.Direction.CW);
        c.save();
        c.clipPath(fxClip);
        fxPaint.setShader(null);
        fxPaint.setStyle(Paint.Style.FILL);
        if (code <= 1 || code == 2) {
            if (wx.isDay) {
                // Sun: a breathing glow and rays that turn slowly behind the icon.
                float cx = r.left + 63 * k, cy = r.top + 132 * k, pulse = 1 + 0.06f * (float) Math.sin(t * 1.6);
                c.save();
                c.translate(cx, cy);
                c.scale(pulse, pulse);
                fxPaint.setShader(sunGlow);
                c.drawCircle(0, 0, 150 * k, fxPaint);
                fxPaint.setShader(null);
                c.restore();
                fxPaint.setStyle(Paint.Style.STROKE);
                fxPaint.setStrokeCap(Paint.Cap.ROUND);
                fxPaint.setStrokeWidth(5 * k);
                fxPaint.setColor(0x66FFB300);
                c.save();
                c.translate(cx, cy);
                c.rotate(t * 10);
                for (int i = 0; i < 14; i++) {
                    float len = (16 + 10 * (float) Math.sin(t * 1.8 + i)) * k;
                    c.drawLine(44 * k, 0, 44 * k + len * 0.8f, 0, fxPaint);
                    c.rotate(360f / 14);
                }
                c.restore();
                fxPaint.setStyle(Paint.Style.FILL);
            } else {
                for (int i = 0; i < 40; i++) {
                    float a = 0.25f + 0.55f * (0.5f + 0.5f * (float) Math.sin(t * (1.2f + FX[3 * i + 2]) + FX[3 * i] * 20));
                    fxPaint.setColor(Ui.alpha(tone(0xFF8E8E93, 0xFFFFFFFF), a * 0.7f));
                    c.drawCircle(r.left + FX[3 * i] * w, r.top + FX[3 * i + 1] * h * 0.8f, (1 + 1.6f * FX[3 * i + 2]) * k, fxPaint);
                }
            }
        }
        if (cloudy || rain || snow) {
            fxPaint.setColor(tone(0x1A5A6B80, 0x2EFFFFFF));
            for (int i = 0; i < 3; i++) {
                float cx = r.left + (((t * (7 + 4 * i) + FX[i] * (w + 300 * k)) % (w + 300 * k)) - 150 * k);
                float cy = r.top + (40 + 36 * i) * k, s = (0.8f + 0.5f * FX[10 + i]) * k;
                c.drawCircle(cx, cy, 34 * s, fxPaint);
                c.drawCircle(cx + 38 * s, cy + 6 * s, 28 * s, fxPaint);
                c.drawCircle(cx - 36 * s, cy + 8 * s, 26 * s, fxPaint);
                c.drawRoundRect(cx - 60 * s, cy + 6 * s, cx + 62 * s, cy + 36 * s, 15 * s, 15 * s, fxPaint);
            }
        }
        if (rain) {
            fxPaint.setStyle(Paint.Style.STROKE);
            fxPaint.setStrokeCap(Paint.Cap.ROUND);
            fxPaint.setStrokeWidth(2.2f * k);
            fxPaint.setColor(tone(0x886AA8E8, 0xAA9CC8FF));
            int n = code >= 95 || code == 65 || code == 82 ? 70 : 46;
            for (int i = 0; i < n; i++) {
                float speed = 0.9f + 0.9f * FX[3 * i + 1], y = ((t * speed + FX[3 * i + 2]) % 1) * (h + 30 * k) - 20 * k;
                float x = r.left + FX[3 * i] * (w + 40 * k) - 10 * k + y * 0.12f;
                c.drawLine(x, r.top + y, x - 4 * k, r.top + y + (12 + 10 * FX[3 * i + 1]) * k, fxPaint);
            }
            fxPaint.setStyle(Paint.Style.FILL);
        }
        if (snow) {
            fxPaint.setColor(tone(0xCC9DB4CC, 0xE6FFFFFF));
            for (int i = 0; i < 46; i++) {
                float speed = 0.12f + 0.12f * FX[3 * i + 1], y = ((t * speed + FX[3 * i + 2]) % 1) * (h + 20 * k) - 10 * k;
                float x = r.left + FX[3 * i] * w + (float) Math.sin(t * 1.1 + FX[3 * i + 2] * 9) * 10 * k;
                c.drawCircle(x, r.top + y, (1.8f + 2.4f * FX[3 * i + 1]) * k, fxPaint);
            }
        }
        if (code >= 95) {   // thunder: a quick double flash every few seconds
            float ph = (t % 6f);
            float flash = ph < 0.12f ? 1 - ph / 0.12f : ph > 0.25f && ph < 0.33f ? (0.33f - ph) / 0.08f * 0.6f : 0;
            if (flash > 0) {
                fxPaint.setColor(Ui.alpha(0xFFFFFFFF, flash * tone(0.55f, 0.35f)));
                c.drawRect(r, fxPaint);
            }
        }
        c.restore();
    }

    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle, x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("Sem dados do clima agora.", "No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        c.drawText(nowTemp, x + sw + 18 * k - Ui.leftBearing(bigTemp, nowTemp), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(highLow, x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k, hw = (r.right - 30 * k - hx) / 6;
        for (int i = 0; i < wx.hours.size() && i < 6; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(hourText[i], hc, r.top + 96 * k, hourPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
            c.drawText(hourTempText[i], hc, r.top + 196 * k, hourTemp);
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", sunriseText, sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", sunsetText, sx, base);
    }

    private void refreshDrawerText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        if (wx != textWeather || settings) {
            textWeather = wx;
            weatherTitle = Prefs.city().isEmpty() ? Ui.tr("Clima", "Weather") : Prefs.city();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = Ui.ru ? "Макс. " + HomeData.degrees(wx.max) + "   Мин. " + HomeData.degrees(wx.min)
                    : Ui.en ? "H:" + HomeData.degrees(wx.max) + "   L:" + HomeData.degrees(wx.min)
                    : Ui.de ? "H:" + HomeData.degrees(wx.max) + "   T:" + HomeData.degrees(wx.min)
                    : "Máx. " + HomeData.degrees(wx.max) + "   Mín. " + HomeData.degrees(wx.min);
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                hourText[i] = HomeData.hour(wx.hours.get(i));
                hourTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
            }
            sunriseText = Ui.tr("Nascer ", "Sunrise ") + Ui.time(wx.sunrise);
            sunsetText = Ui.tr("Pôr do sol ", "Sunset ") + Ui.time(wx.sunset);
        }
        long minute = System.currentTimeMillis() / 60_000;
        if (data.pricesAt != textPricesAt || minute != textMinute || settings) {
            textPricesAt = data.pricesAt;
            textMinute = minute;
            String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
            staleText = stale.isEmpty() ? Ui.tr("variação em 24 h", "24h change") : Ui.tr("de ", "", "de ") + stale;
            for (int i = 0; i < HomeData.COINS.length; i++) {
                priceText[i] = HomeData.brl(data.prices[i]);
                changeText[i] = HomeData.change(data.changes[i]);
            }
        }
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /** Prices in the configured currency with the 24 h change (green/red, like Apple's Stocks app). */
    private void drawQuotesCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) quotesSnapshot.draw(c, r, drawerCardsKey(), cc -> drawNepse(cc, r));
        else drawNepse(c, r);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + data.pricesAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 9 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot(), quotesSnapshot = new Snapshot();

    private static final int RED = 0xFFFF5A52;
    private static final DateTimeFormatter AD = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH);
    private long patroDay = -1;
    private Patro.Month patroMonth;
    private int patroToday;
    private final String[] patroUpcoming = new String[3];
    private String patroHeader = "", patroSub = "", patroDate = "";
    private Paint patroBig, patroCell, patroCellDim, patroFest;

    /** The Patro card's text, built once a day. */
    private void refreshPatro() {
        LocalDate today = LocalDate.now();
        if (today.toEpochDay() == patroDay) return;
        patroDay = today.toEpochDay();
        List<Patro.Month> all = Patro.months(getContext());
        patroMonth = Patro.find(getContext(), today);
        java.util.Arrays.fill(patroUpcoming, null);
        if (patroMonth == null) return;
        Patro.Month m = patroMonth;
        patroToday = (int) (today.toEpochDay() - m.start.toEpochDay()) + 1;
        patroHeader = Patro.MONTHS[m.month - 1] + " " + Patro.digits(m.year);
        patroSub = Patro.MONTHS_EN[m.month - 1] + " " + m.year;
        patroDate = today.format(AD);
        int n = 0;
        for (int mi = all.indexOf(m); mi < all.size() && n < 3; mi++) {
            Patro.Month q = all.get(mi);
            for (int d = mi == all.indexOf(m) ? patroToday : 1; d <= q.length && n < 3; d++) {
                if (q.fest[d].isEmpty()) continue;
                patroUpcoming[n++] = Patro.digits(d) + (q == m ? "" : " " + Patro.MONTHS[q.month - 1]) + "  " + q.fest[d].split(";")[0];
            }
        }
    }

    /** Bikram Sambat month grid with today marked, and the next festivals (Nepali patro). */
    private void drawPrices(Canvas c, RectF r) {
        refreshPatro();
        if (patroBig == null) {
            patroBig = Ui.numbers(Ui.medium, 72 * k, 0xFFFFFFFF);
            patroCell = Ui.numbers(Ui.medium, 21 * k, 0xF2FFFFFF);
            patroCell.setTextAlign(Paint.Align.CENTER);
            patroCellDim = Ui.text(Ui.medium, 18 * k, 0xADFFFFFF);
            patroCellDim.setTextAlign(Paint.Align.CENTER);
            patroFest = Ui.text(Ui.medium, 22 * k, 0xF2FFFFFF);
        }
        float x = r.left + 34 * k, right = r.right - 34 * k;
        Patro.Month m = patroMonth;
        if (m == null) {
            c.drawText(Ui.tr("Calendário", "Patro"), x, r.top + 54 * k, cardTitle);
            c.drawText(Ui.tr("Sem dados para esta data", "No calendar data for this date"), x, r.top + 110 * k, cardDim);
            return;
        }
        patroCell.setColor(drawerInk(0.95f));
        patroFest.setColor(drawerInk(0.95f));
        patroBig.setColor(drawerInk(1));
        patroCellDim.setColor(tone(L_DIM, 0xADFFFFFF));
        c.drawText(patroHeader, x, r.top + 54 * k, cardTitle);
        float gridW = (right - x) * 0.55f, cw = gridW / 7;
        c.drawText(patroSub, x + gridW, r.top + 54 * k, cardDimRight);
        for (int i = 0; i < 7; i++) {
            patroCellDim.setColor(i == 6 ? RED : tone(L_DIM, 0xADFFFFFF));
            c.drawText(Patro.DAYS[i], x + cw * (i + 0.5f), r.top + 92 * k, patroCellDim);
        }
        int col = m.start.getDayOfWeek().getValue() % 7;   // Monday 1 .. Sunday 7 -> Sunday 0
        float rowH = 29 * k, gridTop = r.top + 100 * k;
        for (int d = 1; d <= m.length; d++, col++) {
            float cx = x + cw * (col % 7 + 0.5f), base = gridTop + rowH * (col / 7 + 1) - 7 * k;
            boolean red = col % 7 == 6 || m.holiday[d];
            if (d == patroToday) {
                tmp.set(cx - 18 * k, base - 22 * k, cx + 18 * k, base + 7 * k);
                fill.setShader(null);
                fill.setColor(tone(0xFF1C1C1E, 0xF2FFFFFF));
                c.drawRoundRect(tmp, 14 * k, 14 * k, fill);
                patroCell.setColor(tone(0xFFFFFFFF, 0xFF1C1C1E));
            } else {
                patroCell.setColor(red ? RED : drawerInk(0.95f));
            }
            c.drawText(Patro.digits(d), cx, base, patroCell);
        }
        float px = x + gridW + 44 * k, pw = right - px;
        c.drawText(Patro.digits(patroToday), px, r.top + 108 * k, patroBig);
        c.drawText(patroDate, px, r.top + 142 * k, cardDim);
        for (int i = 0; i < 3 && patroUpcoming[i] != null; i++) {
            float base = r.top + (186 + 38 * i) * k;
            String name = patroUpcoming[i];
            int fit = patroFest.breakText(name, true, pw - 8 * k, null);
            c.drawText(fit < name.length() ? name.substring(0, Math.max(0, fit - 1)) + "…" : name, px, base, patroFest);
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        aimScroll();
        appFocus.target = !sheetOpen && zone == Z_APPS ? 1 : 0;
        if (zone != Z_APPS) appFocus.snap(0);
        drawerDirty = true;
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        pFocus.target = !sheetOpen && zone == Z_PATRO ? 1 : 0;
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        heroChanged |= aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        spoken.focusMoved();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
        appIndex = Math.min(appIndex, Math.max(0, list.size() - 1));
        requestLayout();
        layoutDrawer();
        drawerDirty = true;
    }

    private void openSheet() {
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        c.drawColor(0xA8000000);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(Ui.tr("Todos os Apps", "All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            drawCard(c, others.get(i).tile, gridCell, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (image != null) roundImage(c, image, cell, r);
        if (f > 0.01f) {
            float bh = base.height();
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = new LinearGradient(0, -bh / 2, 0, 0, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                cardSheenH = bh;
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2);
            sheen.setShader(cardSheen);
            sheen.setAlpha(Math.round(255 * fc));
            c.drawRoundRect(tmp, radius, radius, sheen);
            sheen.setAlpha(255);
            c.restore();
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = HomeApps.shelf(c, true);
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = HomeApps.others(c);
            handler.post(() -> {
                setShelf(s);
                setStatusItems(st);
                setDrawerItems(d);
                if (!sheetOpen) setOthers(o);
            });
        });
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        wake();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (menuOpen) return menuKey(e, down);
        if (reordering) return reorderKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;      // another key while OK is held: no move mode
            handler.removeCallbacks(holdStart);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held on a shelf app (~0.5 s): move mode. A timer, since not every remote repeats a held key, or the
                // long-press repeat, whichever comes first; the release after a hold opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = canMenu();
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        showShelfMenu();
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void back() {
        if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER || zone == WIDGETS || zone == Z_APPS) {
            closeDrawer();
            retarget(false);
        } else if (zone != Z_SHELF) {
            if (zone == Z_PATRO) patroGoToday();
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (drawerItems.isEmpty() && others.isEmpty()) return;
                    float fromX = shelf.isEmpty() ? w / 2f : tileRects[shelfIndex].centerX();
                    openDrawer();
                    if (!others.isEmpty()) {
                        zone = Z_APPS;
                        appIndex = appNearest(fromX);
                    }
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            case Z_APPS: {
                int n = others.size(), row = appIndex / APP_COLS, last = (n - 1) / APP_COLS;
                if (n == 0) { closeDrawer(); break; }
                if (dx != 0) {
                    int next = appIndex + dx;
                    if (next < 0 || next >= n || next / APP_COLS != row) return;
                    appIndex = next;
                } else if (dy < 0) {
                    if (row == 0) closeDrawer(); else appIndex -= APP_COLS;
                } else if (appIndex + APP_COLS < n) {
                    appIndex += APP_COLS;
                } else if (row < last) {
                    appIndex = n - 1;
                } else if (!drawerItems.isEmpty()) {
                    appRect(appIndex, appBox);
                    zone = Z_DRAWER;
                    drawerIndex = nearest(drawerRects, appBox.centerX());
                } else {
                    return;
                }
                appFocus.snap(0);
                appFocus.target = 1;
                break;
            }
            case WIDGETS: {
                List<Integer> on = widgets();
                int at = on.indexOf(widgetIndex);
                if (dy < 0 || at < 0) {
                    zone = Z_DRAWER;
                } else if (dx != 0 && at + dx >= 0 && at + dx < on.size()) {
                    widgetIndex = on.get(at + dx);
                    drawerDirty = true;
                } else {
                    return;
                }
                break;
            }
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    if (others.isEmpty()) {
                        closeDrawer();
                    } else {
                        float fromX = drawerIndex < drawerRects.length ? drawerRects[drawerIndex].centerX() : w / 2f;
                        zone = Z_APPS;
                        int last = (others.size() - 1) / APP_COLS;
                        appIndex = Math.min(others.size() - 1, last * APP_COLS + appCol(fromX));
                        appFocus.snap(0);
                        appFocus.target = 1;
                    }
                } else if (!widgets().isEmpty()) {
                    // Down from the drawer buttons: the weather and NEPSE cards (NEPSE opens full screen).
                    zone = WIDGETS;
                    widgetIndex = widgets().get(0);
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        wake();
        if (reordering) {   // TalkBack's click while moving an app: drop it
            dropReorder();
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (!sheetOpen && zone == WIDGETS) {          // NEPSE opens full screen; the weather card is only read
            if (widgetIndex == 1 && Prefs.nepse()) {
                click.open();
                activity.startActivity(new android.content.Intent(activity, NepseActivity.class));
            }
            return;
        }
        if (!sheetOpen && zone == Z_PATRO) {         // OK on the month header: back to today
            if (patroHead) patroGoToday();
            heroDirty = true;
            click.move();
            return;
        }
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = heroButton;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_APPS) {
            if (others.isEmpty()) return;
            it = others.get(appIndex);
            appRect(appIndex, appBox);
            appBox.offset(0, -homeScroll.value);
            r = appBox;
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < 3) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - 3);
            r = topRects[topIndex];
        } else if (zone == Z_CARDS) {
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = cardRects[cardIndex];
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        if (it.kind == HomeApps.THEME) {
            // Toggle light/dark; back to Auto when that matches what the sun says.
            boolean toDark = !nightTarget;
            String theme = toDark == data.autoNight(System.currentTimeMillis()) ? "auto" : toDark ? "dark" : "light";
            click.move();
            switchTheme(theme, topRects[topIndex].centerX(), topRects[topIndex].centerY());
            return;
        }
        click.open();
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + it.label + ".");
    }

    // MARK: - Hold OK on a shelf app (like Apple TV): Move, Remove from shelf, Uninstall

    private boolean canMenu() {
        if (!sheetOpen && zone == Z_APPS) return appIndex < others.size() && others.get(appIndex).kind == HomeApps.APP && others.get(appIndex).pkg != null;
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size()) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    /** An app you installed (not part of the TV, not this one): the only kind that can be uninstalled. */
    private boolean removable(String pkg) {
        if (pkg.equals(getContext().getPackageName())) return false;
        try {
            int flags = getContext().getPackageManager().getApplicationInfo(pkg, 0).flags;
            return (flags & (android.content.pm.ApplicationInfo.FLAG_SYSTEM | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void showShelfMenu() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (!canMenu()) return;
        wake();
        okHeld = true;   // letting go of OK opens nothing
        final boolean fromApps = zone == Z_APPS;
        final HomeApps.Item it = fromApps ? others.get(appIndex) : shelf.get(shelfIndex);
        final List<String> names = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (fromApps) {
            appRect(appIndex, appBox);
            appBox.offset(0, -homeScroll.value);
            menuAnchor.set(appBox);
            if (HomeApps.shelfPackages(getContext()).size() < HomeApps.MAX_SHELF && !HomeApps.shelfPackages(getContext()).contains(it.pkg)) {
                names.add(Ui.tr("Pôr na prateleira", "Add to shelf"));
                actions.add(() -> addToShelf(it.pkg));
            }
        } else {
            menuAnchor.set(tileRects[shelfIndex]);
            if (shelf.size() >= 2) {
                names.add(Ui.tr("Mover", "Move"));
                actions.add(this::startReorder);
            }
            names.add(Ui.tr("Tirar da prateleira", "Remove from shelf"));
            actions.add(() -> removeFromShelf(it.pkg));
        }
        if (removable(it.pkg)) {
            names.add(Ui.tr("Desinstalar", "Uninstall"));
            actions.add(() -> uninstall(it));
        }
        if (names.isEmpty()) return;
        click.open();
        menuNames = names;
        menuActions = actions;
        menuIndex = 0;
        menuOpen = true;
        menuOkDown = false;
        menuIgnoreOkUp = okDown;   // the press that opened the menu is still down: its release picks nothing
        menuAnim.target = 1;
        menuLayer.setVisibility(VISIBLE);
        menuLayer.invalidate();
    }

    // The menu itself: a dark glass popover above the app, a white pill on the focused row, the rest of the screen dimmed.
    private boolean menuOpen, menuOkDown, menuIgnoreOkUp;
    private int menuIndex;
    private List<String> menuNames = new ArrayList<>();
    private List<Runnable> menuActions = new ArrayList<>();
    private final Spring menuAnim = new Spring(0.28f, 0.85f, 0);
    private final RectF menuAnchor = new RectF();
    private int appIndex;
    private final Spring appFocus = new Spring(0.3f, 0.7f, 0), homeScroll = new Spring(0.42f, 0.92f, 0);
    private Paint appName;
    private final RectF appBox = new RectF();
    private Paint menuText;
    private final Path menuTail = new Path();

    private void closeMenu() {
        menuOpen = false;
        menuAnim.target = 0;
        menuLayer.invalidate();
    }

    private boolean menuKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down && menuIndex > 0) { menuIndex--; click.move(); menuLayer.invalidate(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && menuIndex < menuNames.size() - 1) { menuIndex++; click.move(); menuLayer.invalidate(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0 && !menuIgnoreOkUp) menuOkDown = true;
                } else if (menuIgnoreOkUp) {
                    menuIgnoreOkUp = false;
                    okDown = okHeld = false;
                } else if (menuOkDown) {
                    menuOkDown = false;
                    Runnable run = menuActions.get(menuIndex);
                    closeMenu();
                    run.run();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) closeMenu();
                return true;
            default:
                return true;
        }
    }

    private void drawShelfMenu(Canvas c) {
        float v = Ui.clamp(menuAnim.value);
        if (v < 0.01f || menuAnchor.isEmpty() || menuNames.isEmpty()) return;
        if (menuText == null) menuText = Ui.text(Ui.medium, 27 * k, 0xFFFFFFFF);
        RectF tile = menuAnchor;
        fill.setShader(null);
        c.save();
        c.clipOutRect(tile);   // the app stays bright
        fill.setColor(Ui.alpha(0xFF000000, 0.4f * v));
        c.drawRect(screen, fill);
        c.restore();
        int n = menuNames.size();
        float w = 360 * k, rowH = 66 * k, pad = 12 * k, h = n * rowH + 2 * pad;
        float x = Math.max(40 * k, Math.min(this.w - 40 * k - w, tile.centerX() - w / 2)), y = tile.top - h - 34 * k;
        float pivotX = tile.centerX(), pivotY = y + h + 34 * k;
        c.save();
        float sc = 0.9f + 0.1f * v;
        c.scale(sc, sc, pivotX, pivotY);
        tmp.set(x, y, x + w, y + h);
        shadow.setColor(Ui.alpha(0xFF000000, 0.45f * v));
        shadows.draw(c, tmp, 28 * k, SHADOW_BLUR * k, shadow);
        fill.setColor(Ui.alpha(0xFF2A2A2E, 0.96f * v));
        c.drawRoundRect(tmp, 28 * k, 28 * k, fill);
        menuTail.reset();
        float tx = Math.max(x + 40 * k, Math.min(x + w - 40 * k, tile.centerX()));
        menuTail.moveTo(tx - 16 * k, y + h - 1);
        menuTail.lineTo(tx + 16 * k, y + h - 1);
        menuTail.lineTo(tx, y + h + 16 * k);
        menuTail.close();
        c.drawPath(menuTail, fill);
        for (int i = 0; i < n; i++) {
            float top = y + pad + i * rowH;
            boolean focus = i == menuIndex;
            if (focus) {
                tmp.set(x + 10 * k, top + 4 * k, x + w - 10 * k, top + rowH - 4 * k);
                fill.setColor(Ui.alpha(0xFFF5F5F7, v));
                c.drawRoundRect(tmp, 20 * k, 20 * k, fill);
            }
            boolean danger = menuNames.get(i).equals(Ui.tr("Desinstalar", "Uninstall"));
            menuText.setColor(Ui.alpha(focus ? 0xFF1C1C1E : danger ? 0xFFFF6961 : 0xFFF5F5F7, v));
            c.drawText(menuNames.get(i), x + 36 * k, top + rowH / 2 + Ui.halfCap(menuText), menuText);
        }
        c.restore();
    }

    private void addToShelf(String pkg) {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            List<String> all = new ArrayList<>(HomeApps.shelfPackages(c));
            if (!all.contains(pkg)) all.add(pkg);
            final List<String> save = all;
            handler.post(() -> {
                Prefs.setShelf(save);
                reload();
            });
        });
    }

    // MARK: - All the apps as rows of square icons under the shelf (Apple TV)

    private float appsHeight() {
        return others.isEmpty() ? 0 : ((others.size() + APP_COLS - 1) / APP_COLS) * APP_ROW + 30;
    }

    /** The rows use the shelf's own tile size and gap, so they line up with it (7 to a row). */
    private float appTileW() {
        float gap = TILE_GAP * k;
        return Math.min(L_TILE_H * 16f / 9 * k, ((CONTENT_R - MARGIN) * k - (APP_COLS - 1) * gap) / APP_COLS);
    }

    /** Where app i sits in the drawer page, at rest (before the page scrolls). */
    private void appRect(int i, RectF out) {
        float gap = TILE_GAP * k, tw = appTileW(), th = tw * 9f / 16;
        float left = (w - (APP_COLS * tw + (APP_COLS - 1) * gap)) / 2f + (i % APP_COLS) * (tw + gap);
        float top = (APPS_TOP + (i / APP_COLS) * APP_ROW) * k;
        out.set(left, top, left + tw, top + th);
    }

    private int appCol(float x) {
        float gap = TILE_GAP * k, tw = appTileW();
        float left = (w - (APP_COLS * tw + (APP_COLS - 1) * gap)) / 2f;
        return Math.max(0, Math.min(APP_COLS - 1, Math.round((x - left - tw / 2) / (tw + gap))));
    }

    private int appNearest(float x) {
        return Math.min(others.size() - 1, appCol(x));
    }

    /** How far the page scrolls so that what has focus (a row of apps, the shortcuts, the cards) is on screen. */
    private void aimScroll() {
        float bottom = 0, limit = 1020 * k, extra = appsHeight();
        if (drawerOpen && !sheetOpen) {
            if (zone == Z_APPS) bottom = (APPS_TOP + (appIndex / APP_COLS) * APP_ROW) * k + appTileW() * 9f / 16 + 64 * k;
            else if (zone == Z_DRAWER) bottom = (ROW_TOP + extra + ROW_H + 50) * k;
            else if (zone == WIDGETS) bottom = (CARDS_TOP + extra + CARDS_H + 30) * k;
        }
        homeScroll.target = Math.max(0, bottom - limit);
        motionDirty = true;
    }

    private void drawApps(Canvas c) {
        int n = others.size();
        if (n == 0) return;
        if (appName == null) {
            appName = Ui.text(Ui.medium, 24 * k, 0xFFFFFFFF);
            appName.setTextAlign(Paint.Align.CENTER);
        }
        int focused = !sheetOpen && zone == Z_APPS ? appIndex : -1;
        float f = Ui.clamp(appFocus.value);
        for (int i = 0; i < n; i++) if (i != focused) drawApp(c, i, 0);
        if (focused >= 0) drawApp(c, focused, f);
    }

    private void drawApp(Canvas c, int i, float f) {
        appRect(i, appBox);
        float cx = appBox.centerX(), cy = appBox.centerY(), s = 1 + 0.14f * f;
        float hw = appBox.width() / 2 * s, hh = appBox.height() / 2 * s, radius = TILE_RADIUS * k * s;
        RectF dst = tmp;
        dst.set(cx - hw, cy - hh - 4 * k * f, cx + hw, cy + hh - 4 * k * f);
        RectF sh = cell;
        sh.set(dst);
        sh.offset(0, (4 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * f));
        shadows.draw(c, sh, radius, DEEP_BLUR * k, shadowDeep);
        HomeApps.Item it = others.get(i);
        if (it.tile != null) {
            roundImage(c, it.tile, dst, radius);   // the same card the shelf draws
        } else {
            fill.setShader(null);
            fill.setColor(0xFF2C2C2E);
            c.drawRoundRect(dst, radius, radius, fill);
        }
        if (f > 0.02f) {
            appName.setColor(Ui.alpha(tone(L_INK, 0xFFFFFFFF), f));
            String name = it.label.length() > 22 ? it.label.substring(0, 21) + "…" : it.label;
            c.drawText(name, cx, dst.bottom + 38 * k, appName);
        }
    }

    private void removeFromShelf(String pkg) {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            List<String> all = new ArrayList<>(HomeApps.shelfPackages(c));
            all.remove(pkg);
            final List<String> save = all;
            handler.post(() -> {
                Prefs.setShelf(save);
                reload();
            });
        });
    }

    /** The system asks to confirm (that dialog is the user's to accept); the shelf refreshes when it is back. */
    private void uninstall(HomeApps.Item it) {
        try {
            activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_DELETE, android.net.Uri.parse("package:" + it.pkg)));
            handler.postDelayed(this::reload, 6000);
        } catch (RuntimeException e) {
            showToast(Ui.tr("Não deu para desinstalar ", "Couldn't uninstall ") + it.label + ".");
        }
    }

    // MARK: - Moving apps on the shelf

    private boolean canReorder() {
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 2) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (reordering || !canReorder()) return;
        wake();
        reordering = reorderAnim = okHeld = true;
        reorderOrigin = new ArrayList<>(shelf);
        liftIndex = shelfIndex;
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        click.open();
        showToast(Ui.tr("← → para mover · OK para soltar · Voltar para cancelar",
                "← → to move · OK to drop · Back to cancel"));
        retarget(false);    // TalkBack reads "Moving <app>, position i of n"
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that lifted the app is still held: its repeats and release do nothing; a new press drops it.
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    dropReorder();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        cancelReorder(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
    private void shiftReorder(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size()) return;
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = tileRects[i].left - tileRects[j].left;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        if (tileShaders.length == shelf.size()) {
            LinearGradient g = tileShaders[i];
            tileShaders[i] = tileShaders[j];
            tileShaders[j] = g;
        }
        shelfIndex = liftIndex = j;
        reorderAnim = true;
        wake();
        retarget(false);
        click.move();
    }

    /** OK: drops the app where it is and saves the shelf's new order. */
    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        moveLift.target = 0;
        boolean changed = false;
        for (int i = 0; i < shelf.size() && !changed; i++) changed = shelf.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget(false);
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) order.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            // The saved list may hold more than the shelf shows (apps not installed, past the limit): the shown ones
            // take their new order in the same slots, the others stay where they were.
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> Prefs.setShelf(save));
        });
    }

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    private void cancelReorder(boolean snap) {
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            moveLift.target = 0;
            int n = shelf.size();
            if (reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                LinearGradient[] shaders = tileShaders.length == n ? new LinearGradient[n] : tileShaders;
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                    if (shaders != tileShaders) shaders[a] = tileShaders[b];
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                tileShaders = shaders;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderOrigin = null;
            retarget(snap);
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        return moving;
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** The drawer's weather (0) and quotes (1) cards: read by TalkBack, never focused by the remote. */
    private static final int GRID = 9, WIDGETS = 8;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        for (int i = picksOn() ? 0 : 3; i < 3 + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        for (int i = 0; i < Math.min(3, cards.size()); i++) out.add(a11yId(Z_CARDS, i));
        for (int i = 0; i < shelf.size(); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
        if (drawerOpen && Prefs.weather()) out.add(a11yId(WIDGETS, 0));
        if (drawerOpen && Prefs.quotes() && HomeData.active().length > 0) out.add(a11yId(WIDGETS, 1));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + (i + 1) + Ui.tr(" de ", " of ") + others.size();
            case Z_TOP:
                if (i >= 3) return label(lightButtons, i - 3);
                return tabs()[i] + Ui.tr(", aba ", ", tab ") + (i + 1) + Ui.tr(" de 3", " of 3");
            case Z_CARDS: return i < cards.size() ? pickText(cards.get(i)) : "";
            case Z_SHELF:
                if (reordering && i == shelfIndex) {
                    return Ui.tr("Movendo ", "Moving ", "Moviendo ") + label(shelf, i)
                            + Ui.tr(", posição ", ", position ", ", posición ") + (i + 1) + Ui.tr(" de ", " of ") + shelf.size();
                }
                return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case WIDGETS: return i == 0 ? weatherText() : quotesText();
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= 3 + lightButtons.size()) return false;
                a11yRect.set(topRects[i]);
                dy = (i < 3 ? heroLayer : status).getTranslationY();
                break;
            case Z_CARDS:
                if (i >= Math.min(3, cards.size())) return false;
                a11yRect.set(cardRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            case WIDGETS:
                if (!drawerOpen) return false;
                a11yRect.set(i == 0 ? weatherCard : quotesCard);
                dy = 0;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cardIndex < cards.size() ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            case WIDGETS: return a11yId(WIDGETS, widgetIndex);
            default: return -1;
        }
    }

    /** The drawer's widgets that are on (0 weather, 1 quotes), with the drawer open. */
    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (!drawerOpen) return out;
        if (Prefs.weather()) out.add(0);
        if (Prefs.nepse()) out.add(1);
        return out;
    }

    /** A spoken-feedback service (TalkBack) is on. */
    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (sheetOpen || reordering) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case WIDGETS:
                if (!widgets().contains(i)) return;
                widgetIndex = i;
                break;
            case Z_TOP:
                if (i >= 3 + lightButtons.size() || (i < 3 && !picksOn())) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < 3) selectTab(i);
                break;
            case Z_CARDS:
                if (i >= cards.size()) return;
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    /** The weather card as TalkBack reads it: what it shows, in the same words and units. */
    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? Ui.tr("Clima", "Weather") : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return s.append(". ").append(Ui.tr("Sem dados do clima agora.", "No weather data right now.")).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay))
                .append(Ui.tr(". Máxima ", ". High ")).append(HomeData.degrees(wx.max))
                .append(Ui.tr(", mínima ", ", low ")).append(HomeData.degrees(wx.min)).append('.');
        int hours = Math.min(6, wx.hours.size());
        if (hours > 0) s.append(Ui.tr(" Próximas horas: ", " Next hours: "));
        for (int i = 0; i < hours; i++) {
            s.append(HomeData.hour(wx.hours.get(i))).append(' ').append(HomeData.degrees(wx.hourTemp.get(i))).append(i < hours - 1 ? ", " : ".");
        }
        if (wx.sunrise != 0) s.append(Ui.tr(" Nascer do sol ", " Sunrise ")).append(Ui.time(wx.sunrise)).append('.');
        if (wx.sunset != 0) s.append(Ui.tr(" Pôr do sol ", " Sunset ")).append(Ui.time(wx.sunset)).append('.');
        return s.toString();
    }

    /** The NEPSE card as TalkBack reads it: the index and its change. */
    private String quotesText() {
        Nepse.Data d = Nepse.data();
        if (d == null) return "NEPSE";
        return "NEPSE " + String.format(java.util.Locale.US, "%,.2f", d.index) + ", " + String.format(java.util.Locale.US, "%+.2f", d.change)
                + " (" + String.format(java.util.Locale.US, "%+.2f", d.pct) + "%).";
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        float pad = 26 * k, tabTop = L_TABBAR_TOP * k, tabBottom = (L_TABBAR_TOP + L_TABBAR_H) * k;
        float x = MARGIN * k - pad;
        for (int i = 0; i < 3; i++) {
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom);
            x = topRects[i].right + 4 * k;
        }
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[3 + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + L_BUTTON_GAP * k;
        }
        float top = L_CARDS_TOP * k, ch = L_CARD_H * k, g = L_CARD_GAP * k, left = MARGIN * k, tall = L_TALL_W * k;
        float wide = (CONTENT_R - MARGIN) * k - 2 * tall - 2 * g;
        cardRects[0].set(left, top, left + wide, top + ch);
        cardRects[1].set(cardRects[0].right + g, top, cardRects[0].right + g + tall, top + ch);
        cardRects[2].set(cardRects[1].right + g, top, cardRects[1].right + g + tall, top + ch);
        heroDirty = statusDirty = true;
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the first one. */
    private void updateCards() {
        List<Recs.Pick> list = recs.now(TAB_KIND[tab]);
        if (!samePicks(list, cards)) {
            cards = list;
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        for (Recs.Pick p : recs.wanted()) recs.backdrop(p);   // all three tabs preloaded: switching tabs is instant
        Backdrop b = cards.isEmpty() ? null : recs.backdrop(cards.get(0));
        if (cards.isEmpty() || b != null) showBackdrop(b);
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        updateCards();
        heroDirty = true;
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * Ui.clamp(drawer.value), blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = new LinearGradient(0, 0, 0, h, 0xFFF5F5F7, 0xFFE2E2E7, Shader.TileMode.CLAMP);
            if (nightGradient == null) nightGradient = new LinearGradient(0, 0, 0, h, 0xFF1C1C1E, 0xFF0B0B0D, Shader.TileMode.CLAMP);
            fill.setColorFilter(v);
            fill.setColor(0xFF000000);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    private void drawLightTop(Canvas c) {
        applyTone();
        if (!picksOn()) {
            drawClock(c);
            if (patroOn()) drawPatroHome(c);
            if (Prefs.nepse()) drawNepseTicker(c);
            return;
        }
        String hello = Ui.tr("Para você", "For You");
        c.drawText(hello, MARGIN * k - Ui.leftBearing(lTitle, hello), L_TITLE_BASE * k, lTitle);
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // the white pill with a shadow and grows a little (the tvOS focus).
        float focus = Ui.clamp(topFocus[tab].value);
        for (int i = 0; i < 3; i++) {
            RectF r = topRects[i];
            if (i == tab && focus > 0.01f) {
                float s = 1 + 0.05f * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                if (focus > 0.01f) {
                    tmp.set(cell);
                    tmp.offset(0, 8 * k * focus);
                    shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.4f) * focus));
                    shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
                }
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, focus));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            Paint p = i == tab ? lTabOn : lTab;
            if (i == tab) lTabOn.setColor(blend(tone(L_INK, WHITE), L_INK, focus));   // always dark on the white pill
            c.drawText(tabs()[i], r.centerX() - p.measureText(tabs()[i]) / 2, r.centerY() + Ui.halfCap(p), p);
        }
        int top = -1;
        for (int i = 0; i < 3; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        for (int i = 0; i < 3; i++) if (i != top) drawLightCard(c, i);
        if (top >= 0) drawLightCard(c, top);
        if (HomeData.coinCount() > 0) drawQuotes(c);
    }

    private boolean picksOn() {
        return Recs.hasKey(getContext());
    }

    private void drawClock(Canvas c) {
        float x = MARGIN * k;
        c.drawText(dateText, x - Ui.leftBearing(lDate, dateText), L_DATE_BASE * k, lDate);
        c.drawText(clockText, x - Ui.leftBearing(lClock, clockText), L_CLOCK_BASE * k, lClock);
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) return;
        if (data.weatherAt != weatherLineAt || Prefs.version != weatherLinePrefs || !Ui.language.equals(weatherLineLanguage)) {
            weatherLineAt = data.weatherAt;
            weatherLinePrefs = Prefs.version;
            weatherLineLanguage = Ui.language;
            weatherLine = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "   "
                    + (Ui.ru ? "Макс. " + HomeData.degrees(wx.max) + "  Мин. " + HomeData.degrees(wx.min)
                    : Ui.en ? "H:" + HomeData.degrees(wx.max) + "  L:" + HomeData.degrees(wx.min)
                    : Ui.de ? "H:" + HomeData.degrees(wx.max) + "  T:" + HomeData.degrees(wx.min)
                    : "Máx. " + HomeData.degrees(wx.max) + "  Mín. " + HomeData.degrees(wx.min));
        }
        float size = 40 * k, base = L_WEATHER_BASE * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(lWeather), size, lWeather.getColor());
        c.drawText(weatherLine, x + sw + 16 * k, base, lWeather);
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private void drawLightCard(Canvas c, int i) {
        RectF base = cardRects[i];
        Recs.Pick p = i < cards.size() ? cards.get(i) : null;
        float v = cardFocus[i].value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        Backdrop b = p == null ? null : recs.backdrop(p);
        Bitmap img = b == null ? null : (i == 0 ? b.wide : b.tall);
        if (img != null) {
            roundImage(c, img, cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f) {
            if (lightSheens.length != cardRects.length) {
                lightSheens = new LinearGradient[cardRects.length];
                lightRims = new LinearGradient[cardRects.length];
                lightShaderW = new float[cardRects.length];
            }
            if (lightSheens[i] == null || lightShaderW[i] != bw) {
                lightSheens[i] = new LinearGradient(-bw / 2, -bh / 2, 0, bh / 2, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                lightRims[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
                lightShaderW[i] = bw;
            }
            int alpha = Math.round(255 * f);
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            sheen.setShader(lightSheens[i]);
            sheen.setAlpha(alpha);
            c.drawRoundRect(tmp, r0, r0, sheen);
            sheen.setAlpha(255);
            rim.setShader(lightRims[i]);
            rim.setAlpha(alpha);
            rim.setStrokeWidth(2.5f * k / s);
            c.drawRoundRect(tmp, r0, r0, rim);
            rim.setStrokeWidth(2.5f * k);
            rim.setAlpha(255);
            c.restore();
        }
        if (p == null) return;
        fitPaint.set(lCardTitle);
        c.drawText(TextUtils.ellipsize(p.title, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 46 * k, lCardTitle);
        if (i == 0 && p.meta != null && !p.meta.isEmpty()) {
            fitPaint.set(lCardMeta);
            c.drawText(TextUtils.ellipsize(p.meta, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 80 * k, lCardMeta);
        }
    }

    /**
     * Live TV, HDMI Inputs and Settings: icon-only glass circles (light by day, smoked at night); the focused one turns
     * white with a dark icon (the tvOS focus) and its name appears below it.
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        for (int i = 0; i < lightButtons.size(); i++) {
            RectF r = topRects[3 + i];
            float f = Ui.clamp(topFocus[3 + i].value), s = 1 + 0.1f * f, rad = r.width() / 2 * s;
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                    tone(0.6f, 0.45f), 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
            }
            HomeApps.Item btn = lightButtons.get(i);
            int ink = blend(tone(L_INK, 0xEBFFFFFF), L_INK, f);
            boolean themeBtn = btn.kind == HomeApps.THEME;
            if (btn.kind == HomeApps.WIFI) drawWifi(c, cell.centerX(), cell.centerY(), 27 * k * s, ink);
            else Symbols.draw(getContext(), c, themeBtn ? (nightTarget ? "sunny" : "bedtime") : btn.symbol, cell.centerX(), cell.centerY(), 27 * k * s, ink);
            // Short names under every button (brighter and larger on the focused one).
            String label;
            switch (btn.kind) {
                case HomeApps.LIVE_TV: label = Ui.tr("TV aberta", "Live TV"); break;
                case HomeApps.INPUTS: label = "HDMI"; break;
                case HomeApps.WIFI: label = "Wi-Fi"; break;
                case HomeApps.THEME: label = nightTarget ? Ui.tr("Claro", "Light") : Ui.tr("Escuro", "Dark"); break;
                default: label = Ui.tr("Ajustes", "Settings"); break;
            }
            float oldSize = lTip.getTextSize();
            lTip.setTextSize((19 + 4 * f) * k);
            lTip.setAlpha(Math.round(255 * (0.55f + 0.45f * f)));
            float half = lTip.measureText(label) / 2;
            c.drawText(label, Math.min(r.centerX(), CONTENT_R * k - half), r.bottom + (34 + 4 * f) * k, lTip);
            lTip.setTextSize(oldSize);
            lTip.setAlpha(255);
        }
    }

    // MARK: - Light/dark switch: the new look spreads from the button over a snapshot of the old one

    private Bitmap revealShot;
    private float revealX, revealY;
    private long revealAt;
    private static final long REVEAL_MS = 750;
    private final Path revealPath = new Path();

    private void switchTheme(String theme, float cx, float cy) {
        if (revealShot != null || w <= 0 || h <= 0) {
            Prefs.setTheme(theme);
            checkNight(true);
            return;
        }
        Bitmap shot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(activity.getWindow(), new Rect(0, 0, w, h), shot, result -> {
                Prefs.setTheme(theme);
                if (result == PixelCopy.SUCCESS) {
                    revealShot = shot;
                    revealX = cx;
                    revealY = cy;
                    revealAt = SystemClock.uptimeMillis();
                    checkNight(false);   // the real screen changes at once, under the old snapshot
                    wake();
                } else {
                    checkNight(true);
                }
            }, handler);
        } catch (RuntimeException e) {   // no window to copy yet
            Prefs.setTheme(theme);
            checkNight(true);
        }
    }

    @Override protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        if (revealShot == null) return;
        float t = Ui.clamp((SystemClock.uptimeMillis() - revealAt) / (float) REVEAL_MS);
        if (t >= 1) {
            revealShot = null;
            return;
        }
        float e = t * t * (3 - 2 * t);
        float far = (float) Math.hypot(Math.max(revealX, w - revealX), Math.max(revealY, h - revealY));
        float r = far * e;
        revealPath.reset();
        revealPath.addCircle(revealX, revealY, r, Path.Direction.CW);
        c.save();
        c.clipOutPath(revealPath);
        c.drawBitmap(revealShot, 0, 0, null);
        c.restore();
        phStrokeInit();
        phStroke.setStrokeWidth(3 * k);
        phStroke.setColor(Ui.alpha(0xFFFFFFFF, 0.35f * (1 - t)));
        c.drawCircle(revealX, revealY, r, phStroke);
    }

    /** Wi-Fi: a dot and three arcs (Material Symbols has no PNG for it here). */
    private void drawWifi(Canvas c, float cx, float cy, float size, int color) {
        phStrokeInit();
        phStroke.setColor(color);
        phStroke.setStrokeWidth(size * 0.11f);
        phStroke.setStrokeCap(Paint.Cap.ROUND);
        float bx = cx, by = cy + size * 0.42f;
        for (int i = 1; i <= 3; i++) {
            float rr = size * 0.30f * i;
            tmp.set(bx - rr, by - rr, bx + rr, by + rr);
            c.drawArc(tmp, 225, 90, false, phStroke);
        }
        phStroke.setStrokeCap(Paint.Cap.BUTT);
        fill.setShader(null);
        fill.setColor(color);
        c.drawCircle(bx, by, size * 0.08f, fill);
    }

    private void phStrokeInit() {
        if (phStroke != null) return;
        phStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        phStroke.setStyle(Paint.Style.STROKE);
    }

    // MARK: - Patro on the home screen (without picks): month grid, day cursor, festival list

    private int patroMi, patroSel = 1, patroDir;
    // Animation: the cursor glides between days, the grid slides in on a month change, the card lifts when focused.
    private final Spring pcX = new Spring(0.22f, 0.8f, 0), pcY = new Spring(0.22f, 0.8f, 0),
            pFocus = new Spring(0.3f, 0.85f, 0), pMonth = new Spring(0.35f, 1f, 1);
    private int patroShownMi = -1;

    private boolean stepPatro(float dt) {
        return pcX.step(dt) | pcY.step(dt) | pFocus.step(dt) | pMonth.step(dt);
    }

    /** Aims the cursor at the selected day; a new month snaps it and slides the grid in from the side. */
    private void patroAim() {
        List<Patro.Month> all = Patro.months(getContext());
        if (all.isEmpty()) return;
        Patro.Month m = all.get(Math.min(patroMi, all.size() - 1));
        int idx = m.start.getDayOfWeek().getValue() % 7 + Math.min(patroSel, m.length) - 1;
        if (patroMi != patroShownMi) {
            patroDir = patroShownMi < 0 || patroMi == patroShownMi ? 0 : patroMi > patroShownMi ? 1 : -1;
            patroShownMi = patroMi;
            pcX.snap(idx % 7);
            pcY.snap(idx / 7);
            pMonth.snap(0);
            pMonth.target = 1;
        } else {
            pcX.target = idx % 7;
            pcY.target = idx / 7;
        }
        heroDirty = true;
    }

    private boolean patroHead;
    private Paint phTitle, phDim, phNum, phSmall, phBig, phLine, phRow, phStroke;

    private boolean patroOn() {
        if (picksOn() || !Prefs.quotes()) return false;
        refreshPatro();
        return patroMonth != null;
    }

    private void patroGoToday() {
        refreshPatro();
        List<Patro.Month> all = Patro.months(getContext());
        patroMi = Math.max(0, all.indexOf(patroMonth));
        patroSel = patroToday;
        patroHead = false;
        patroAim();
    }

    private void patroMove(int dx, int dy) {
        List<Patro.Month> all = Patro.months(getContext());
        if (all.isEmpty()) { zone = Z_SHELF; return; }
        Patro.Month m = all.get(patroMi);
        if (patroHead) {
            if (dx != 0) {
                int n = patroMi + dx;
                if (n < 0 || n >= all.size()) return;
                patroMi = n;
                patroSel = Math.min(patroSel, all.get(n).length);
            } else if (dy < 0) {
                zone = Z_TOP;
                topIndex = 3;
            } else {
                patroHead = false;
            }
        } else if (dx != 0) {
            int d = patroSel + dx;
            if (d < 1) {
                if (patroMi == 0) return;
                d = all.get(--patroMi).length;
            } else if (d > m.length) {
                if (patroMi == all.size() - 1) return;
                patroMi++;
                d = 1;
            }
            patroSel = d;
        } else if (dy < 0) {
            if (patroSel - 7 < 1) patroHead = true; else patroSel -= 7;
        } else if (patroSel + 7 > m.length) {
            zone = Z_SHELF;
            if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, w * 0.65f);
        } else {
            patroSel += 7;
        }
        patroAim();
    }

    private void drawPatroHome(Canvas c) {
        if (phTitle == null) {
            phTitle = Ui.text(Ui.semibold, 42 * k, L_INK);
            phDim = Ui.text(Ui.medium, 22 * k, L_DIM);
            phNum = Ui.numbers(Ui.medium, 30 * k, L_INK);
            phNum.setTextAlign(Paint.Align.CENTER);
            phSmall = Ui.numbers(Ui.regular, 15 * k, L_DIM);
            phSmall.setTextAlign(Paint.Align.RIGHT);
            phBig = Ui.numbers(Ui.medium, 84 * k, L_INK);
            phLine = Ui.text(Ui.medium, 26 * k, L_INK);
            phRow = Ui.text(Ui.medium, 22 * k, L_INK);
            phStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            phStroke.setStyle(Paint.Style.STROKE);
        }
        List<Patro.Month> all = Patro.months(getContext());
        if (all.isEmpty()) return;
        boolean focus = zone == Z_PATRO, cursor = focus && !patroHead;
        Patro.Month m = focus || patroMonth == null ? all.get(Math.min(patroMi, all.size() - 1)) : patroMonth;
        int ink = tone(L_INK, WHITE), dim = tone(L_DIM, 0xADFFFFFF);
        phTitle.setColor(ink); phLine.setColor(ink); phRow.setColor(ink); phBig.setColor(ink); phDim.setColor(dim); phSmall.setColor(dim);
        RectF card = tmp2;
        card.set(700 * k, 236 * k, CONTENT_R * k, 780 * k);
        float lift = Ui.clamp(pFocus.value);
        c.save();
        c.scale(1 + 0.014f * lift, 1 + 0.014f * lift, card.centerX(), card.centerY());
        shadow.setColor(Ui.alpha(0xFF000000, tone(0.10f, 0.35f) + (focus ? 0.08f : 0)));
        shadows.draw(c, card, 30 * k, SHADOW_BLUR * k, shadow);
        fill.setShader(null);
        fill.setColor(tone(0xB8FFFFFF, 0x24FFFFFF));
        c.drawRoundRect(card, 30 * k, 30 * k, fill);
        if (focus) {
            phStroke.setStrokeWidth(3 * k);
            phStroke.setColor(tone(0xFFFFFFFF, 0x66FFFFFF));
            c.drawRoundRect(card, 30 * k, 30 * k, phStroke);
        }
        float pad = 36 * k, gx = card.left + pad, gw = 560 * k, cw = gw / 7;
        // Header: month name; focused, a pill with arrows (Left/Right: month, OK: today).
        String title = Patro.MONTHS[m.month - 1] + " " + Patro.digits(m.year);
        float ty = card.top + 74 * k;
        if (focus && patroHead) {
            tmp.set(gx - 14 * k, ty - 52 * k, gx + gw, ty + 46 * k);
            fill.setColor(tone(0xFFFFFFFF, 0x33FFFFFF));
            c.drawRoundRect(tmp, 36 * k, 36 * k, fill);
            phDim.setTextAlign(Paint.Align.LEFT);
            c.drawText("‹", gx + 4 * k, ty - 4 * k, phTitle);
            c.drawText("›", gx + gw - 32 * k, ty - 4 * k, phTitle);
            c.drawText(title, gx + 52 * k, ty - 4 * k, phTitle);
        } else {
            c.drawText(title, gx + 52 * k, ty - 4 * k, phTitle);
        }
        phDim.setTextAlign(Paint.Align.LEFT);
        String sub = Patro.MONTHS_EN[m.month - 1] + " " + m.year + " · " + m.start.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)
                + "–" + m.date(m.length).getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH) + " " + m.date(m.length).getYear();
        c.drawText(sub, gx + 52 * k, ty + 28 * k, phDim);
        // Grid
        phDim.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < 7; i++) {
            phDim.setColor(i == 6 ? RED : dim);
            c.drawText(Patro.DAYS[i], gx + cw * (i + 0.5f), card.top + 148 * k, phDim);
        }
        phDim.setTextAlign(Paint.Align.LEFT);
        phDim.setColor(dim);
        float rowH = 58 * k, top = card.top + 168 * k;
        int col = m.start.getDayOfWeek().getValue() % 7;
        boolean here = m == patroMonth;
        float slide = Ui.clamp(pMonth.value);
        boolean sliding = slide < 0.995f;
        if (sliding) {
            c.saveLayerAlpha(gx - 40 * k, top - 10 * k, gx + gw + 40 * k, top + rowH * 6 + 10 * k, Math.round(255 * slide));
            c.translate((1 - slide) * 70 * k * patroDir, 0);
        }
        for (int d = 1; d <= m.length; d++, col++) {
            float cx = gx + cw * (col % 7 + 0.5f), cy = top + rowH * (col / 7) + rowH / 2;
            boolean red = col % 7 == 6 || m.holiday[d], today = here && d == patroToday;
            tmp.set(cx - 32 * k, cy - 26 * k, cx + 32 * k, cy + 26 * k);
            if (today) {
                fill.setColor(tone(0xFF1C1C1E, 0xF2FFFFFF));
                c.drawRoundRect(tmp, 18 * k, 18 * k, fill);
            }
            phNum.setColor(today ? tone(WHITE, 0xFF1C1C1E) : red ? RED : ink);
            c.drawText(Patro.digits(d), cx - 4 * k, cy + 8 * k, phNum);
            phSmall.setColor(today ? tone(0xB3FFFFFF, 0x991C1C1E) : dim);
            c.drawText(String.valueOf(m.date(d).getDayOfMonth()), cx + 28 * k, cy + 21 * k, phSmall);
            if (!m.fest[d].isEmpty()) {
                fill.setColor(today ? tone(WHITE, 0xFF1C1C1E) : red ? RED : dim);
                c.drawCircle(cx - 4 * k, cy + 19 * k, 3 * k, fill);
            }
        }
        if (cursor) {   // glides from day to day
            float rx = gx + cw * (pcX.value + 0.5f), ry = top + rowH * pcY.value + rowH / 2;
            tmp.set(rx - 32 * k, ry - 26 * k, rx + 32 * k, ry + 26 * k);
            phStroke.setStrokeWidth(4 * k);
            phStroke.setColor(tone(0xFF0A84FF, 0xFF64A8FF));
            c.drawRoundRect(tmp, 18 * k, 18 * k, phStroke);
        }
        if (sliding) c.restore();
        // Right: the selected day and this month's festivals.
        int sel = focus ? Math.min(patroSel, m.length) : m == patroMonth ? patroToday : 1;
        float px = gx + gw + 56 * k, pw = card.right - pad - px;
        c.drawText(Patro.digits(sel), px, card.top + 118 * k, phBig);
        phDim.setColor(dim);
        float bigW = phBig.measureText(Patro.digits(sel));
        c.drawText(Patro.MONTHS[m.month - 1], px + bigW + 16 * k, card.top + 80 * k, phLine);
        c.drawText(m.date(sel).format(AD), px + bigW + 16 * k, card.top + 114 * k, phDim);
        float y = card.top + 168 * k;
        String[] names = m.fest[sel].isEmpty() ? new String[0] : m.fest[sel].split(";");
        if (m.holiday[sel] && names.length == 0) names = new String[] {Ui.tr("Feriado", "Holiday")};
        for (int i = 0; i < names.length && i < 2; i++, y += 36 * k) c.drawText(fit(phLine, names[i], pw), px, y, phLine);
        if (names.length == 0) c.drawText(Ui.tr("Sem eventos", "No events"), px, y, phDim);
        y = Math.max(y, card.top + 240 * k) + 14 * k;
        tmp.set(px, y - 8 * k, px + pw, y - 6 * k);
        fill.setColor(tone(0x22000000, 0x33FFFFFF));
        c.drawRect(tmp, fill);
        y += 34 * k;
        int shown = 0;
        for (int d = 1; d <= m.length && shown < 6; d++) {
            if (m.fest[d].isEmpty()) continue;
            boolean on = d == sel;
            phRow.setColor(on ? tone(0xFF0A84FF, 0xFF64A8FF) : m.holiday[d] ? RED : ink);
            String label = Patro.digits(d) + "  " + m.fest[d].split(";")[0];
            c.drawText(fit(phRow, label, pw), px, y, phRow);
            y += 34 * k;
            shown++;
        }
        c.restore();
    }

    private final RectF tmp2 = new RectF();

    /** The text cut with an ellipsis to fit the width. */
    private static String fit(Paint p, String text, float width) {
        int n = p.breakText(text, true, width, null);
        return n >= text.length() ? text : text.substring(0, Math.max(0, n - 1)) + "…";
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                // Up from an empty shelf too: move()'s older zones (the hero, the corner capsule) aren't drawn any more.
                if (dy >= 0) return false;
                if (patroOn()) {
                    zone = Z_PATRO;
                    patroGoToday();
                } else if (cards.isEmpty()) {
                    zone = Z_TOP;
                    topIndex = picksOn() ? tab : 3;
                } else {
                    zone = Z_CARDS;
                    cardIndex = nearest(cardRects, cards.size(), shelf.isEmpty() ? w / 2f : tileRects[shelfIndex].centerX());
                }
                break;
            case Z_CARDS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(cards.size() - 1, cardIndex + dx));
                    if (next == cardIndex) return true;
                    cardIndex = next;
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = tab;     // as on Apple TV: moving up to the tabs focuses the selected tab
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, cardRects[cardIndex].centerX());
                }
                break;
            case Z_TOP:
                if (dx != 0) {
                    int next = Math.max(picksOn() ? 0 : 3, Math.min(2 + lightButtons.size(), topIndex + dx));
                    if (next == topIndex) return true;
                    topIndex = next;
                    if (topIndex < 3) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (cards.isEmpty() && patroOn()) {
                        zone = Z_PATRO;
                        patroGoToday();
                        patroHead = true;
                    } else if (cards.isEmpty()) {
                        zone = Z_SHELF;
                    } else {
                        zone = Z_CARDS;
                        cardIndex = nearest(cardRects, cards.size(), topRects[topIndex].centerX());
                    }
                } else {
                    return true;
                }
                break;
            case Z_PATRO:
                patroMove(dx, dy);
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
