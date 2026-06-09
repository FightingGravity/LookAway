package com.example.lookaway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

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

    // --- SHARED NOTIFICATION VARIABLES ---
    private static final String CHANNEL_ID = "LookAway_Scanner_Channel";
    private static final int NOTIFICATION_ID = 9911;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Intercept shutdown commands before performing initialization
        if (intent != null && "STOP_VPN".equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }

        // Build the final notification instantly and feed it directly to the system
        Notification finalNotification = buildDynamicNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, finalNotification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, finalNotification);
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

                int dropCount = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    int length = in.read(packet);
                    if (length > 0) {
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
        b.addAddress("10.0.0.2", 32);
        b.addRoute("0.0.0.0", 0);

        SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
        Set<String> vpnBlockedApps = prefs.getStringSet("vpn_blocked_apps_list", new HashSet<>());

        if (vpnBlockedApps.isEmpty()) return false;

        PackageManager pm = getPackageManager();
        for (String packageName : vpnBlockedApps) {
            try {
                pm.getPackageInfo(packageName, 0);
                b.addAllowedApplication(packageName);
                Log.i(TAG, "Total Silence active for: " + packageName);
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        try { return (vpnInterface = b.establish()) != null; } catch (Exception e) { return false; }
    }

    private void stopVpn() {
        if (vpnThread != null) { vpnThread.interrupt(); vpnThread = null; }
        try { if (vpnInterface != null) vpnInterface.close(); } catch (Exception ignored) {}

        // Smart Shutdown: Only clear the notification layout if the visual engine is also dead
        if (!LookAwayMasterEngine.isRunning) {
            stopForeground(true);
        } else {
            stopForeground(false);
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopVpn();
    }

    // --- SHARED NOTIFICATION BUILDER LOGIC ---
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID, "LookAway Master Engine", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private Notification buildDynamicNotification() {
        SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);

        // If this service execution path is hit, the VPN status is active
        boolean isVpnActive = prefs.getBoolean("passive_ad_block", false);
        // Safely extract the Master Engine runtime state via internal boolean flag
        boolean isOverlayActive = LookAwayMasterEngine.isRunning;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("LookAway Control Center")
                .setContentText("Active background systems:")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true);

        // Map button targets straight to the core Master Engine processing routines
        if (isOverlayActive) {
            Intent disableOverlayIntent = new Intent(this, LookAwayMasterEngine.class);
            disableOverlayIntent.setAction("ACTION_DISABLE_OVERLAY");
            PendingIntent piOverlay = PendingIntent.getService(this, 2, disableOverlayIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(R.drawable.ic_notification, "Stop Overlay", piOverlay);
        }

        if (isVpnActive) {
            Intent disableVpnIntent = new Intent(this, LookAwayMasterEngine.class);
            disableVpnIntent.setAction("ACTION_DISABLE_VPN");
            PendingIntent piVpn = PendingIntent.getService(this, 3, disableVpnIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(R.drawable.ic_notification, "Stop Ad Block", piVpn);
        }

        return builder.build();
    }
}