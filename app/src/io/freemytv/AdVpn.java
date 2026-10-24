package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A local DNS filter. The VPN carries nothing but the DNS lookups (the only route is the made-up DNS address): a lookup
 * of a name in assets/adblock.txt (or under it) gets "no such name", every other one goes on to AdGuard's DNS, which
 * filters the common ad and tracker lists. All other traffic never enters the VPN.
 */
public class AdVpn extends VpnService {
    private static final byte[] DNS = {10, 111, (byte) 222, 2};
    private static final String[] UPSTREAM = {"94.140.14.14", "1.1.1.1"};
    static final String STOP = "io.freemytv.ADVPN_STOP";

    /** True while the filter is up in this process: the home screen starts it again if the TV or an update stopped it. */
    static volatile boolean on;
    private volatile boolean running;
    private ParcelFileDescriptor tun;
    private Thread loop;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Object write = new Object();
    private Set<String> blocked = new HashSet<>();

    /** Starts the filter (the system's VPN approval must have been given: see AdBlock.request). */
    static void start(Context c) {
        c.startForegroundService(new Intent(c, AdVpn.class));   // a foreground service: it may start from the background and the system keeps it
    }

    static void stop(Context c) {
        c.startService(new Intent(c, AdVpn.class).setAction(STOP));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        foreground();   // must follow startForegroundService within seconds, whether or not the VPN can start
        if (!running) begin();
        if (!running) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    /** The small \"ad filter is on\" notice that keeps the service alive (it is quiet: no sound, lowest priority). */
    private void foreground() {
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        nm.createNotificationChannel(new android.app.NotificationChannel("adfilter", "Ad filter", android.app.NotificationManager.IMPORTANCE_MIN));
        android.app.Notification n = new android.app.Notification.Builder(this, "adfilter").setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(Ui.tr("Bloqueio de anúncios ligado", "Ad blocking is on")).setOngoing(true).build();
        startForeground(7, n);
    }

    private void begin() {
        loadList();
        try {
            tun = new Builder().setSession("Free My TV ad filter").addAddress("10.111.222.1", 32)
                    .addRoute("10.111.222.2", 32).addDnsServer("10.111.222.2").setMtu(1400).establish();
        } catch (RuntimeException e) {
            AppLog.w("ad filter: " + e.getMessage());
            return;
        }
        if (tun == null) return;   // no approval: the dialog has to be accepted first
        running = true;
        on = true;
        loop = new Thread(this::run, "adfilter");
        loop.start();
        AppLog.w("ad filter on (" + blocked.size() + " names)");
    }

    private void loadList() {
        Set<String> set = new HashSet<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(getAssets().open("adblock.txt"), "UTF-8"))) {
            for (String line; (line = in.readLine()) != null; ) {
                line = line.trim().toLowerCase(java.util.Locale.ROOT);
                if (!line.isEmpty() && line.charAt(0) != '#') set.add(line);
            }
        } catch (IOException e) {
            AppLog.w("ad filter list: " + e.getMessage());
        }
        blocked = set;
    }

    private boolean isBlocked(String name) {
        for (String n = name; ; ) {
            if (blocked.contains(n)) return true;
            int dot = n.indexOf('.');
            if (dot < 0) return false;
            n = n.substring(dot + 1);
        }
    }

    private void run() {
        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(tun.getFileDescriptor())) {
            byte[] buf = new byte[32767];
            while (running) {
                int n = in.read(buf);
                if (n <= 0) {
                    Thread.sleep(20);
                    continue;
                }
                byte[] pkt = Arrays.copyOf(buf, n);
                pool.execute(() -> handle(pkt, out));
            }
        } catch (IOException | InterruptedException e) {
            if (running) AppLog.w("ad filter stopped: " + e.getMessage());
        }
    }

    /** One IPv4/UDP packet to the DNS address: answer it, or pass it on. */
    private void handle(byte[] p, FileOutputStream out) {
        try {
            if (p.length < 28 || (p[0] >> 4) != 4 || p[9] != 17) return;
            int ihl = (p[0] & 15) * 4;
            if (p.length < ihl + 8 + 12 || (((p[ihl + 2] & 255) << 8) | (p[ihl + 3] & 255)) != 53) return;
            int dns = ihl + 8, dnsLen = p.length - dns;
            byte[] query = Arrays.copyOfRange(p, dns, p.length);
            String name = name(query);
            byte[] answer;
            if (name != null && isBlocked(name)) {
                answer = refuse(query);
            } else {
                answer = forward(query);
                if (answer == null) return;
            }
            byte[] reply = packet(p, ihl, answer);
            synchronized (write) {
                out.write(reply);
            }
        } catch (IOException | RuntimeException e) {
            // one lookup lost: the app asks again
        }
    }

    /** The first question's name, lower-case, or null. */
    private static String name(byte[] q) {
        StringBuilder s = new StringBuilder();
        int i = 12;
        while (i < q.length && q[i] != 0) {
            int len = q[i] & 255;
            if ((len & 0xC0) != 0 || i + 1 + len > q.length) return null;
            if (s.length() > 0) s.append('.');
            for (int j = 1; j <= len; j++) s.append((char) q[i + j]);
            i += len + 1;
        }
        return s.toString().toLowerCase(java.util.Locale.ROOT);
    }

    /** The query turned into "no such name" (NXDOMAIN). */
    private static byte[] refuse(byte[] q) {
        int end = 12;
        while (end < q.length && q[end] != 0) end += (q[end] & 255) + 1;
        end += 5;   // the zero, the type and the class
        byte[] a = Arrays.copyOf(q, Math.min(end, q.length));
        a[2] = (byte) 0x81;
        a[3] = (byte) 0x83;
        a[6] = a[7] = a[8] = a[9] = a[10] = a[11] = 0;
        return a;
    }

    private byte[] forward(byte[] query) {
        for (String server : UPSTREAM) {
            try (DatagramSocket s = new DatagramSocket()) {
                protect(s);
                s.setSoTimeout(2500);
                s.send(new DatagramPacket(query, query.length, InetAddress.getByName(server), 53));
                byte[] buf = new byte[4096];
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                s.receive(in);
                return Arrays.copyOf(buf, in.getLength());
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    /** The answer wrapped as an IPv4/UDP packet from the DNS address back to the asker. */
    private static byte[] packet(byte[] req, int ihl, byte[] payload) {
        int total = 28 + payload.length;
        byte[] r = new byte[total];
        r[0] = 0x45;
        r[2] = (byte) (total >> 8);
        r[3] = (byte) total;
        r[8] = 64;
        r[9] = 17;
        System.arraycopy(DNS, 0, r, 12, 4);          // from the DNS address
        System.arraycopy(req, 12, r, 16, 4);         // to the asker
        int sum = 0;
        for (int i = 0; i < 20; i += 2) sum += ((r[i] & 255) << 8) | (r[i + 1] & 255);
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        sum = ~sum & 0xFFFF;
        r[10] = (byte) (sum >> 8);
        r[11] = (byte) sum;
        r[20] = 0;
        r[21] = 53;                                   // from port 53
        r[22] = req[ihl];
        r[23] = req[ihl + 1];                         // to the asker's port
        int udp = 8 + payload.length;
        r[24] = (byte) (udp >> 8);
        r[25] = (byte) udp;                           // checksum 0: none, allowed in IPv4
        System.arraycopy(payload, 0, r, 28, payload.length);
        return r;
    }

    private void shutdown() {
        running = false;
        on = false;
        try {
            if (tun != null) tun.close();
        } catch (IOException ignored) {
        }
        tun = null;
        AppLog.w("ad filter off");
    }

    @Override public void onDestroy() {
        shutdown();
        pool.shutdownNow();
        super.onDestroy();
    }

    @Override public void onRevoke() {
        shutdown();
        Prefs.setDnsBlock(false);   // the system took the VPN away (another VPN, or the user)
        stopSelf();
    }
}
