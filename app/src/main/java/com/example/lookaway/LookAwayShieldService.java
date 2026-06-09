package com.example.lookaway;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

public class LookAwayShieldService extends AccessibilityService {

    private static final String TAG = "LookAwayShield";
    private static LookAwayShieldService instance;

    private static final Set<String> SYSTEM_BLOCKLIST = new HashSet<>();

    // Dynamic Learning Variables
    private String baseAppPackage = "";
    private final Set<String> dynamicSafeClasses = new HashSet<>();

    static {
        SYSTEM_BLOCKLIST.add("com.android.systemui");
        SYSTEM_BLOCKLIST.add("com.google.android.inputmethod.latin");
        SYSTEM_BLOCKLIST.add("com.android.launcher3");
        SYSTEM_BLOCKLIST.add("com.google.android.apps.nexuslauncher");
        SYSTEM_BLOCKLIST.add("android");
        // Block Play Store so transparent overlay redirects aren't memorized as games
        SYSTEM_BLOCKLIST.add("com.android.vending");
    }

    public static LookAwayShieldService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "LookAway Accessibility Drone Online.");

        Intent returnIntent = new Intent(this, MainActivity.class);
        returnIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(returnIntent);
    }

    // Hardcoded universally safe game engines
    private boolean isUniversalSafeClass(String className) {
        return className.contains("UnityPlayerActivity") ||
                className.contains("Cocos2dxActivity") ||
                className.contains("NativeActivity") ||
                className.contains("MainActivity") ||
                className.contains("GameActivity");
    }

    // --- BASELINE DATABASE: Top 20 Global Ad Network SDKs ---
    private boolean isKnownAdClass(String className) {
        String lower = className.toLowerCase();
        return lower.contains("com.google.android.gms.ads.adactivity") ||               // 1. AdMob / Google
                lower.contains("com.applovin.adview.applovinfullscreenactivity") ||      // 2. AppLovin
                lower.contains("com.unity3d.services.ads.adunit.adunitactivity") ||      // 3. Unity Ads
                lower.contains("com.vungle.warren.ui.vungleactivity") ||                 // 4. Vungle / Liftoff
                lower.contains("com.ironsource.sdk.controller.controlleractivity") ||    // 5. IronSource
                lower.contains("com.mbridge.msdk.reward.player.mbrewardvideoactivity") || // 6. Mintegral
                lower.contains("com.chartboost.sdk.view.cbimpressionactivity") ||        // 7. Chartboost
                lower.contains("com.tapjoy.tjadunitactivity") ||                         // 8. Tapjoy
                lower.contains("com.adcolony.sdk.adcolonyinterstitialactivity") ||       // 9. AdColony
                lower.contains("com.bytedance.sdk.openadsdk.activity.ttrewardvideoactivity") || // 10. Pangle / TikTok
                lower.contains("com.bytedance.sdk.openadsdk.activity.ttfullscreenvideoactivity") || // 11. Pangle Fullscreen
                lower.contains("com.inmobi.ads.rendering.inmobiadactivity") ||           // 12. InMobi
                lower.contains("com.facebook.ads.audiencenetworkactivity") ||            // 13. Meta / Facebook
                lower.contains("com.fyber.inneractive.sdk.activities.inneractivefullscreenadactivity") || // 14. Fyber
                lower.contains("com.smaato.sdk.core.browser.browseractivity") ||         // 15. Smaato
                lower.contains("com.amazon.device.ads.adactivity") ||                    // 16. Amazon Publisher Services
                lower.contains("com.yandex.mobile.ads.common.adactivity") ||             // 17. Yandex
                lower.contains("com.startapp.sdk.adsbase.activities.overlayactivity") || // 18. StartApp
                lower.contains("com.digitalturbine.ignite.cl.ui.activity.interstitialactivity") || // 19. Digital Turbine
                lower.contains("com.google.android.finsky.transparentmainactivity");     // 20. Play Store Redirect Overlay
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkgCharSequence = event.getPackageName();
            CharSequence classCharSequence = event.getClassName();

            if (pkgCharSequence != null && classCharSequence != null) {
                String packageName = pkgCharSequence.toString();
                String className = classCharSequence.toString();

                if (SYSTEM_BLOCKLIST.contains(packageName)) return;

                // Check user preferences for Automatic Mode
                SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
                boolean isAutomaticMode = prefs.getBoolean("is_automatic_mode", false);

                // 1. Wipe dynamic memory clean if the user switches to a totally new app
                if (!packageName.equals(baseAppPackage)) {
                    baseAppPackage = packageName;
                    dynamicSafeClasses.clear();
                }

                // 2. Memorize classes that are NOT ads to build a custom safe zone
                if (!isKnownAdClass(className)) {
                    dynamicSafeClasses.add(className);
                }

                // 3. Routing Logic: Close eye on Safe Zone, Open eye on Ad Network
                if (isUniversalSafeClass(className) || dynamicSafeClasses.contains(className)) {
                    Log.d(TAG, "Safe Zone Confirmed: " + className + ". Auto-closing scanner.");
                    if (com.example.lookaway.LookAwayMasterEngine.getInstance() != null) {
                        com.example.lookaway.LookAwayMasterEngine.getInstance().stopScannerFromShield();
                    }
                } else if (isAutomaticMode && isKnownAdClass(className)) {
                    Log.d(TAG, "Target Locked - Ad Network SDK Detected: " + className);
                    if (com.example.lookaway.LookAwayMasterEngine.getInstance() != null) {
                        com.example.lookaway.LookAwayMasterEngine.getInstance().triggerScannerFromShield();
                    }
                }
            }
        }
    }

    // --- RAM BASED CLICK PASS THROUGH ---
    public void clickAtCoordinates(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Clicking requires Nougat (API 24) or higher.");
            return;
        }

        Log.d(TAG, "Executing simulated tap on hardware grid at: (" + x + ", " + y + ")");

        Path clickPath = new Path();
        clickPath.moveTo(x, y);

        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(clickPath, 0, 50);
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(stroke);

        dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                Log.v(TAG, "Accessibility tap successfully completed via RAM instruction.");
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                Log.e(TAG, "Accessibility tap aborted by OS dispatcher.");
            }
        }, null);
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility engine interrupted.");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
    }
}