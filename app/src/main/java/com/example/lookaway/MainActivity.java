package com.example.lookaway;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout; // <-- THE MISSING IMPORT THAT CAUSED THE RED ERROR!
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import org.opencv.android.OpenCVLoader;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import eightbitlab.com.blurview.BlurView;
import eightbitlab.com.blurview.RenderScriptBlur;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "LookAway";
    private static final int DRAW_OVER_OTHER_APP_PERMISSION_REQUEST_CODE = 1222;
    private static final int SCREEN_CAPTURE_PERMISSION_REQUEST_CODE = 1333;
    private static final int FGS_PERMISSION_REQUEST_CODE = 1444;
    private static final int VPN_REQUEST_CODE = 1555;

    // UI Elements
    private View toggleLayout;
    private View targetsLayout;
    private View settingsLayout;
    private TextView textToggle;
    private ImageView iconPower;
    private TextView titleAdam;
    private View btnKofi;
    private TextView textAdsWatched;

    // SAM UI Elements
    private View btnToggleSam;
    private TextView textSamToggle;
    private ImageView iconSamPower;
    private TextView titleSam;
    private boolean isServiceRunning = false;
    private boolean isWaitingForService = false;
    private Intent pendingProjectionData = null;

    private BroadcastReceiver uiStateReceiver;

    private LinearLayout btnInfoPage;
    private LinearLayout btnReplayTutorialHome;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (OpenCVLoader.initDebug()) {
            Log.d(TAG, "OpenCV loaded successfully!");
        } else {
            Log.e(TAG, "OpenCV initialization failed.");
        }

        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        // Bindings
        toggleLayout = findViewById(R.id.btn_toggle_overlay);
        targetsLayout = findViewById(R.id.btn_open_targets);
        settingsLayout = findViewById(R.id.btn_open_settings);
        btnInfoPage = findViewById(R.id.btn_info_page); // <-- INFO BINDING ADDED
        btnReplayTutorialHome = findViewById(R.id.btn_replay_tutorial_home);
        textToggle = findViewById(R.id.text_toggle);
        iconPower = findViewById(R.id.icon_power);
        titleAdam = findViewById(R.id.title_adam);
        btnKofi = findViewById(R.id.btn_kofi);
        textAdsWatched = findViewById(R.id.text_ads_watched);

        // SAM Bindings
        btnToggleSam = findViewById(R.id.btn_toggle_sam);
        textSamToggle = findViewById(R.id.text_sam_toggle);
        iconSamPower = findViewById(R.id.icon_sam_power);
        titleSam = findViewById(R.id.title_sam);

        setupBlurViews();
        updateAdStats();
        TutorialTargetSeeder.seedIfNeeded(this);

        applyDynamicCaps(titleAdam, "Ad Detect & Advance Mode");
        applyDynamicCaps(titleSam, "Selective Airplane Mode");

        SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
        if (prefs.getBoolean("passive_ad_block", false)) {
            startVpnIfPrepared();
        }

        // Listeners
        btnKofi.setOnClickListener(v -> {
            String kofiUrl = "https://ko-fi.com/fightinggravity";
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(kofiUrl));
            startActivity(browserIntent);
        });

        settingsLayout.setOnClickListener(v -> {
            if (OnboardingManager.shouldShow(this) &&
                    OnboardingManager.getStep(this) == OnboardingManager.STEP_SAM_SETTINGS) {
                OnboardingOverlay.remove(this);
                OnboardingManager.setStep(this, OnboardingManager.STEP_SAM_APPS);
            }
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        });

        targetsLayout.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, TargetsActivity.class);
            startActivity(intent);
        });

        // <-- INFO LISTENER ADDED
        if (btnInfoPage != null) {
            btnInfoPage.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, InfoActivity.class);
                startActivity(intent);
            });
        }

        if (btnReplayTutorialHome != null) {
            btnReplayTutorialHome.setOnClickListener(v -> OnboardingManager.start(this));
        }

        toggleLayout.setOnClickListener(v -> handleAdamToggle());

        btnToggleSam.setOnClickListener(v -> handleSamToggle(prefs));

        // UI Reset Receiver
        uiStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.example.lookaway.RESET_UI".equals(intent.getAction())) {

                    // 1. Check ADAM's true state before touching its UI
                    if (LookAwayMasterEngine.isRunning) {
                        textToggle.setText("Disable ADAM");
                        textToggle.setTextColor(Color.parseColor("#EF4444")); // Red
                        iconPower.setColorFilter(Color.parseColor("#EF4444"));
                        isServiceRunning = true;
                    } else {
                        resetToggleUI(); // Only turn white if actually dead
                    }

                    // 2. Update stats
                    updateAdStats();

                    // 3. Sync SAM's true state
                    SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
                    boolean isPassiveEnabled = prefs.getBoolean("passive_ad_block", false);
                    updateSamUI(isPassiveEnabled);
                }
            }
        };

        ContextCompat.registerReceiver(
                this,
                uiStateReceiver,
                new IntentFilter("com.example.lookaway.RESET_UI"),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    private void applyDynamicCaps(TextView targetView, String rawString) {
        SpannableString spannable = new SpannableString(rawString);

        int baseSizePx = (int) (16 * getResources().getDisplayMetrics().scaledDensity);
        int largeSizePx = (int) (26 * getResources().getDisplayMetrics().scaledDensity);

        for (int i = 0; i < rawString.length(); i++) {
            char c = rawString.charAt(i);
            if (Character.isUpperCase(c)) {
                // Large Size + Cyan Color for Caps
                spannable.setSpan(new AbsoluteSizeSpan(largeSizePx), i, i + 1, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(new ForegroundColorSpan(Color.parseColor("#00e5ff")), i, i + 1, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                // Normal Size + Pure White Color for everything else
                spannable.setSpan(new AbsoluteSizeSpan(baseSizePx), i, i + 1, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(new ForegroundColorSpan(Color.parseColor("#FFFFFF")), i, i + 1, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        targetView.setText(spannable);
    }

    private void updateSamUI(boolean isActive) {
        if (isActive) {
            textSamToggle.setText("Disable SAM");
            textSamToggle.setTextColor(Color.parseColor("#EF4444")); // Red
            iconSamPower.setColorFilter(Color.parseColor("#EF4444"));
        } else {
            textSamToggle.setText("Enable SAM");
            textSamToggle.setTextColor(Color.parseColor("#FFFFFF")); // White
            iconSamPower.setColorFilter(Color.parseColor("#FFFFFF"));
        }
    }

    private void startVpnIfPrepared() {
        Intent vpnIntent = android.net.VpnService.prepare(this);
        if (vpnIntent == null) {
            startService(new Intent(this, LookAwayVpnService.class));
        } else {
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
        }
    }

    private void startVpnService() {
        startService(new Intent(this, LookAwayVpnService.class));
    }

    private void stopVpnService() {
        Intent intent = new Intent(this, LookAwayVpnService.class);
        intent.setAction("STOP_VPN");
        startService(intent);
    }

    private void updateAdStats() {
        SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
        long totalMillis = 0;

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        Calendar cal = Calendar.getInstance();

        for (int i = 0; i < 7; i++) {
            String dateKey = "active_time_" + sdf.format(cal.getTime());
            totalMillis += prefs.getLong(dateKey, 0);
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }

        long totalMinutesSaved = TimeUnit.MILLISECONDS.toMinutes(totalMillis);
        textAdsWatched.setText("LookAway has watched " + totalMinutesSaved + " minutes of ads for you this week.");
    }

    private void setupBlurViews() {
        float blurRadius = 3f;

        ViewGroup rootView = findViewById(android.R.id.content);
        Drawable windowBackground = getWindow().getDecorView().getBackground();

        BlurView bannerBlur = findViewById(R.id.banner_container);
        BlurView toggleBlur = findViewById(R.id.widget_control_blur);
        BlurView samBlur = findViewById(R.id.sam_control_blur);
        BlurView targetsBlur = findViewById(R.id.targets_control_blur);
        BlurView settingsBlur = findViewById(R.id.settings_control_blur);
        BlurView tipJarBlur = findViewById(R.id.tip_jar_blur);

        bannerBlur.setupWith(rootView, new RenderScriptBlur(this)).setFrameClearDrawable(windowBackground).setBlurRadius(blurRadius);
        toggleBlur.setupWith(rootView, new RenderScriptBlur(this)).setFrameClearDrawable(windowBackground).setBlurRadius(blurRadius);
        samBlur.setupWith(rootView, new RenderScriptBlur(this)).setFrameClearDrawable(windowBackground).setBlurRadius(blurRadius);
        targetsBlur.setupWith(rootView, new RenderScriptBlur(this)).setFrameClearDrawable(windowBackground).setBlurRadius(blurRadius);
        settingsBlur.setupWith(rootView, new RenderScriptBlur(this)).setFrameClearDrawable(windowBackground).setBlurRadius(blurRadius);
        tipJarBlur.setupWith(rootView, new RenderScriptBlur(this)).setFrameClearDrawable(windowBackground).setBlurRadius(blurRadius);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAdStats();

        if (LookAwayMasterEngine.isRunning) {
            textToggle.setText("Disable ADAM");
            textToggle.setTextColor(Color.parseColor("#EF4444"));
            iconPower.setColorFilter(Color.parseColor("#EF4444"));
            isServiceRunning = true;
        } else if (!isWaitingForService) {
            resetToggleUI();
        }
        isWaitingForService = false;

        SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
        boolean isPassiveEnabled = prefs.getBoolean("passive_ad_block", false);
        updateSamUI(isPassiveEnabled);
        getWindow().getDecorView().postDelayed(this::showOnboardingStep, 250);
    }

    private void handleSamToggle(SharedPreferences prefs) {
        boolean isCurrentlyActive = prefs.getBoolean("passive_ad_block", false);
        boolean isOnboardingSamStep = OnboardingManager.shouldShow(this) &&
                OnboardingManager.getStep(this) == OnboardingManager.STEP_SAM_ENABLE;

        if (isOnboardingSamStep && isCurrentlyActive) {
            OnboardingManager.setStep(this, OnboardingManager.STEP_ADAM_ENABLE);
            showOnboardingStep();
            return;
        }

        if (isOnboardingSamStep) {
            OnboardingOverlay.remove(this);
            new AlertDialog.Builder(this)
                    .setTitle("SAM Uses Android's VPN Slot")
                    .setMessage("Android calls this a VPN because SAM uses the local VPN interface to keep only your selected apps offline. LookAway is not sending your traffic to a remote server.")
                    .setPositiveButton("Continue", (dialog, which) -> beginSamToggle(prefs))
                    .setNegativeButton("Not Now", null)
                    .show();
            return;
        }

        beginSamToggle(prefs);
    }

    private void beginSamToggle(SharedPreferences prefs) {
        boolean isCurrentlyActive = prefs.getBoolean("passive_ad_block", false);
        boolean isChecked = !isCurrentlyActive;

        if (isChecked) {
            Intent vpnIntent = VpnService.prepare(MainActivity.this);
            if (vpnIntent != null) {
                startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
            } else {
                startVpnService();
                prefs.edit().putBoolean("passive_ad_block", true).apply();
                updateSamUI(true);
                advanceOnboardingAfterSamEnabled();
            }
        } else {
            stopVpnService();
            prefs.edit().putBoolean("passive_ad_block", false).apply();
            updateSamUI(false);
        }
    }

    private void handleAdamToggle() {
        boolean isOnboardingAdamStep = OnboardingManager.shouldShow(this) &&
                OnboardingManager.getStep(this) == OnboardingManager.STEP_ADAM_ENABLE;

        if (isOnboardingAdamStep && (isServiceRunning || LookAwayMasterEngine.isRunning)) {
            advanceOnboardingAfterAdamEnabled();
            return;
        }

        if (!isServiceRunning) {
            if (isOnboardingAdamStep) {
                OnboardingOverlay.remove(this);
                new AlertDialog.Builder(this)
                        .setTitle("ADAM Needs Local Screen Access")
                        .setMessage("ADAM uses Accessibility, overlay, and screen capture so it can see ad targets you teach it and tap them for you. The image processing stays on this phone. NOTE: If your phone allows for per app screen recording, please select 'Share entire screen' or ADAM will not see anything.")
                        .setPositiveButton("Continue", (dialog, which) -> beginAdamEnableFlow())
                        .setNegativeButton("Not Now", null)
                        .show();
            } else {
                beginAdamEnableFlow();
            }
        } else {
            stopMasterEngine();
        }
    }

    private void beginAdamEnableFlow() {
        if (!isAccessibilityServiceEnabled()) {
            promptForAccessibility();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(MainActivity.this)) {
            requestOverlayPermission();
        } else {
            requestScreenCapturePermission();
        }
    }

    private void advanceOnboardingAfterSamEnabled() {
        if (OnboardingManager.shouldShow(this) &&
                OnboardingManager.getStep(this) == OnboardingManager.STEP_SAM_ENABLE) {
            OnboardingManager.setStep(this, OnboardingManager.STEP_ADAM_ENABLE);
            getWindow().getDecorView().postDelayed(this::showOnboardingStep, 350);
        }
    }

    private void advanceOnboardingAfterAdamEnabled() {
        if (OnboardingManager.shouldShow(this) &&
                OnboardingManager.getStep(this) == OnboardingManager.STEP_ADAM_ENABLE) {
            OnboardingManager.setStep(this, OnboardingManager.STEP_ADAM_SETTINGS);
            getWindow().getDecorView().postDelayed(() ->
                    startActivity(new Intent(this, SettingsActivity.class)), 500);
        }
    }

    private void showOnboardingStep() {
        if (!OnboardingManager.shouldShow(this)) return;

        int step = OnboardingManager.getStep(this);
        if (step == OnboardingManager.STEP_WELCOME) {
            OnboardingOverlay.show(
                    this,
                    null,
                    "Welcome to LookAway",
                    "Let's set up Selective Airplane Mode (SAM) and Ad Detect and Advance Mode (ADAM) so ads get quieter, shorter, and easier to ignore. You can skip this tutorial any time.",
                    "Start Tutorial",
                    () -> {
                        OnboardingManager.setStep(this, OnboardingManager.STEP_PERMISSIONS);
                        showOnboardingStep();
                    });
        } else if (step == OnboardingManager.STEP_PERMISSIONS) {
            OnboardingOverlay.show(
                    this,
                    null,
                    "What LookAway Needs",
                    "Accessibility lets ADAM notice app and ad windows and tap targets you save. Screen capture is used locally only while the eye is open. Overlay shows the floating eye. SAM uses Android's local VPN slot for apps you choose.",
                    "Set Up SAM",
                    () -> {
                        OnboardingManager.setStep(this, OnboardingManager.STEP_SAM_SETTINGS);
                        showOnboardingStep();
                    });
        } else if (step == OnboardingManager.STEP_SAM_SETTINGS) {
            OnboardingOverlay.showTapTarget(
                    this,
                    settingsLayout,
                    "Open Settings",
                    "SAM setup starts in Settings. Tap the highlighted Settings button to choose the apps SAM can keep offline.");
        } else if (step == OnboardingManager.STEP_SAM_ENABLE) {
            OnboardingOverlay.showTapTarget(
                    this,
                    btnToggleSam,
                    "Enable SAM",
                    "Tap Enable SAM. Android will ask for VPN access because SAM uses a local VPN slot to block selected apps from reaching the network.");
        } else if (step == OnboardingManager.STEP_ADAM_ENABLE) {
            OnboardingOverlay.showTapTarget(
                    this,
                    toggleLayout,
                    "Enable ADAM",
                    "Tap Enable ADAM and grant the Android prompts. When the floating eye appears, we can teach it what to tap.");
        } else if (step == OnboardingManager.STEP_FINISH) {
            OnboardingOverlay.show(
                    this,
                    null,
                    "You're Ready",
                    "Start with one game, watch a few ads, and add targets when ADAM misses something. One more thing: push and hold ADAM for 2 seconds to move the eye anywhere you want.",
                    "Done",
                    () -> OnboardingManager.complete(this));
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        int accessibilityEnabled = 0;
        final String service = getPackageName() + "/" + LookAwayShieldService.class.getCanonicalName();
        try {
            accessibilityEnabled = Settings.Secure.getInt(getApplicationContext().getContentResolver(), android.provider.Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            Log.e(TAG, "Accessibility setting not found: " + e.getMessage());
        }
        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(getApplicationContext().getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                return settingValue.contains(service);
            }
        }
        return false;
    }

    private void promptForAccessibility() {
        new AlertDialog.Builder(this)
                .setTitle("Accessibility Permission Required")
                .setMessage("LookAway uses Accessibility to detect app/ad window changes and perform taps you configure. It does not read, store, or transmit personal screen content.\n\nPlease find 'LookAway' in the Installed apps menu and toggle it on.")
                .setPositiveButton("Go to Settings", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    startActivity(intent);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void requestOverlayPermission() {
        Toast.makeText(this, "Please enable 'Display over other apps'", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, DRAW_OVER_OTHER_APP_PERMISSION_REQUEST_CODE);
    }

    private void requestScreenCapturePermission() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager != null) {
            startActivityForResult(manager.createScreenCaptureIntent(), SCREEN_CAPTURE_PERMISSION_REQUEST_CODE);
        }
    }

    private void checkAndStartService(Intent data) {
        List<String> permissionsToRequest = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION);
            }
        }
        if (!permissionsToRequest.isEmpty()) {
            pendingProjectionData = data;
            requestPermissions(permissionsToRequest.toArray(new String[0]), FGS_PERMISSION_REQUEST_CODE);
            return;
        }
        startMasterEngine(data);
    }

    private void startMasterEngine(Intent projectionTokenData) {
        isWaitingForService = true;
        Intent serviceIntent = new Intent(MainActivity.this, LookAwayMasterEngine.class);
        if (projectionTokenData != null) {
            serviceIntent.putExtra("projection_data", projectionTokenData);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        textToggle.setText("Disable ADAM");
        textToggle.setTextColor(Color.parseColor("#EF4444"));
        iconPower.setColorFilter(Color.parseColor("#EF4444"));
        isServiceRunning = true;
        advanceOnboardingAfterAdamEnabled();
    }

    private void stopMasterEngine() {
        Intent stopIntent = new Intent(MainActivity.this, LookAwayMasterEngine.class);
        stopIntent.setAction("ACTION_DISABLE_OVERLAY");
        startService(stopIntent);

        resetToggleUI();
    }

    private void resetToggleUI() {
        textToggle.setText("Enable ADAM");
        textToggle.setTextColor(Color.parseColor("#FFFFFF"));
        iconPower.setColorFilter(Color.parseColor("#FFFFFF"));
        isServiceRunning = false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == FGS_PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) startMasterEngine(pendingProjectionData);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);

        if (requestCode == DRAW_OVER_OTHER_APP_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                requestScreenCapturePermission();
            }
        } else if (requestCode == SCREEN_CAPTURE_PERMISSION_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                checkAndStartService(data);
            }
        } else if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startVpnService();
                prefs.edit().putBoolean("passive_ad_block", true).apply();
                updateSamUI(true);
                advanceOnboardingAfterSamEnabled();
            } else {
                Toast.makeText(this, "VPN Permission Denied.", Toast.LENGTH_SHORT).show();
                prefs.edit().putBoolean("passive_ad_block", false).apply();
                updateSamUI(false);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (uiStateReceiver != null) {
            unregisterReceiver(uiStateReceiver);
        }
    }
}
