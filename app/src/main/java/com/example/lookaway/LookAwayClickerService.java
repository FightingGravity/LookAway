package com.example.lookaway;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashSet;
import java.util.Set;

public class LookAwayClickerService extends AccessibilityService {

    private static LookAwayClickerService instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String homeLauncherPackage = "";
    private static final String TAG = "LookAway-Tracker";

    private boolean isAdActive = false;

    public static LookAwayClickerService getInstance() { return instance; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        android.accessibilityservice.AccessibilityServiceInfo info = new android.accessibilityservice.AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        this.setServiceInfo(info);

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolveInfo = getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolveInfo != null && resolveInfo.activityInfo != null) {
            homeLauncherPackage = resolveInfo.activityInfo.packageName;
        }

        Intent navIntent = new Intent(this, MainActivity.class);
        navIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(navIntent);
    }

    public void clickAtCoordinates(int x, int y) {
        if (x < 0 || y < 0) return;
        mainHandler.post(() -> {
            try {
                Path clickPath = new Path();
                clickPath.moveTo(x, y);
                GestureDescription.StrokeDescription clickStroke = new GestureDescription.StrokeDescription(clickPath, 0, 50);
                GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
                gestureBuilder.addStroke(clickStroke);
                dispatchGesture(gestureBuilder.build(), null, null);
            } catch (Exception e) {
                Log.e(TAG, "Error triggering click: " + e.getMessage());
            }
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String currentApp = event.getPackageName() != null ? event.getPackageName().toString() : "";
            String currentClass = event.getClassName() != null ? event.getClassName().toString() : "";

            if (currentApp.equals("com.android.systemui") ||
                    currentClass.contains("LearningFirewallActivity") ||
                    currentApp.equals(getPackageName())) return;

            SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
            if (!prefs.getBoolean("is_automatic_mode", false)) return;

            // 1. Identify Zones
            Set<String> monitoredApps = prefs.getStringSet("monitored_apps_list", new HashSet<>());
            boolean isGame = monitoredApps.contains(currentApp);
            boolean isHome = currentApp.equals(homeLauncherPackage);
            boolean isPlayStore = currentApp.equals("com.android.vending");

            // 2. Scan Logic (Node Inspection Only)
            boolean isHiddenAd = false;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                isHiddenAd = searchForHiddenAdSignatures(root);
                root.recycle();
            }

            // 3. State Management
            if (isHiddenAd) {
                isAdActive = true;
                sendBroadcast(new Intent("com.example.lookaway.START_AUTO_SCAN").setPackage(getPackageName()));
                sendBroadcast(new Intent("com.example.lookaway.VISUAL_AD_CAUGHT").setPackage(getPackageName()));
            }
            // Only close if we are in a safe zone (Game or Home) and NOT in the Play Store
            else if ((isGame || isHome) && !isPlayStore) {
                if (isAdActive) {
                    isAdActive = false;
                    sendBroadcast(new Intent("com.example.lookaway.STOP_AUTO_SCAN").setPackage(getPackageName()));
                }
            }
        }
    }

    private boolean searchForHiddenAdSignatures(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence className = node.getClassName();
        if (className != null && (className.toString().toLowerCase().contains("webview") || className.toString().toLowerCase().contains("adview"))) return true;

        CharSequence text = node.getText();
        if (text != null && (text.toString().toLowerCase().equals("ad") || text.toString().toLowerCase().equals("sponsored"))) return true;

        for (int i = 0; i < node.getChildCount(); i++) {
            if (searchForHiddenAdSignatures(node.getChild(i))) return true;
        }
        return false;
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
    }
}