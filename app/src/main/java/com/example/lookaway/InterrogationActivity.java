package com.example.lookaway;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class InterrogationActivity extends AppCompatActivity {

    private LinearLayout itemsContainer;
    private Button btnCancel, btnSave, btnIgnoreAll;

    private SharedPreferences prefs;
    private Set<String> pendingSignatures;

    private final Map<String, Integer> sortingStateMap = new HashMap<>();
    private final List<Button> allIgnoreButtons = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.layout_interrogation);

        // Strip any OS-level dimming or blurring to preserve battery and performance
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        // Force translucent bottom alignment behavior natively
        getWindow().setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        getWindow().setGravity(android.view.Gravity.BOTTOM);

        itemsContainer = findViewById(R.id.items_container);
        btnCancel = findViewById(R.id.btn_cancel);
        btnSave = findViewById(R.id.btn_save);
        btnIgnoreAll = findViewById(R.id.btn_ignore_all);

        prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);

        Set<String> rawPending = prefs.getStringSet("pending_interrogation_classes", new HashSet<>());
        pendingSignatures = new HashSet<>(rawPending);

        Set<String> currentCustomTripwires = prefs.getStringSet("custom_tripwires", new HashSet<>());
        Set<String> currentUserIgnoreList = prefs.getStringSet("user_ignore_list", new HashSet<>());

        pendingSignatures.removeAll(currentCustomTripwires);
        pendingSignatures.removeAll(currentUserIgnoreList);

        if (pendingSignatures.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText("No untracked window signatures found in the queue.");
            emptyView.setTextColor(Color.parseColor("#9CA3AF"));
            emptyView.setPadding(0, 20, 0, 20);
            itemsContainer.addView(emptyView);
            btnIgnoreAll.setVisibility(View.GONE);
        } else {
            populateInterrogationList();
        }

        btnCancel.setOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> commitSortingLogic());

        btnIgnoreAll.setOnClickListener(v -> {
            for (Button btn : allIgnoreButtons) {
                btn.performClick();
            }
        });
    }

    private boolean isKnownAdClass(String className) {
        String lower = className.toLowerCase(Locale.ROOT);
        return lower.contains("com.google.android.gms.ads.adactivity") ||
                lower.contains("com.applovin.adview.applovinfullscreenactivity") ||
                lower.contains("com.unity3d.services.ads.adunit.adunitactivity") ||
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
                lower.contains("com.google.android.finsky.transparentmainactivity");
    }

    private void populateInterrogationList() {
        float density = getResources().getDisplayMetrics().density;
        int verticalPadding = (int) (12 * density);

        for (final String signature : pendingSignatures) {
            sortingStateMap.put(signature, 0);

            String[] parts = signature.split("\\|");
            String displayPackage = parts[0];

            String rawClass = parts.length > 1 ? parts[1] : "UnknownWindowNode";
            String displayClass = rawClass;

            if (displayClass.contains(".")) {
                displayClass = displayClass.substring(displayClass.lastIndexOf(".") + 1);
            }

            final LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setPadding(0, verticalPadding, 0, verticalPadding);
            rowLayout.setWeightSum(3);

            LinearLayout textBlock = new LinearLayout(this);
            textBlock.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f);
            textBlock.setLayoutParams(textParams);

            TextView classText = new TextView(this);
            classText.setText(displayClass); // Show the pretty name

            if (isKnownAdClass(rawClass)) {
                classText.setTextColor(Color.parseColor("#EAB308"));
            } else {
                classText.setTextColor(Color.WHITE);
            }

            classText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            classText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

            TextView pkgText = new TextView(this);
            pkgText.setText(displayPackage);
            pkgText.setTextColor(Color.parseColor("#9CA3AF"));
            pkgText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);

            textBlock.addView(classText);
            textBlock.addView(pkgText);

            LinearLayout actionBlock = new LinearLayout(this);
            actionBlock.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f);
            actionBlock.setLayoutParams(actionParams);
            actionBlock.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);

            final Button btnTrigger = new Button(this);
            btnTrigger.setText("Trigger");
            btnTrigger.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            btnTrigger.setTextColor(Color.WHITE);
            btnTrigger.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#374151")));

            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams((int)(75 * density), (int)(36 * density));
            btnParams.setMarginEnd((int)(4 * density));
            btnTrigger.setLayoutParams(btnParams);

            final Button btnIgnore = new Button(this);
            btnIgnore.setText("Ignore");
            btnIgnore.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            btnIgnore.setTextColor(Color.WHITE);
            btnIgnore.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#374151")));
            btnIgnore.setLayoutParams(new LinearLayout.LayoutParams((int)(75 * density), (int)(36 * density)));

            allIgnoreButtons.add(btnIgnore);

            btnTrigger.setOnClickListener(v -> {
                if (sortingStateMap.get(signature) == 1) {
                    sortingStateMap.put(signature, 0);
                    btnTrigger.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#374151")));
                } else {
                    sortingStateMap.put(signature, 1);
                    btnTrigger.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#00e5ff")));
                    btnIgnore.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#374151")));
                }
            });

            btnIgnore.setOnClickListener(v -> {
                if (sortingStateMap.get(signature) == 2) {
                    sortingStateMap.put(signature, 0);
                    btnIgnore.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#374151")));
                } else {
                    sortingStateMap.put(signature, 2);
                    btnIgnore.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#6B7280")));
                    btnTrigger.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#374151")));
                }
            });

            actionBlock.addView(btnTrigger);
            actionBlock.addView(btnIgnore);

            rowLayout.addView(textBlock);
            rowLayout.addView(actionBlock);

            View divider = new View(this);
            divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
            divider.setBackgroundColor(Color.parseColor("#1F2937"));

            itemsContainer.addView(rowLayout);
            itemsContainer.addView(divider);
        }
    }

    private void commitSortingLogic() {
        Set<String> currentCustomTripwires = new HashSet<>(prefs.getStringSet("custom_tripwires", new HashSet<>()));
        Set<String> currentUserIgnoreList = new HashSet<>(prefs.getStringSet("user_ignore_list", new HashSet<>()));

        // 1. Create a single Editor instance to batch everything efficiently
        SharedPreferences.Editor editor = prefs.edit();
        long currentTime = System.currentTimeMillis();

        int triggerCount = 0;
        int ignoreCount = 0;

        for (Map.Entry<String, Integer> entry : sortingStateMap.entrySet()) {
            String signature = entry.getKey();
            int state = entry.getValue();

            if (state == 1) { // Marked as TRIGGER
                currentCustomTripwires.add(signature);
                pendingSignatures.remove(signature);

                // --- BACKFILL TRIGGER DATA ---
                editor.putInt("count_" + signature, 1);
                editor.putLong("last_seen_" + signature, currentTime);

                triggerCount++;
            } else if (state == 2) { // Marked as IGNORE
                currentUserIgnoreList.add(signature);
                pendingSignatures.remove(signature);

                // --- BACKFILL IGNORE DATA ---
                editor.putLong("last_seen_" + signature, currentTime);

                ignoreCount++;
            }
        }

        // 2. Apply all changes (Sets + Counts + Timestamps) in one clean operation
        editor.putStringSet("custom_tripwires", currentCustomTripwires)
                .putStringSet("user_ignore_list", currentUserIgnoreList)
                .putStringSet("pending_interrogation_classes", pendingSignatures)
                .apply();

        if (com.example.lookaway.LookAwayMasterEngine.getInstance() != null) {
            com.example.lookaway.LookAwayMasterEngine.getInstance().updateDynamicNotification();
        }

        Toast.makeText(this, "Saved: " + triggerCount + " Triggers, " + ignoreCount + " Ignored", Toast.LENGTH_SHORT).show();
        finish();
    }
}
