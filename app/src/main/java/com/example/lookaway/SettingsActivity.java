package com.example.lookaway;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import eightbitlab.com.blurview.BlurView;
import eightbitlab.com.blurview.RenderScriptBlur;

public class SettingsActivity extends AppCompatActivity {

    // Mode Selector Controls
    private ScrollView settingsScroll;
    private RadioGroup rgModeSelector;
    private RadioButton rbManual, rbAutomatic;
    private TextView tvSliderLabel;
    private SeekBar sbTimeout;

    // App & Target Management
    private Button btnMonitoredApps;
    private Button btnTriggerClasses;
    private Button btnBlockedApps;
    private SwitchCompat swAutoCloseVibration;
    private TextView vibrationIntensityLabel;
    private SeekBar vibrationIntensitySeekBar;
    private SwitchCompat swMuteMediaAuto, swMuteMediaManual;

    // Scanner Settings
    private TextView accuracyLabel;
    private SeekBar accuracySeekBar;
    private TextView accuracyWarning;
    private ImageView imgRoiMulti, imgRoiTop, imgRoiBot;
    private SeekBar roiSeekBar;
    private TextView roiSliderLabel;
    private SeekBar widgetSizeSeekBar;

    private SharedPreferences prefs;
    private boolean isRestoringState = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);

        // Bindings
        settingsScroll = findViewById(R.id.settings_scroll);
        rgModeSelector = findViewById(R.id.rg_mode_selector);
        rbManual = findViewById(R.id.rb_manual);
        rbAutomatic = findViewById(R.id.rb_automatic);
        tvSliderLabel = findViewById(R.id.tv_slider_label);
        sbTimeout = findViewById(R.id.sb_timeout);

        btnMonitoredApps = findViewById(R.id.btn_monitored_apps);
        btnTriggerClasses = findViewById(R.id.btn_trigger_classes);
        btnBlockedApps = findViewById(R.id.btn_blocked_apps);
        swAutoCloseVibration = findViewById(R.id.sw_auto_close_vibration);
        vibrationIntensityLabel = findViewById(R.id.vibration_intensity_label);
        vibrationIntensitySeekBar = findViewById(R.id.vibration_intensity_seekbar);
        swMuteMediaAuto = findViewById(R.id.sw_mute_media_auto);
        swMuteMediaManual = findViewById(R.id.sw_mute_media_manual);

        accuracyLabel = findViewById(R.id.accuracy_label);
        accuracySeekBar = findViewById(R.id.accuracy_seekbar);
        accuracyWarning = findViewById(R.id.accuracy_warning);
        imgRoiMulti = findViewById(R.id.img_roi_multi);
        imgRoiTop = findViewById(R.id.img_roi_top);
        imgRoiBot = findViewById(R.id.img_roi_bot);
        roiSeekBar = findViewById(R.id.roi_seekbar);
        roiSliderLabel = findViewById(R.id.roi_slider_label);
        widgetSizeSeekBar = findViewById(R.id.widget_size_seekbar);
        findViewById(R.id.btn_back_settings).setOnClickListener(v -> finish());

        setupBlurViews();

        // --- LOAD AND APPLY SAVED STATES ---

        int currentTimeout = prefs.getInt("timeout_seconds", 30);
        sbTimeout.setProgress((currentTimeout - 15) / 5);
        tvSliderLabel.setText("Automatically stop scanning after " + currentTimeout + " seconds.");

        int savedAccuracy = prefs.getInt("match_accuracy", 35);
        accuracySeekBar.setProgress(savedAccuracy);
        updateAccuracyUI(savedAccuracy);

        int savedRoi = prefs.getInt("roi_percent", 30);
        roiSeekBar.setProgress(savedRoi);
        updateRoiLabel(savedRoi);
        updateRoiSelection(prefs.getInt("roi_mode", 1));

        widgetSizeSeekBar.setProgress(prefs.getInt("widget_scale", 50));
        swAutoCloseVibration.setChecked(prefs.getBoolean("auto_close_vibration", true));
        int savedVibrationIntensity = normalizeVibrationIntensity(prefs.getInt("auto_close_vibration_intensity", 180));
        vibrationIntensitySeekBar.setProgress(savedVibrationIntensity);
        updateVibrationIntensityUI(savedVibrationIntensity);
        updateVibrationSliderState(swAutoCloseVibration.isChecked());
        migrateMuteMediaPrefsIfNeeded();
        swMuteMediaAuto.setChecked(prefs.getBoolean("mute_media_auto_mode", true));
        swMuteMediaManual.setChecked(prefs.getBoolean("mute_media_manual_mode", false));

        // --- LISTENERS ---

        rgModeSelector.setOnCheckedChangeListener((group, checkedId) -> {
            boolean autoSelected = (checkedId == R.id.rb_automatic);
            toggleUiStates(autoSelected);
            prefs.edit().putBoolean("is_automatic_mode", autoSelected).apply();
            if (autoSelected && !isRestoringState) {
                advanceOnboardingAfterAutomaticMode();
            }
        });
        rbAutomatic.setOnClickListener(v -> {
            prefs.edit().putBoolean("is_automatic_mode", true).apply();
            advanceOnboardingAfterAutomaticMode();
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

        btnMonitoredApps.setOnClickListener(v -> {
            if (OnboardingManager.shouldShow(this) &&
                    OnboardingManager.getStep(this) == OnboardingManager.STEP_MONITORED_APPS) {
                OnboardingOverlay.remove(this);
                OnboardingManager.setStep(this, OnboardingManager.STEP_TARGET_PRACTICE_NEXT);
            }
            startActivity(new Intent(this, MonitoredAppsActivity.class));
        });
        btnTriggerClasses.setOnClickListener(v -> {
            if (OnboardingManager.shouldShow(this) &&
                    OnboardingManager.getStep(this) == OnboardingManager.STEP_SCANNER_TUNING) {
                OnboardingOverlay.remove(this);
                OnboardingManager.setStep(this, OnboardingManager.STEP_TRIGGER_MANAGER);
            }
            startActivity(new Intent(this, TriggerClassesActivity.class));
        });
        btnBlockedApps.setOnClickListener(v -> {
            if (OnboardingManager.shouldShow(this) &&
                    OnboardingManager.getStep(this) == OnboardingManager.STEP_SAM_APPS) {
                OnboardingOverlay.remove(this);
                OnboardingManager.setStep(this, OnboardingManager.STEP_SAM_ENABLE);
            }
            startActivity(new Intent(this, BlockedAppsActivity.class));
        });
        swAutoCloseVibration.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("auto_close_vibration", isChecked).apply();
            updateVibrationSliderState(isChecked);
        });

        swMuteMediaAuto.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean("mute_media_auto_mode", isChecked).apply());

        swMuteMediaManual.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean("mute_media_manual_mode", isChecked).apply());

        vibrationIntensitySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean b) {
                updateVibrationIntensityUI(normalizeVibrationIntensity(p));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                int intensity = normalizeVibrationIntensity(s.getProgress());
                s.setProgress(intensity);
                updateVibrationIntensityUI(intensity);
                prefs.edit().putInt("auto_close_vibration_intensity", intensity).apply();
            }
        });

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
        isRestoringState = true;
        boolean isAutomatic = prefs.getBoolean("is_automatic_mode", true);
        if (isAutomatic) {
            rbAutomatic.setChecked(true);
        } else {
            rbManual.setChecked(true);
        }
        toggleUiStates(isAutomatic);
        isRestoringState = false;
        swAutoCloseVibration.setChecked(prefs.getBoolean("auto_close_vibration", true));
        int vibrationIntensity = normalizeVibrationIntensity(prefs.getInt("auto_close_vibration_intensity", 180));
        vibrationIntensitySeekBar.setProgress(vibrationIntensity);
        updateVibrationIntensityUI(vibrationIntensity);
        updateVibrationSliderState(swAutoCloseVibration.isChecked());
        migrateMuteMediaPrefsIfNeeded();
        swMuteMediaAuto.setChecked(prefs.getBoolean("mute_media_auto_mode", true));
        swMuteMediaManual.setChecked(prefs.getBoolean("mute_media_manual_mode", false));
        getWindow().getDecorView().postDelayed(this::showOnboardingStep, 250);
    }

    private void showOnboardingStep() {
        if (!OnboardingManager.shouldShow(this)) return;

        int step = OnboardingManager.getStep(this);
        if (step == OnboardingManager.STEP_SAM_APPS) {
            settingsScroll.post(() -> {
                settingsScroll.smoothScrollTo(0, btnBlockedApps.getBottom());
                settingsScroll.postDelayed(() -> OnboardingOverlay.showTapTarget(
                        this,
                        btnBlockedApps,
                        "Choose SAM Apps",
                        "Tap SAM Apps. This is where you tell SAM which apps to keep offline."), 350);
            });
        } else if (step == OnboardingManager.STEP_ADAM_SETTINGS) {
            settingsScroll.post(() -> {
                settingsScroll.smoothScrollTo(0, 0);
                settingsScroll.postDelayed(() -> OnboardingOverlay.showTapTarget(
                        this,
                        rbAutomatic,
                        "ADAM Modes",
                        "Tap Auto Mode. Automatic Mode opens ADAM when LookAway senses an ad trigger in a monitored app."), 250);
            });
        } else if (step == OnboardingManager.STEP_MONITORED_APPS) {
            OnboardingOverlay.showTapTarget(
                    this,
                    btnMonitoredApps,
                    "Select Monitored Apps",
                    "Tap Monitored Apps. Choose the games where ADAM should watch for ad triggers. Start with one game; you can always add more.");
        } else if (step == OnboardingManager.STEP_SCANNER_TUNING) {
            OnboardingOverlay.showTapTarget(
                    this,
                    btnTriggerClasses,
                    "Scanner Tuning",
                    "These settings control how ADAM scans. Tap Manage Triggers next so you know where automatic triggers live.");
        }
    }

    private void advanceOnboardingAfterAutomaticMode() {
        if (OnboardingManager.shouldShow(this) &&
                OnboardingManager.getStep(this) == OnboardingManager.STEP_ADAM_SETTINGS) {
            OnboardingOverlay.remove(this);
            OnboardingManager.setStep(this, OnboardingManager.STEP_MONITORED_APPS);
            getWindow().getDecorView().postDelayed(this::showOnboardingStep, 250);
        }
    }

    private void toggleUiStates(boolean isAutomatic) {
        btnMonitoredApps.setEnabled(isAutomatic);
        btnMonitoredApps.setAlpha(isAutomatic ? 1.0f : 0.4f);

        btnTriggerClasses.setEnabled(isAutomatic);
        btnTriggerClasses.setAlpha(isAutomatic ? 1.0f : 0.4f);

        sbTimeout.setEnabled(!isAutomatic);
        sbTimeout.setAlpha(!isAutomatic ? 1.0f : 0.4f);
        tvSliderLabel.setAlpha(!isAutomatic ? 1.0f : 0.4f);
    }

    private void setupBlurViews() {
        BlurView mainSettingsBlur = findViewById(R.id.settings_main_blur);
        ViewGroup rootView = findViewById(android.R.id.content);
        Drawable windowBackground = getWindow().getDecorView().getBackground();
        mainSettingsBlur.setupWith(rootView, new RenderScriptBlur(this)).setFrameClearDrawable(windowBackground).setBlurRadius(3f);
    }

    private int normalizeVibrationIntensity(int intensity) {
        int clamped = Math.max(45, Math.min(300, intensity));
        return Math.round(clamped / 5f) * 5;
    }

    private void updateVibrationIntensityUI(int intensity) {
        vibrationIntensityLabel.setText("Vibration Strength: " + intensity);
    }

    private void updateVibrationSliderState(boolean isEnabled) {
        vibrationIntensitySeekBar.setEnabled(isEnabled);
        vibrationIntensitySeekBar.setAlpha(isEnabled ? 1.0f : 0.4f);
        vibrationIntensityLabel.setAlpha(isEnabled ? 1.0f : 0.4f);
    }

    private void migrateMuteMediaPrefsIfNeeded() {
        if (prefs.contains("mute_media_pref_migrated")) return;

        boolean oldMuteEnabled = prefs.getBoolean("mute_media_while_scanning", false);
        String oldMode = prefs.getString("mute_media_scan_mode", "automatic");
        SharedPreferences.Editor editor = prefs.edit();
        if (oldMuteEnabled) {
            editor.putBoolean("mute_media_auto_mode", "automatic".equals(oldMode));
            editor.putBoolean("mute_media_manual_mode", "manual".equals(oldMode));
        }
        editor.putBoolean("mute_media_pref_migrated", true).apply();
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
