package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 */
public class RemoteKeyService extends AccessibilityService {
    private static final String NETTV_LAUNCHER = "np.com.geniussystems.android.atv.launcher";
    private long bootUntil, lastHome;

    private void home() {
        lastHome = SystemClock.uptimeMillis();
        startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /**
     * NetTV's launcher opens itself at startup, and again when its own apps close. For the first minutes after the TV
     * starts, Free My TV takes the screen back each time that launcher comes up (the accessibility events tell us).
     */
    @Override protected void onServiceConnected() {
        if (!RemoteKeys.NETTV || SystemClock.elapsedRealtime() > 300_000) return;
        bootUntil = SystemClock.uptimeMillis() + 240_000;
        Handler h = new Handler(Looper.getMainLooper());
        h.post(this::home);
        try {
            AdBlock.resume(this);   // boot: the ad filter back on (a boot receiver may not start it in the background)
        } catch (RuntimeException e) {
            AppLog.w("ad filter at boot: " + e.getMessage());
        }
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (bootUntil == 0 || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        long now = SystemClock.uptimeMillis();
        if (now < bootUntil && now - lastHome > 1500 && NETTV_LAUNCHER.contentEquals(String.valueOf(event.getPackageName()))) home();
    }

    @Override public void onInterrupt() {}
}
