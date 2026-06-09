package com.example.lookaway;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.VpnService;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import eightbitlab.com.blurview.BlurView;
import eightbitlab.com.blurview.RenderScriptBlur;

public class SettingsActivity extends AppCompatActivity {

    private static final int VPN_REQUEST_CODE = 1555;

    // Mode Selector Controls
    private RadioGroup rgModeSelector;
    private RadioButton rbManual, rbAutomatic;
    private Button btnMonitoredApps;
    private TextView tvSliderLabel;
    private SeekBar sbTimeout;

    // Core Controls
    private Switch switchPassiveAdBlock;
    private TextView tvPassiveAdBlockDesc;
    private Button btnBlockedApps;

    // Scanner Settings
    private TextView accuracyLabel;
    private SeekBar accuracySeekBar;
    private TextView accuracyWarning;
    private ImageView imgRoiMulti, imgRoiTop, imgRoiBot;
    private SeekBar roiSeekBar;
    private TextView roiSliderLabel;
    private SeekBar widgetSizeSeekBar;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);

        // Bindings
        rgModeSelector = findViewById(R.id.rg_mode_selector);
        rbManual = findViewById(R.id.rb_manual);
        rbAutomatic = findViewById(R.id.rb_automatic);
        btnMonitoredApps = findViewById(R.id.btn_monitored_apps);
        tvSliderLabel = findViewById(R.id.tv_slider_label);
        sbTimeout = findViewById(R.id.sb_timeout);

        switchPassiveAdBlock = findViewById(R.id.switch_passive_ad_block);
        tvPassiveAdBlockDesc = findViewById(R.id.tv_passive_ad_block_desc);
        btnBlockedApps = findViewById(R.id.btn_blocked_apps);

        accuracyLabel = findViewById(R.id.accuracy_label);
        accuracySeekBar = findViewById(R.id.accuracy_seekbar);
        accuracyWarning = findViewById(R.id.accuracy_warning);
        imgRoiMulti = findViewById(R.id.img_roi_multi);
        imgRoiTop = findViewById(R.id.img_roi_top);
        imgRoiBot = findViewById(R.id.img_roi_bot);
        roiSeekBar = findViewById(R.id.roi_seekbar);
        roiSliderLabel = findViewById(R.id.roi_slider_label);
        widgetSizeSeekBar = findViewById(R.id.widget_size_seekbar);

        setupBlurViews();

        // --- LOAD AND APPLY SAVED STATES ---

        // Timeout Slider Initialization
        int currentTimeout = prefs.getInt("timeout_seconds", 30);
        sbTimeout.setProgress((currentTimeout - 15) / 5);
        tvSliderLabel.setText("Automatically stop scanning after " + currentTimeout + " seconds.");

        // Scanner State Restoration
        int savedAccuracy = prefs.getInt("match_accuracy", 80);
        accuracySeekBar.setProgress(savedAccuracy);
        updateAccuracyUI(savedAccuracy);

        int savedRoi = prefs.getInt("roi_percent", 20);
        roiSeekBar.setProgress(savedRoi);
        updateRoiLabel(savedRoi);
        updateRoiSelection(prefs.getInt("roi_mode", 0));

        widgetSizeSeekBar.setProgress(prefs.getInt("widget_scale", 50));

        // --- LISTENERS ---

        rgModeSelector.setOnCheckedChangeListener((group, checkedId) -> {
            boolean autoSelected = (checkedId == R.id.rb_automatic);
            toggleUiStates(autoSelected);
            prefs.edit().putBoolean("is_automatic_mode", autoSelected).apply();
        });

        sbTimeout.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean b) {
                int seconds = (p * 5) + 15;
                tvSliderLabel.setText("Automatically stop scanning after " + seconds + " seconds.");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt("timeout_seconds", (s.getProgress() * 5) + 15).apply();
            }
        });

        // Suppressing the listener briefly during onResume helps prevent accidental looping
        switchPassiveAdBlock.setOnClickListener(v -> {
            boolean isChecked = switchPassiveAdBlock.isChecked();
            updateWarningColor(isChecked);
            if (isChecked) {
                Intent vpnIntent = VpnService.prepare(SettingsActivity.this);
                if (vpnIntent != null) startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
                else { startVpnService(); prefs.edit().putBoolean("passive_ad_block", true).apply(); }
            } else {
                stopVpnService();
                prefs.edit().putBoolean("passive_ad_block", false).apply();
            }
        });

        btnMonitoredApps.setOnClickListener(v -> startActivity(new Intent(this, MonitoredAppsActivity.class)));
        btnBlockedApps.setOnClickListener(v -> startActivity(new Intent(this, BlockedAppsActivity.class)));

        accuracySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean b) { updateAccuracyUI(p); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { prefs.edit().putInt("match_accuracy", s.getProgress()).apply(); }
        });

        roiSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean b) { updateRoiLabel(p); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { prefs.edit().putInt("roi_percent", s.getProgress()).apply(); }
        });

        widgetSizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean b) {
                prefs.edit().putInt("widget_scale", p).apply();
                Intent i = new Intent("com.example.lookaway.WIDGET_RESIZE");
                i.putExtra("new_scale", p);
                i.setPackage(getPackageName());
                sendBroadcast(i);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        imgRoiMulti.setOnClickListener(v -> updateAndSaveRoiMode(0));
        imgRoiTop.setOnClickListener(v -> updateAndSaveRoiMode(1));
        imgRoiBot.setOnClickListener(v -> updateAndSaveRoiMode(2));
    }

    @Override
    protected void onResume() {
        super.onResume();

        // 1. Force sync the Automatic Mode toggle based on actual backend data
        boolean isAutomatic = prefs.getBoolean("is_automatic_mode", false);
        if (isAutomatic) {
            rbAutomatic.setChecked(true);
        } else {
            rbManual.setChecked(true);
        }
        toggleUiStates(isAutomatic);

        // 2. Force sync the Passive Ad Blocking switch based on actual backend data
        boolean isPassiveEnabled = prefs.getBoolean("passive_ad_block", false);
        switchPassiveAdBlock.setChecked(isPassiveEnabled);
        updateWarningColor(isPassiveEnabled);
    }

    private void toggleUiStates(boolean isAutomatic) {
        btnMonitoredApps.setEnabled(isAutomatic);
        btnMonitoredApps.setAlpha(isAutomatic ? 1.0f : 0.4f);
        sbTimeout.setEnabled(!isAutomatic);
        sbTimeout.setAlpha(!isAutomatic ? 1.0f : 0.4f);
        tvSliderLabel.setAlpha(!isAutomatic ? 1.0f : 0.4f);
    }

    private void updateWarningColor(boolean isActive) {
        tvPassiveAdBlockDesc.setTextColor(isActive ? Color.RED : Color.parseColor("#AAAAAA"));
    }

    private void startVpnService() { startService(new Intent(this, LookAwayVpnService.class)); }

    private void stopVpnService() {
        Intent intent = new Intent(this, LookAwayVpnService.class);
        intent.setAction("STOP_VPN");
        startService(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startVpnService();
            prefs.edit().putBoolean("passive_ad_block", true).apply();
            switchPassiveAdBlock.setChecked(true);
            updateWarningColor(true);
        } else if (requestCode == VPN_REQUEST_CODE) {
            Toast.makeText(this, "VPN Permission Denied.", Toast.LENGTH_SHORT).show();
            switchPassiveAdBlock.setChecked(false);
            updateWarningColor(false);
        }
    }

    private void setupBlurViews() {
        BlurView settingsBlur = findViewById(R.id.settings_blur);
        BlurView adBlockingBlur = findViewById(R.id.ad_blocking_blur);
        BlurView scannerSettingsBlur = findViewById(R.id.scanner_settings_blur);
        ViewGroup rootView = findViewById(android.R.id.content);
        Drawable windowBackground = getWindow().getDecorView().getBackground();
        settingsBlur.setupWith(rootView, new RenderScriptBlur(this)).setFrameClearDrawable(windowBackground).setBlurRadius(3f);
        adBlockingBlur.setupWith(rootView, new RenderScriptBlur(this)).setFrameClearDrawable(windowBackground).setBlurRadius(3f);
        scannerSettingsBlur.setupWith(rootView, new RenderScriptBlur(this)).setFrameClearDrawable(windowBackground).setBlurRadius(3f);
    }

    private void updateAccuracyUI(int progress) {
        accuracyLabel.setText("Target Match Required: " + progress + "%");
        accuracyWarning.setVisibility(progress < 70 ? View.VISIBLE : View.GONE);
    }

    private void updateRoiLabel(int p) { roiSliderLabel.setText("Margin Size: " + (p + 5) + "%"); }

    private void updateAndSaveRoiMode(int m) {
        updateRoiSelection(m);
        prefs.edit().putInt("roi_mode", m).apply();
    }

    private void updateRoiSelection(int m) {
        imgRoiMulti.setSelected(m == 0);
        imgRoiTop.setSelected(m == 1);
        imgRoiBot.setSelected(m == 2);
    }
}