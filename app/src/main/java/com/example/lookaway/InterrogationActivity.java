package com.example.lookaway;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class InterrogationActivity extends AppCompatActivity {

    private LinearLayout itemsContainer;
    private Button btnCancel, btnSave;

    private SharedPreferences prefs;
    private Set<String> pendingSignatures;

    // Tracks state mapping: signature -> 1 (Cyan/Trigger), 2 (Gray/Ignore), 0 (Unselected)
    private final Map<String, Integer> sortingStateMap = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.layout_interrogation);

        // Force translucent bottom alignment behavior natively
        getWindow().setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        getWindow().setGravity(android.view.Gravity.BOTTOM);

        itemsContainer = findViewById(R.id.items_container);
        btnCancel = findViewById(R.id.btn_cancel);
        btnSave = findViewById(R.id.btn_save);

        prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
        Set<String> rawPending = prefs.getStringSet("pending_interrogation_classes", new HashSet<>());
        pendingSignatures = new HashSet<>(rawPending);

        if (pendingSignatures.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText("No untracked window signatures found in the queue.");
            emptyView.setTextColor(Color.parseColor("#9CA3AF"));
            emptyView.setPadding(0, 20, 0, 20);
            itemsContainer.addView(emptyView);
        } else {
            populateInterrogationList();
        }

        btnCancel.setOnClickListener(v -> finish());

        btnSave.setOnClickListener(v -> {
            commitSortingLogic();
        });
    }

    private void populateInterrogationList() {
        float density = getResources().getDisplayMetrics().density;
        int verticalPadding = (int) (12 * density);

        for (final String signature : pendingSignatures) {
            // Default track state: Unselected
            sortingStateMap.put(signature, 0);

            // Split signature into Package Name and Class Name values
            String[] parts = signature.split("\\|");
            String displayPackage = parts[0];
            String displayClass = parts.length > 1 ? parts[1] : "UnknownWindowNode";

            // Clean up paths for readable UI row lists
            if (displayClass.contains(".")) {
                displayClass = displayClass.substring(displayClass.lastIndexOf(".") + 1);
            }

            // Row Container
            final LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setPadding(0, verticalPadding, 0, verticalPadding);
            rowLayout.setWeightSum(3);

            // Text Metadata block
            LinearLayout textBlock = new LinearLayout(this);
            textBlock.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f);
            textBlock.setLayoutParams(textParams);

            TextView classText = new TextView(this);
            classText.setText(displayClass);
            classText.setTextColor(Color.WHITE);
            classText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            classText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

            TextView pkgText = new TextView(this);
            pkgText.setText(displayPackage);
            pkgText.setTextColor(Color.parseColor("#9CA3AF"));
            pkgText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);

            textBlock.addView(classText);
            textBlock.addView(pkgText);

            // Action Toggles
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

            // Binary Toggling Operations
            btnTrigger.setOnClickListener(v -> {
                if (sortingStateMap.get(signature) == 1) {
                    sortingStateMap.put(signature, 0); // Deselect
                    btnTrigger.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#374151")));
                } else {
                    sortingStateMap.put(signature, 1); // Select Cyan/Trigger
                    btnTrigger.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#06B6D4"))); // Cyan
                    btnIgnore.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#374151")));
                }
            });

            btnIgnore.setOnClickListener(v -> {
                if (sortingStateMap.get(signature) == 2) {
                    sortingStateMap.put(signature, 0); // Deselect
                    btnIgnore.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#374151")));
                } else {
                    sortingStateMap.put(signature, 2); // Select Gray/Ignore
                    btnIgnore.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#6B7280"))); // Gray
                    btnTrigger.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#374151")));
                }
            });

            actionBlock.addView(btnTrigger);
            actionBlock.addView(btnIgnore);

            rowLayout.addView(textBlock);
            rowLayout.addView(actionBlock);

            // Divider Line decoration
            View divider = new View(this);
            divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
            divider.setBackgroundColor(Color.parseColor("#1F2937"));

            itemsContainer.addView(rowLayout);
            itemsContainer.addView(divider);
        }
    }

    private void commitSortingLogic() {
        // Read out current custom lists
        Set<String> currentCustomTripwires = new HashSet<>(prefs.getStringSet("custom_tripwires", new HashSet<>()));
        Set<String> currentUserIgnoreList = new HashSet<>(prefs.getStringSet("user_ignore_list", new HashSet<>()));

        int triggerCount = 0;
        int ignoreCount = 0;

        for (Map.Entry<String, Integer> entry : sortingStateMap.entrySet()) {
            String signature = entry.getKey();
            int state = entry.getValue();

            if (state == 1) {
                // Sorting to active custom tripwire target paths
                currentCustomTripwires.add(signature);
                pendingSignatures.remove(signature);
                triggerCount++;
            } else if (state == 2) {
                // Sorting to silent bypass ignore lists
                currentUserIgnoreList.add(signature);
                pendingSignatures.remove(signature);
                ignoreCount++;
            }
        }

        // Commit updating states back to persistent preferences file
        prefs.edit()
                .putStringSet("custom_tripwires", currentCustomTripwires)
                .putStringSet("user_ignore_list", currentUserIgnoreList)
                .putStringSet("pending_interrogation_classes", pendingSignatures)
                .apply();

        // Clear the active Status Bar Notification
        android.app.NotificationManager manager = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && pendingSignatures.isEmpty()) {
            manager.cancel(9915);
        }

        Toast.makeText(this, "Saved: " + triggerCount + " Tripwires, " + ignoreCount + " Ignored", Toast.LENGTH_SHORT).show();
        finish();
    }
}