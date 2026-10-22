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
    /** NetTV's launcher opens itself at startup: Free My TV takes the screen as soon as the service is up (boot only). */
    @Override protected void onServiceConnected() {
        if (RemoteKeys.NETTV && SystemClock.elapsedRealtime() < 120_000) {
            Runnable home = () -> startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Handler h = new Handler(Looper.getMainLooper());
            h.post(home);
            h.postDelayed(home, 8_000);   // again, in case the launcher comes up after us
        }
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}
}
