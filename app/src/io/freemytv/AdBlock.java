package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

import java.io.IOException;

/**
 * Ad blocking for the whole TV: AdVpn answers the DNS lookups of the names in assets/adblock.txt with "no such name"
 * and passes the others to AdGuard's DNS. It can't touch ads served from the same address as the video (YouTube's own).
 * The system asks once for approval of the VPN: that dialog is the user's to accept.
 */
final class AdBlock {
    private AdBlock() {}

    static final int REQUEST = 77;
    /** Private DNS of an earlier version of this switch: it would bypass the VPN's DNS, so it is put back. */
    private static final String OLD_HOST = "dns.adguard-dns.com";

    /** The switch changed in settings. On: ask for the VPN approval if needed, then start. */
    static void request(Activity a, boolean on) {
        if (!on) {
            AdVpn.stop(a);
            HomeMode.notice(a, Ui.tr("Bloqueio de anúncios desligado.", "Ad blocking is off."));
            return;
        }
        Intent approval = VpnService.prepare(a);
        if (approval != null) {
            a.startActivityForResult(approval, REQUEST);
        } else {
            begin(a);
        }
    }

    /** The approval dialog came back (RESULT_OK = accepted). */
    static void result(Activity a, int resultCode) {
        if (resultCode == Activity.RESULT_OK) {
            begin(a);
        } else {
            Prefs.setDnsBlock(false);
            HomeMode.notice(a, Ui.tr("O bloqueio precisa da aprovação da VPN na TV.", "Ad blocking needs the VPN approval on the TV."));
        }
    }

    /** After a reboot: back on if it was on and the approval is already given (a dialog can't open from here). */
    static void resume(Context c) {
        if (Prefs.dnsBlock() && VpnService.prepare(c) == null) AdVpn.start(c);
    }

    private static void begin(Context c) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            freePrivateDns(app);
            AdVpn.start(app);
            HomeMode.notice(app, Ui.tr("Bloqueio de anúncios ligado.", "Ad blocking is on."));
        }).start();
    }

    /** Puts back the Private DNS setting an earlier version of this switch changed, if it is still ours. */
    private static void freePrivateDns(Context c) {
        try (Adb adb = Adb.connect(c, 10_000, null)) {
            String host = adb.shell("settings get global private_dns_specifier").trim();
            if (!host.equals(OLD_HOST)) return;
            String[] was = Prefs.dnsWas().split("\\|", -1);
            String mode = was[0].isEmpty() || was[0].equals("null") || was[0].equals("hostname") ? "opportunistic" : was[0];
            adb.shell("settings put global private_dns_mode " + mode);
        } catch (IOException e) {
            AppLog.w("private DNS: " + e.getMessage());
        }
    }
}
