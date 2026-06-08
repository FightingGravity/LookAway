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

    /**
     * Allows other parts of our app (like the Floating Widget or automation loops)
     * to safely access the clicker engine.
     */
    public static LookAwayClickerService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        android.accessibilityservice.AccessibilityServiceInfo info = new android.accessibilityservice.AccessibilityServiceInfo();

        // RESTORED: Turn the service's eyes back on so it can monitor screen changes
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        info.notificationTimeout = 100;
        this.setServiceInfo(info);

        Log.d(TAG, "Accessibility Clicker Engine Connected & Monitoring.");

        // Identify the device's home launcher package to know when we are on the home screen
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolveInfo = getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolveInfo != null && resolveInfo.activityInfo != null) {
            homeLauncherPackage = resolveInfo.activityInfo.packageName;
        }

        // Auto-Return Logic
        Intent returnIntent = new Intent(this, MainActivity.class);
        returnIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(returnIntent);
    }

    /**
     * Simulates a single finger tap at a specific (x, y) coordinate on the screen.
     */
    public void clickAtCoordinates(int x, int y) {
        if (x < 0 || y < 0) {
            Log.w(TAG, "Invalid click coordinates: (" + x + ", " + y + "). Ignoring.");
            return;
        }
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

            // Avoid scanning system UI layers or our own application
            if (currentApp.equals("com.android.systemui") || currentApp.equals(getPackageName())) return;

            SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
            // Verify if automatic reward sensing is enabled in settings
            if (!prefs.getBoolean("is_automatic_mode", false)) return;

            // 1. Identify current device zone
            Set<String> monitoredApps = prefs.getStringSet("monitored_apps_list", new HashSet<>());
            boolean isGame = monitoredApps.contains(currentApp);
            boolean isHome = currentApp.equals(homeLauncherPackage);
            boolean isPlayStore = currentApp.equals("com.android.vending");

            // 2. Scan Logic (Node Tree Inspection)
            boolean isHiddenAd = false;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                isHiddenAd = searchForHiddenAdSignatures(root);
                root.recycle();
            }

            // 3. Automation State Management
            if (isHiddenAd) {
                isAdActive = true;
                // Kick off the OpenCV background image tracking loops and alert the widget
                sendBroadcast(new Intent("com.example.lookaway.START_AUTO_SCAN").setPackage(getPackageName()));
                sendBroadcast(new Intent("com.example.lookaway.VISUAL_AD_CAUGHT").setPackage(getPackageName()));
            }
            // Turn off scanning if we return to a designated safe zone (the game environment or home launcher)
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
    public boolean onUnbind(Intent intent) {
        instance = null;
        Log.d(TAG, "Accessibility Clicker Engine Disconnected.");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
    }
}