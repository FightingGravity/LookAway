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
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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

    private View toggleLayout;
    private View targetsLayout;
    private View settingsLayout;

    private TextView textToggle;
    private ImageView iconPower;
    private TextView instructionText;

    private boolean isServiceRunning = false;
    private boolean isWaitingForService = false;
    private Intent pendingProjectionData = null;

    private View btnKofi;
    private TextView textAdsWatched;

    private BroadcastReceiver uiStateReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (OpenCVLoader.initDebug()) {
            Log.d(TAG, "OpenCV loaded successfully!");
        } else {
            Log.e(TAG, "OpenCV initialization failed.");
        }

        setContentView(R.layout.activity_main);

        toggleLayout = findViewById(R.id.btn_toggle_overlay);
        targetsLayout = findViewById(R.id.btn_open_targets);
        settingsLayout = findViewById(R.id.btn_open_settings);

        textToggle = findViewById(R.id.text_toggle);
        iconPower = findViewById(R.id.icon_power);
        instructionText = findViewById(R.id.instruction_text);

        btnKofi = findViewById(R.id.btn_kofi);
        textAdsWatched = findViewById(R.id.text_ads_watched);

        setupBlurViews();
        updateAdStats();

        SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
        if (prefs.getBoolean("passive_ad_block", false)) {
            startVpnIfPrepared();
        }

        btnKofi.setOnClickListener(v -> {
            String kofiUrl = "https://ko-fi.com/fightinggravity";
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(kofiUrl));
            startActivity(browserIntent);
        });

        settingsLayout.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        });

        toggleLayout.setOnClickListener(v -> {
            if (!isServiceRunning) {
                if (!isAccessibilityServiceEnabled()) {
                    promptForAccessibility();
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(MainActivity.this)) {
                    requestOverlayPermission();
                } else {
                    requestScreenCapturePermission();
                }
            } else {
                stopMasterEngine();
            }
        });

        targetsLayout.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, TargetsActivity.class);
            startActivity(intent);
        });

        uiStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.example.lookaway.RESET_UI".equals(intent.getAction())) {
                    resetToggleUI();
                    updateAdStats();
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

    private void startVpnIfPrepared() {
        Intent vpnIntent = android.net.VpnService.prepare(this);
        if (vpnIntent == null) {
            startService(new Intent(this, LookAwayVpnService.class));
        } else {
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
        }
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
        BlurView targetsBlur = findViewById(R.id.targets_control_blur);
        BlurView settingsBlur = findViewById(R.id.settings_control_blur);
        BlurView tipJarBlur = findViewById(R.id.tip_jar_blur);

        bannerBlur.setupWith(rootView, new RenderScriptBlur(this)).setFrameClearDrawable(windowBackground).setBlurRadius(blurRadius);
        toggleBlur.setupWith(rootView, new RenderScriptBlur(this)).setFrameClearDrawable(windowBackground).setBlurRadius(blurRadius);
        targetsBlur.setupWith(rootView, new RenderScriptBlur(this)).setFrameClearDrawable(windowBackground).setBlurRadius(blurRadius);
        settingsBlur.setupWith(rootView, new RenderScriptBlur(this)).setFrameClearDrawable(windowBackground).setBlurRadius(blurRadius);
        tipJarBlur.setupWith(rootView, new RenderScriptBlur(this)).setFrameClearDrawable(windowBackground).setBlurRadius(blurRadius);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAdStats();

        if (LookAwayMasterEngine.isRunning) {
            textToggle.setText("DISABLE OVERLAY");
            textToggle.setTextColor(Color.parseColor("#EF4444"));
            iconPower.setColorFilter(Color.parseColor("#EF4444"));
            instructionText.setVisibility(View.VISIBLE);
            isServiceRunning = true;
        } else if (!isWaitingForService) {
            resetToggleUI();
        }
        isWaitingForService = false;
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
                .setTitle("Auto-Clicker Required")
                .setMessage("LookAway needs Accessibility permission to physically click the targets it finds on your screen.\n\nPlease find 'LookAway' in the Installed apps menu and toggle it on.")
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

        textToggle.setText("DISABLE OVERLAY");
        textToggle.setTextColor(Color.parseColor("#EF4444"));
        iconPower.setColorFilter(Color.parseColor("#EF4444"));
        instructionText.setVisibility(View.VISIBLE);
        isServiceRunning = true;
    }

    private void stopMasterEngine() {
        Intent stopIntent = new Intent(MainActivity.this, LookAwayMasterEngine.class);
        stopIntent.setAction("ACTION_DISABLE_OVERLAY");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(stopIntent);
        } else {
            startService(stopIntent);
        }
        resetToggleUI();
    }

    private void resetToggleUI() {
        textToggle.setText("ENABLE OVERLAY");
        textToggle.setTextColor(Color.parseColor("#FFFFFF"));
        iconPower.setColorFilter(Color.parseColor("#FFFFFF"));
        instructionText.setVisibility(View.GONE);
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
                startService(new Intent(this, LookAwayVpnService.class));
            } else {
                getSharedPreferences("LookAwayPrefs", MODE_PRIVATE).edit().putBoolean("is_automatic_mode", false).apply();
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