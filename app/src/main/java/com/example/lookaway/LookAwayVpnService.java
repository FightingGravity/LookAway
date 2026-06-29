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

    private static final String TAG = "LookAway-SAM";
    private Thread vpnThread;
    private ParcelFileDescriptor vpnInterface;

    private FileInputStream in;
    private FileOutputStream out;

    // Local VPN configuration for user-selected apps.
    private static final String CHANNEL_ID = "LookAway_SAM_Channel";
    private static final int NOTIFICATION_ID = 9912;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP_VPN".equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }

        Notification finalNotification = buildDynamicNotification();

        try {
            startForeground(NOTIFICATION_ID, finalNotification);
        } catch (Exception e) {
            Log.w("LookAway", "Foreground notification start failed. Posting fallback notification.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, finalNotification);
            }
        }

        if (vpnThread == null || !vpnThread.isAlive()) {
            vpnThread = new Thread(this, "LookAwayVpnThread");
            vpnThread.start();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void run() {
        try {
            if (setupVpn()) {
                Log.i(TAG, "SAM active. Dropping network traffic for selected apps locally.");
                in = new FileInputStream(vpnInterface.getFileDescriptor());
                out = new FileOutputStream(vpnInterface.getFileDescriptor());
                byte[] packet = new byte[32767];

                int dropCount = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    int length = in.read(packet);
                    if (length > 0) {
                        dropCount++;
                        if (dropCount % 100 == 0) {
                            Log.v(TAG, "Dropped " + dropCount + " selected-app packets locally.");
                        }
                    } else {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            Log.i(TAG, "SAM VPN thread interrupted. Shutting down.");
                            break;
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

        // SAM routes only user-selected apps into this local VPN interface and drops packets locally.
        PackageManager pm = getPackageManager();
        for (String packageName : vpnBlockedApps) {
            try {
                pm.getPackageInfo(packageName, 0);
                b.addAllowedApplication(packageName);
                Log.i(TAG, "SAM selected app: " + packageName);
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        try { return (vpnInterface = b.establish()) != null; } catch (Exception e) { return false; }
    }

    private void stopVpn() {
        if (vpnThread != null) { vpnThread.interrupt(); vpnThread = null; }
        try { if (vpnInterface != null) vpnInterface.close(); } catch (Exception ignored) {}

        getSharedPreferences("LookAwayPrefs", MODE_PRIVATE).edit().putBoolean("passive_ad_block", false).apply();

        Intent uiIntent = new Intent("com.example.lookaway.RESET_UI");
        uiIntent.setPackage(getPackageName());
        sendBroadcast(uiIntent);

        stopForeground(true);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }

        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopVpn();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID, "Selective Airplane Mode", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private Notification buildDynamicNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Selective Airplane Mode")
                .setContentText("SAM is blocking all internet traffic for your selected apps.")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setGroup("SAM_EXPLICIT_ISOLATION")
                .setOngoing(true);

        Intent disableVpnIntent = new Intent(this, LookAwayVpnService.class);
        disableVpnIntent.setAction("STOP_VPN");
        PendingIntent piVpn = PendingIntent.getService(this, 3, disableVpnIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        builder.addAction(R.drawable.ic_notification, "Disable SAM", piVpn);

        return builder.build();
    }
}
