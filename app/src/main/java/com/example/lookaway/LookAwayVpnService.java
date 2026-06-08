package com.example.lookaway;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;

public class LookAwayVpnService extends VpnService implements Runnable {

    private static final String TAG = "LookAway-DNS";
    private Thread vpnThread;
    private ParcelFileDescriptor vpnInterface;

    private FileInputStream in;
    private FileOutputStream out;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP_VPN".equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }
        if (vpnThread == null || !vpnThread.isAlive()) {
            vpnThread = new Thread(this, "LookAwayVpnThread");
            vpnThread.start();
        }
        return START_STICKY;
    }

    @Override
    public void run() {
        try {
            if (setupVpn()) {
                Log.i(TAG, "Per-App Airplane Mode Active. Silently killing all traffic for selected apps.");
                in = new FileInputStream(vpnInterface.getFileDescriptor());
                out = new FileOutputStream(vpnInterface.getFileDescriptor());
                byte[] packet = new byte[32767];

                // Diagnostic counter
                int dropCount = 0;

                while (!Thread.currentThread().isInterrupted()) {
                    int length = in.read(packet);
                    if (length > 0) {
                        // Traffic is discarded here, effectively killing the connection.
                        dropCount++;
                        if (dropCount % 100 == 0) {
                            Log.v(TAG, "Silently dropped " + dropCount + " packets.");
                        }
                    }
                }
            } else {
                Log.e(TAG, "VPN Setup failed.");
                stopSelf();
            }
        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                Log.e(TAG, "VPN Thread Error: " + e.getMessage());
            }
        } finally {
            try {
                if (in != null) in.close();
                if (out != null) out.close();
            } catch (Exception ignored) {}
        }
    }

    private boolean setupVpn() {
        Builder b = new Builder();
        // Set a local address
        b.addAddress("10.0.0.2", 32);

        // Route all traffic through the VPN
        b.addRoute("0.0.0.0", 0);

        SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
        Set<String> vpnBlockedApps = prefs.getStringSet("vpn_blocked_apps_list", new HashSet<>());

        if (vpnBlockedApps.isEmpty()) return false;

        PackageManager pm = getPackageManager();
        for (String packageName : vpnBlockedApps) {
            try {
                pm.getPackageInfo(packageName, 0);
                // OS routes ONLY this app's traffic into our tunnel
                b.addAllowedApplication(packageName);
                Log.i(TAG, "Total Silence active for: " + packageName);
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        try { return (vpnInterface = b.establish()) != null; } catch (Exception e) { return false; }
    }

    private void stopVpn() {
        if (vpnThread != null) { vpnThread.interrupt(); vpnThread = null; }
        try { if (vpnInterface != null) vpnInterface.close(); } catch (Exception ignored) {}
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopVpn();
    }
}