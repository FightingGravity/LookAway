package com.example.lookaway;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Set;

public class LookAwayShieldService extends AccessibilityService {
    private static final String TAG = "LookAwayShield";
    private static final boolean LOG_NON_WINDOW_ACCESSIBILITY_EVENTS = true;
    private static final boolean ENABLE_CONTENT_CHANGE_BURST_TRIGGER = true;
    private static final long NON_WINDOW_EVENT_LOG_THROTTLE_MS = 1200;
    private static final long CONTENT_CHANGE_BURST_WINDOW_MS = 5000;
    private static final int CONTENT_CHANGE_BURST_THRESHOLD = 4;
    private static final long CONTENT_CHANGE_TRIGGER_COOLDOWN_MS = 45000;
    private static final long CONTENT_CHANGE_LAUNCH_GRACE_MS = 8000;
    private static final long STOREFRONT_RECOVERY_STALE_MS = 120000;
    private volatile boolean recoveringFromStorefront = false;
    private volatile boolean storefrontRecoveryReturnedToBase = false;
    private volatile boolean storefrontRecoverySawAdTap = false;
    private long storefrontRecoveryArmedAt = 0;
    private long lastAutoBackTime = 0;
    private long lastNonWindowEventLogTime = 0;
    private String lastNonWindowEventSignature = "";
    private long contentChangeBurstWindowStart = 0;
    private int contentChangeBurstCount = 0;
    private long lastContentChangeTriggerTime = 0;
    private final android.os.Handler autoBackHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable autoBackRunnable = () -> {
        android.util.Log.d("LookAwayShield", "Storefront return timeout reached. Sending back navigation.");
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);

        // Some storefront redirects need a second back action after the first transition settles.
        autoBackHandler.postDelayed(() -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK), 800);
    };

    private long baseAppLaunchTime = 0;
    private String baseAppPackage = null;
    private volatile boolean needsSafeClassClear = false;

    public String getBaseAppPackage() {
        return baseAppPackage;
    }

    public String getStorefrontRecoveryDebugState() {
        return "recovering=" + recoveringFromStorefront +
                " returnedToBase=" + storefrontRecoveryReturnedToBase +
                " postStoreTap=" + storefrontRecoverySawAdTap +
                " ageMs=" + getStorefrontRecoveryAgeMs();
    }

    private String shortClassName(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot >= 0 ? className.substring(lastDot + 1) : className;
    }

    private void armStorefrontRecovery() {
        recoveringFromStorefront = true;
        storefrontRecoveryReturnedToBase = false;
        storefrontRecoverySawAdTap = false;
        storefrontRecoveryArmedAt = System.currentTimeMillis();
        Log.d(TAG, "STORE_RECOVERY arm base=" + baseAppPackage + " " + getStorefrontRecoveryDebugState());
    }

    private void markReturnedFromStorefront() {
        if (!recoveringFromStorefront || storefrontRecoveryReturnedToBase) return;

        storefrontRecoveryReturnedToBase = true;
        Log.d(TAG, "STORE_RECOVERY returned_to_base " + getStorefrontRecoveryDebugState());
    }

    public void extendStorefrontRecovery() {
        if (!recoveringFromStorefront) return;

        if (!storefrontRecoveryReturnedToBase) {
            Log.d(TAG, "STORE_RECOVERY target_tap_before_base ignored " + getStorefrontRecoveryDebugState());
            return;
        }

        storefrontRecoverySawAdTap = true;
        Log.d(TAG, "STORE_RECOVERY post_store_target_tap " + getStorefrontRecoveryDebugState());
    }

    private boolean isStorefrontRecoveryActive() {
        return recoveringFromStorefront;
    }

    private void clearStorefrontRecovery() {
        clearStorefrontRecovery("complete");
    }

    private void clearStorefrontRecovery(String reason) {
        if (recoveringFromStorefront || storefrontRecoveryReturnedToBase || storefrontRecoverySawAdTap) {
            Log.d(TAG, "STORE_RECOVERY clear reason=" + reason + " " + getStorefrontRecoveryDebugState());
        }
        recoveringFromStorefront = false;
        storefrontRecoveryReturnedToBase = false;
        storefrontRecoverySawAdTap = false;
        storefrontRecoveryArmedAt = 0;
    }

    public void resetStorefrontRecovery(String reason) {
        autoBackHandler.removeCallbacks(autoBackRunnable);
        clearStorefrontRecovery(reason);
    }

    private long getStorefrontRecoveryAgeMs() {
        if (!recoveringFromStorefront || storefrontRecoveryArmedAt == 0) return 0;
        return System.currentTimeMillis() - storefrontRecoveryArmedAt;
    }

    private boolean isStorefrontRecoveryStale() {
        return recoveringFromStorefront &&
                storefrontRecoveryArmedAt > 0 &&
                System.currentTimeMillis() - storefrontRecoveryArmedAt > STOREFRONT_RECOVERY_STALE_MS;
    }

    private boolean shouldResetRecoveryForPackage(String packageName) {
        return packageName.equals("com.example.lookaway") ||
                packageName.equals("com.sec.android.app.launcher") ||
                packageName.equals("com.android.launcher3") ||
                packageName.equals("com.google.android.apps.nexuslauncher");
    }

    private void updateLastSeenTimestamp(String fullSignature) {
        SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
        prefs.edit().putLong("last_seen_" + fullSignature, System.currentTimeMillis()).apply();
    }

    private static LookAwayShieldService instance;
    private static final Set<String> SYSTEM_BLOCKLIST = new HashSet<>();
    private final Set<String> dynamicSafeClasses = new HashSet<>();

    private final LinkedList<WindowRecord> rollingRingBuffer = new LinkedList<>();
    private static final int MAX_BUFFER_SIZE = 10;

    public static class WindowRecord {
        public final String className;
        public final String packageName;
        public WindowRecord(String className, String packageName) {
            this.className = className;
            this.packageName = packageName;
        }
    }

    static {
        SYSTEM_BLOCKLIST.add("com.android.systemui");
        SYSTEM_BLOCKLIST.add("com.google.android.inputmethod.latin");
        SYSTEM_BLOCKLIST.add("com.google.android.gms");
        SYSTEM_BLOCKLIST.add("com.samsung.android.honeyboard");
        SYSTEM_BLOCKLIST.add("com.android.launcher3");
        SYSTEM_BLOCKLIST.add("com.google.android.apps.nexuslauncher");
        SYSTEM_BLOCKLIST.add("android");
        SYSTEM_BLOCKLIST.add("com.example.lookaway");
        SYSTEM_BLOCKLIST.add("com.samsung.android.game.gametools");
        SYSTEM_BLOCKLIST.add("com.sec.android.app.launcher");
    }

    public static LookAwayShieldService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "LookAway Accessibility Service Online.");

        Intent returnIntent = new Intent(this, MainActivity.class);
        returnIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(returnIntent);
    }

    private boolean isKnownAdClass(String className) {
        String lower = className.toLowerCase(Locale.ROOT);
        return lower.contains("com.google.android.gms.ads.adactivity") ||
                lower.contains("com.applovin.adview.applovinfullscreenactivity") ||
                lower.contains("com.unity3d.services.ads.adunit.adunitactivity") ||
                lower.contains("com.unity3d.ads.adplayer.fullscreenwebviewdisplay") ||
                lower.contains("com.vungle.warren.ui.vungleactivity") ||
                lower.contains("com.ironsource.sdk.controller.controlleractivity") ||
                lower.contains("com.mbridge.msdk.reward.player.mbrewardvideoactivity") ||
                lower.contains("com.chartboost.sdk.view.cbimpressionactivity") ||
                lower.contains("com.tapjoy.tjadunitactivity") ||
                lower.contains("com.adcolony.sdk.adcolonyinterstitialactivity") ||
                lower.contains("com.bytedance.sdk.openadsdk.activity.ttrewardvideoactivity") ||
                lower.contains("com.bytedance.sdk.openadsdk.activity.ttfullscreenvideoactivity") ||
                lower.contains("com.inmobi.ads.rendering.inmobiadactivity") ||
                lower.contains("com.facebook.ads.audiencenetworkactivity") ||
                lower.contains("com.fyber.inneractive.sdk.activities.inneractivefullscreenadactivity") ||
                lower.contains("com.smaato.sdk.core.browser.browseractivity") ||
                lower.contains("com.amazon.device.ads.adactivity") ||
                lower.contains("com.yandex.mobile.ads.common.adactivity") ||
                lower.contains("com.startapp.sdk.adsbase.activities.overlayactivity") ||
                lower.contains("com.digitalturbine.ignite.cl.ui.activity.interstitialactivity") ||
                lower.contains("com.google.android.finsky.transparentmainactivity") ||
                lower.contains("com.moloco.sdk") ||
                lower.contains("vastactivity");
    }

    private void incrementTriggerCount(String signature) {
        SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
        String key = "count_" + signature;
        int currentCount = prefs.getInt(key, 0);
        prefs.edit().putInt(key, currentCount + 1).apply();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        final int eventType = event.getEventType();

        final CharSequence pkgCharSequence = event.getPackageName();
        final CharSequence classCharSequence = event.getClassName();

        if (pkgCharSequence == null || classCharSequence == null) return;

        final String packageName = pkgCharSequence.toString();
        final String className = classCharSequence.toString();

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                isStorefrontRecoveryActive() &&
                shouldResetRecoveryForPackage(packageName)) {
            resetStorefrontRecovery("left_ad_flow:" + packageName);
        }

        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            logNonWindowAccessibilityEvent(event, packageName, className);
            return;
        }

        // Accessibility is used for window/package state and local, user-configured gestures only.
        if (packageName.equals("com.sec.android.app.launcher") || packageName.equals("com.samsung.android.game.gametools")) {
            if (LookAwayMasterEngine.getInstance() != null && LookAwayMasterEngine.getInstance().isCurrentlyScanning()) {
                Log.d(TAG, "Samsung Game Tools overlay detected. Sending dismiss tap.");

                autoBackHandler.postDelayed(() -> {
                    int screenWidth = getResources().getDisplayMetrics().widthPixels;
                    int tapX = screenWidth / 2;
                    int tapY = 150;
                    clickAtCoordinates(tapX, tapY);
                    Log.d(TAG, "Dismiss tap executed at X:" + tapX + " Y:" + tapY);
                }, 2000);
            }
            return;
        }

        if (getSharedPreferences("LookAwayPrefs", MODE_PRIVATE)
                .getStringSet("monitored_apps_list", new HashSet<>()).contains(packageName)) {
            if (baseAppPackage == null || !baseAppPackage.equals(packageName)) {
                resetStorefrontRecovery("new_base_app:" + packageName);
                baseAppPackage = packageName;
                baseAppLaunchTime = System.currentTimeMillis();
                needsSafeClassClear = true;
            }
        }

        new Thread(() -> {
            SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
            Set<String> monitoredApps = prefs.getStringSet("monitored_apps_list", new HashSet<>());
            boolean isAutomaticMode = prefs.getBoolean("is_automatic_mode", true);

            if (SYSTEM_BLOCKLIST.contains(packageName)) return;

            boolean isBrowser = packageName.contains("chrome") ||
                    packageName.contains("browser") ||
                    packageName.equals("com.sec.android.app.sbrowser");

            boolean isAppStore = packageName.equals("com.android.vending") ||
                    packageName.contains("samsungapps") ||
                    packageName.contains("galaxyapps") ||
                    packageName.contains("sec.android.app.billing");

            if (isStorefrontRecoveryStale()) {
                resetStorefrontRecovery("stale_timeout");
            }

            // Keep automation scoped to the user's selected app, app-store redirects, and browser bridges.
            if (baseAppPackage == null && !monitoredApps.contains(packageName)) {
                return;
            }

            if (baseAppPackage != null && !packageName.equals(baseAppPackage)
                    && !isAppStore
                    && !isBrowser) {
                Log.d(TAG, "External package detected. Resetting monitoring state: " + packageName);
                resetStorefrontRecovery("external_package:" + packageName);
                if (LookAwayMasterEngine.getInstance() != null) {
                    LookAwayMasterEngine.getInstance().stopScannerFromShield();
                }
                baseAppPackage = null;
                return;
            }

            if (isAutomaticMode) {
                synchronized (rollingRingBuffer) {
                    rollingRingBuffer.addLast(new WindowRecord(className, packageName));
                    while (rollingRingBuffer.size() > MAX_BUFFER_SIZE) {
                        rollingRingBuffer.removeFirst();
                    }
                }
            }

            Set<String> customTripwires = prefs.getStringSet("custom_tripwires", new HashSet<>());
            Set<String> userIgnoreList = prefs.getStringSet("user_ignore_list", new HashSet<>());
            String fullSignature = packageName + "|" + className;
            boolean knownAdClass = isKnownAdClass(className);
            boolean customTrigger = customTripwires.contains(fullSignature);
            boolean userIgnored = userIgnoreList.contains(fullSignature);
            boolean baseEvent = baseAppPackage != null && packageName.equals(baseAppPackage);

            if (monitoredApps.contains(packageName)) {
                if (needsSafeClassClear) {
                    dynamicSafeClasses.clear();
                    if (!knownAdClass && !customTrigger && !userIgnored) {
                        dynamicSafeClasses.add(className);
                    }
                    needsSafeClassClear = false;
                } else if (!knownAdClass && !customTrigger && !userIgnored) {
                    dynamicSafeClasses.add(className);
                }
            }

            boolean safeBaseClass = baseEvent && dynamicSafeClasses.contains(className);
            boolean recoveryBaseEvent = baseEvent && isStorefrontRecoveryActive();
            boolean interestingEvent = recoveringFromStorefront || isAppStore || isBrowser ||
                    baseEvent || knownAdClass || customTrigger || userIgnored;

            if (interestingEvent) {
                Log.d(TAG,
                        "STATE pkg=" + packageName +
                                " class=" + shortClassName(className) +
                                " base=" + baseAppPackage +
                                " store=" + isAppStore +
                                " browser=" + isBrowser +
                                " baseEvent=" + baseEvent +
                                " safeBase=" + safeBaseClass +
                                " knownAd=" + knownAdClass +
                                " custom=" + customTrigger +
                                " ignored=" + userIgnored +
                                " scanning=" +
                                (LookAwayMasterEngine.getInstance() != null
                                        && LookAwayMasterEngine.getInstance().isCurrentlyScanning()) +
                                " " + getStorefrontRecoveryDebugState());
            }

            if (recoveryBaseEvent) {

                autoBackHandler.removeCallbacks(autoBackRunnable);

                Log.d(TAG,
                        "BASE EVENT:"
                                + " class=" + className
                                + " safeBase=" + safeBaseClass
                                + " recovering=" + recoveringFromStorefront
                                + " returnedToBase=" + storefrontRecoveryReturnedToBase
                                + " postStoreTap=" + storefrontRecoverySawAdTap
                                + " scanning=" +
                                (LookAwayMasterEngine.getInstance() != null
                                        && LookAwayMasterEngine.getInstance().isCurrentlyScanning()));

                LookAwayMasterEngine engine = LookAwayMasterEngine.getInstance();

                if (isStorefrontRecoveryActive()) {
                    markReturnedFromStorefront();

                    if (!storefrontRecoverySawAdTap) {
                        if (engine != null && !engine.isCurrentlyScanning()) {
                            engine.triggerScannerFromShield();
                        }
                        Log.d(TAG, "Returned from storefront. Waiting for ADAM post-store tap before closing.");
                        return;
                    }

                    clearStorefrontRecovery();
                    Log.d(TAG, "Storefront recovery completed by base app event. Auto-closing scanner.");
                }

                Log.d(TAG,
                        "Returned to Base App (" + className + "). Auto-closing scanner.");

                if (engine != null) {
                    engine.stopScannerFromShield();
                }
            }
            else if (userIgnored) {
                updateLastSeenTimestamp(fullSignature);
                Log.d(TAG, "User Ignore List matched: " + className + ". Auto-closing scanner.");
                if (LookAwayMasterEngine.getInstance() != null) {
                    LookAwayMasterEngine.getInstance().stopScannerFromShield();
                }
            }
            else if (isAppStore) {
                LookAwayMasterEngine engine = LookAwayMasterEngine.getInstance();
                if (engine != null &&
                        engine.isCurrentlyScanning() &&
                        baseAppPackage != null &&
                        (System.currentTimeMillis() - lastAutoBackTime > 6000)) {
                    lastAutoBackTime = System.currentTimeMillis();
                    Log.d(TAG, "App store redirect detected. Scheduling return navigation.");

                    armStorefrontRecovery();

                    if (LookAwayMasterEngine.getInstance() != null) {
                        LookAwayMasterEngine.getInstance().triggerScannerFromShield();
                    }
                    autoBackHandler.postDelayed(autoBackRunnable, 3000);
                }
            }
            else if (isAutomaticMode &&
                    (customTrigger || knownAdClass)) {

                boolean launchGraceActive = System.currentTimeMillis() - baseAppLaunchTime < 4000;
                if (launchGraceActive && !knownAdClass) {
                    Log.d(TAG, "Ignored custom trigger during app launch sequence.");
                } else {

                    updateLastSeenTimestamp(fullSignature);

                    if (customTrigger) {
                        incrementTriggerCount(fullSignature);
                    }

                    Log.d(TAG, "Ad trigger detected: " + className);

                    LookAwayMasterEngine engine = LookAwayMasterEngine.getInstance();

                    if (engine != null) {
                        engine.enforceCeasefire();

                        if (!engine.isCurrentlyScanning()) {
                            engine.triggerScannerFromShield();
                        }
                    }
                }
            }
            else if (safeBaseClass) {
                autoBackHandler.removeCallbacks(autoBackRunnable);

                Log.d(TAG,
                        "BASE EVENT:"
                                + " class=" + className
                                + " safeBase=true"
                                + " recovering=" + recoveringFromStorefront
                                + " returnedToBase=" + storefrontRecoveryReturnedToBase
                                + " postStoreTap=" + storefrontRecoverySawAdTap
                                + " scanning=" +
                                (LookAwayMasterEngine.getInstance() != null
                                        && LookAwayMasterEngine.getInstance().isCurrentlyScanning()));

                Log.d(TAG,
                        "Returned to Base App (" + className + "). Auto-closing scanner.");

                LookAwayMasterEngine engine = LookAwayMasterEngine.getInstance();
                if (engine != null) {
                    engine.stopScannerFromShield();
                }
            }
            else if (isBrowser) {
                Log.d(TAG, "Browser bridge detected. Auto-closing scanner, awaiting redirect.");
                if (LookAwayMasterEngine.getInstance() != null) {
                    LookAwayMasterEngine.getInstance().stopScannerFromShield();
                }
            }
        }).start();
    }

    private void logNonWindowAccessibilityEvent(AccessibilityEvent event, String packageName, String className) {
        if (!LOG_NON_WINDOW_ACCESSIBILITY_EVENTS || SYSTEM_BLOCKLIST.contains(packageName)) return;

        int eventType = event.getEventType();
        if (!isDiagnosticAccessibilityEvent(eventType)) return;

        SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
        if (!prefs.getBoolean("is_automatic_mode", true)) return;

        Set<String> monitoredApps = prefs.getStringSet("monitored_apps_list", new HashSet<>());
        boolean monitoredOrBase = monitoredApps.contains(packageName) ||
                (baseAppPackage != null && baseAppPackage.equals(packageName));
        if (!monitoredOrBase) return;

        long now = System.currentTimeMillis();
        LookAwayMasterEngine engine = LookAwayMasterEngine.getInstance();
        boolean scanning = engine != null && engine.isCurrentlyScanning();

        if (isContentChangeBurstCandidate(event, packageName, className, now, scanning) &&
                recordContentChangeBurst(now)) {
            Log.d(TAG,
                    "A11Y_BURST_TRIGGER pkg=" + packageName +
                            " class=" + shortClassName(className) +
                            " threshold=" + CONTENT_CHANGE_BURST_THRESHOLD +
                            " windowMs=" + CONTENT_CHANGE_BURST_WINDOW_MS);
            if (engine != null) {
                engine.triggerScannerFromShield();
            }
        }

        String signature = packageName + "|" + className + "|" + eventType;

        synchronized (this) {
            if (signature.equals(lastNonWindowEventSignature) &&
                    now - lastNonWindowEventLogTime < NON_WINDOW_EVENT_LOG_THROTTLE_MS) {
                return;
            }
            lastNonWindowEventSignature = signature;
            lastNonWindowEventLogTime = now;
        }

        int textCount = event.getText() == null ? 0 : event.getText().size();
        boolean hasContentDescription = event.getContentDescription() != null;
        int windowChanges = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? event.getWindowChanges() : 0;

        Log.d(TAG,
                "A11Y_EVENT type=" + AccessibilityEvent.eventTypeToString(eventType) +
                        " pkg=" + packageName +
                        " class=" + shortClassName(className) +
                        " base=" + baseAppPackage +
                        " textCount=" + textCount +
                        " contentDesc=" + hasContentDescription +
                        " contentChangeTypes=" + event.getContentChangeTypes() +
                        " windowChanges=" + windowChanges +
                        " scanning=" + scanning);
    }

    private boolean isDiagnosticAccessibilityEvent(int eventType) {
        return eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                eventType == AccessibilityEvent.TYPE_VIEW_SELECTED ||
                eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
    }

    private boolean isContentChangeBurstCandidate(
            AccessibilityEvent event,
            String packageName,
            String className,
            long now,
            boolean scanning) {
        if (!ENABLE_CONTENT_CHANGE_BURST_TRIGGER) return false;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return false;
        if (scanning) return false;
        if (baseAppPackage == null || !baseAppPackage.equals(packageName)) return false;
        if (now - baseAppLaunchTime < CONTENT_CHANGE_LAUNCH_GRACE_MS) return false;
        if (!isGenericEmbeddedAdClass(className)) return false;
        if (event.getText() != null && !event.getText().isEmpty()) return false;
        if (event.getContentDescription() != null) return false;

        int contentChangeTypes = event.getContentChangeTypes();
        return contentChangeTypes == 0 ||
                (contentChangeTypes & AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE) != 0;
    }

    private boolean isGenericEmbeddedAdClass(String className) {
        return className.equals("android.view.View") ||
                className.equals("android.view.ViewGroup") ||
                className.equals("android.widget.FrameLayout") ||
                className.endsWith(".ViewFactoryHolder");
    }

    private boolean recordContentChangeBurst(long now) {
        synchronized (this) {
            if (now - lastContentChangeTriggerTime < CONTENT_CHANGE_TRIGGER_COOLDOWN_MS) {
                return false;
            }

            if (contentChangeBurstWindowStart == 0 ||
                    now - contentChangeBurstWindowStart > CONTENT_CHANGE_BURST_WINDOW_MS) {
                contentChangeBurstWindowStart = now;
                contentChangeBurstCount = 1;
                return false;
            }

            contentChangeBurstCount++;
            if (contentChangeBurstCount < CONTENT_CHANGE_BURST_THRESHOLD) {
                return false;
            }

            lastContentChangeTriggerTime = now;
            contentChangeBurstWindowStart = 0;
            contentChangeBurstCount = 0;
            return true;
        }
    }

    public void launchInterrogation() {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
            boolean isAutoMode = prefs.getBoolean("is_automatic_mode", true);
            Set<String> monitoredApps = prefs.getStringSet("monitored_apps_list", new HashSet<>());
            boolean isMonitoredApp = monitoredApps.contains(baseAppPackage);

            if (isAutoMode && isMonitoredApp) {
                Set<String> customTripwires = prefs.getStringSet("custom_tripwires", new HashSet<>());
                Set<String> userIgnoreList = prefs.getStringSet("user_ignore_list", new HashSet<>());
                java.util.LinkedHashSet<String> uniqueSignatures = new java.util.LinkedHashSet<>();

                synchronized (rollingRingBuffer) {
                    for (WindowRecord record : rollingRingBuffer) {
                        String signature = record.packageName + "|" + record.className;
                        if (!customTripwires.contains(signature) && !userIgnoreList.contains(signature)) {
                            uniqueSignatures.add(signature);
                        }
                    }
                }

                Set<String> pendingQueue = new HashSet<>(prefs.getStringSet("pending_interrogation_classes", new HashSet<>()));
                boolean addedNew = pendingQueue.addAll(uniqueSignatures);

                if (pendingQueue.isEmpty()) {
                    Toast.makeText(this, "LookAway: No new triggers.", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (addedNew) {
                    prefs.edit().putStringSet("pending_interrogation_classes", pendingQueue).apply();
                    Toast.makeText(this, "⚠️ LookAway: " + pendingQueue.size() + " Unsorted Windows Caught", Toast.LENGTH_SHORT).show();
                    if (LookAwayMasterEngine.getInstance() != null) {
                        LookAwayMasterEngine.getInstance().updateDynamicNotification();
                    }
                } else {
                    Toast.makeText(this, "LookAway: No new triggers.", Toast.LENGTH_SHORT).show();
                }
            }
        }, 400);
    }

    public void clickAtCoordinates(int x, int y) {
        android.accessibilityservice.GestureDescription.Builder builder = new android.accessibilityservice.GestureDescription.Builder();
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x, y);
        builder.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50));
        // Gesture dispatch sends only local tap coordinates chosen by local detection.
        dispatchGesture(builder.build(), null, null);
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        resetStorefrontRecovery("shield_destroyed");
        instance = null;
    }
}
