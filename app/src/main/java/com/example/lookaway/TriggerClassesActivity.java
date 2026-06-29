package com.example.lookaway;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class TriggerClassesActivity extends AppCompatActivity {

    private RecyclerView rvTriggerClasses;
    private TextView tvEmptyState;
    private View triggerBlurContainer;
    private LinearLayout btnBack;
    private LinearLayout headerContainer;
    private LinearLayout selectionHeaderContainer;
    private ImageView btnCancelSelection;
    private TextView tvSelectionCount;
    private Button btnBulkDelete;
    private LinearLayout btnUndo;
    private SharedPreferences prefs;
    private RuleAdapter adapter;
    private List<RuleItem> ruleList = new ArrayList<>();
    private boolean isSelectionMode = false;
    private Set<String> selectedSignatures = new HashSet<>();

    // --- THE 5-STEP UNDO STACK ---
    private LinkedList<UndoAction> undoStack = new LinkedList<>();

    // --- THE LIVE UPDATE LISTENER ---
    private SharedPreferences.OnSharedPreferenceChangeListener liveUpdateListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trigger_classes);

        prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);

        rvTriggerClasses = findViewById(R.id.rv_trigger_classes);
        tvEmptyState = findViewById(R.id.tv_empty_state);
        triggerBlurContainer = findViewById(R.id.trigger_blur_container);
        btnBack = findViewById(R.id.btn_back_dashboard);

        headerContainer = findViewById(R.id.header_container);
        selectionHeaderContainer = findViewById(R.id.selection_header_container);
        btnCancelSelection = findViewById(R.id.btn_cancel_selection);
        tvSelectionCount = findViewById(R.id.tv_selection_count);
        btnBulkDelete = findViewById(R.id.btn_bulk_delete);
        btnUndo = findViewById(R.id.btn_undo_action);

        rvTriggerClasses.setLayoutManager(new LinearLayoutManager(this));

        btnBack.setOnClickListener(v -> finish());
        btnCancelSelection.setOnClickListener(v -> exitSelectionMode());
        btnBulkDelete.setOnClickListener(v -> executeBulkDelete());
        btnUndo.setOnClickListener(v -> executeUndo());

        // Configure the live-listener
        liveUpdateListener = (sharedPreferences, key) -> {
            // Only refresh the UI if a relevant key changed (ignore widget coords, etc)
            if (key != null && (key.startsWith("count_") || key.startsWith("last_seen_") ||
                    key.equals("custom_tripwires") || key.equals("user_ignore_list"))) {
                runOnUiThread(this::loadRulesWithHeaders);
            }
        };

        loadRulesWithHeaders();
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(liveUpdateListener);
        getWindow().getDecorView().postDelayed(this::showOnboardingStep, 250);
    }

    @Override
    protected void onPause() {
        super.onPause();
        prefs.unregisterOnSharedPreferenceChangeListener(liveUpdateListener);
    }

    private void loadRulesWithHeaders() {
        ruleList.clear();

        Set<String> customTripwires = prefs.getStringSet("custom_tripwires", new HashSet<>());
        Set<String> userIgnoreList = prefs.getStringSet("user_ignore_list", new HashSet<>());

        List<RuleItem> triggers = new ArrayList<>();
        List<RuleItem> ignores = new ArrayList<>();

        for (String signature : customTripwires) {
            triggers.add(new RuleItem(signature, RuleType.TRIGGER, prefs.getInt("count_" + signature, 0), prefs.getLong("last_seen_" + signature, 0)));
        }
        for (String signature : userIgnoreList) {
            ignores.add(new RuleItem(signature, RuleType.IGNORE, 0, prefs.getLong("last_seen_" + signature, 0)));
        }

        Collections.sort(triggers, (a, b) -> Integer.compare(b.triggerCount, a.triggerCount));
        Collections.sort(ignores, (a, b) -> a.className.compareToIgnoreCase(b.className));

        if (!triggers.isEmpty()) {
            ruleList.add(new RuleItem("Active Triggers"));
            ruleList.addAll(triggers);
        }
        if (!ignores.isEmpty()) {
            ruleList.add(new RuleItem("Ignored Windows"));
            ruleList.addAll(ignores);
        }

        if (ruleList.isEmpty()) {
            tvEmptyState.setVisibility(View.VISIBLE);
            rvTriggerClasses.setVisibility(View.GONE);
        } else {
            tvEmptyState.setVisibility(View.GONE);
            rvTriggerClasses.setVisibility(View.VISIBLE);

            if (adapter == null) {
                adapter = new RuleAdapter();
                rvTriggerClasses.setAdapter(adapter);
            } else {
                adapter.notifyDataSetChanged();
            }
        }
    }

    private void showOnboardingStep() {
        if (!OnboardingManager.shouldShow(this) ||
                OnboardingManager.getStep(this) != OnboardingManager.STEP_TRIGGER_MANAGER) {
            return;
        }

        if (triggerBlurContainer.getWidth() <= 0 || triggerBlurContainer.getHeight() <= 0) {
            getWindow().getDecorView().postDelayed(this::showOnboardingStep, 200);
            return;
        }

        OnboardingOverlay.show(
                this,
                triggerBlurContainer,
                "Manage Triggers",
                "Triggers open ADAM automatically. Ignored windows tell ADAM what to leave alone or when the ad flow is over. Most users only need this screen when an ad does not open automatically.",
                "Finish Tutorial",
                () -> {
                    OnboardingManager.setStep(this, OnboardingManager.STEP_FINISH);
                    startActivity(new android.content.Intent(this, MainActivity.class)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
                    finish();
                });
    }

    // --- UNDO ENGINE ---

    private void pushToUndoStack(ActionType type, List<RuleItem> items) {
        // Stop live-listening while we manipulate data so the screen doesn't jitter
        prefs.unregisterOnSharedPreferenceChangeListener(liveUpdateListener);

        undoStack.addFirst(new UndoAction(type, new ArrayList<>(items)));
        if (undoStack.size() > 5) {
            undoStack.removeLast(); // Keep memory maxed at 5
        }

        btnUndo.setVisibility(View.VISIBLE);
        prefs.registerOnSharedPreferenceChangeListener(liveUpdateListener);
    }

    private void executeUndo() {
        if (undoStack.isEmpty()) return;

        prefs.unregisterOnSharedPreferenceChangeListener(liveUpdateListener);
        UndoAction lastAction = undoStack.removeFirst();
        SharedPreferences.Editor editor = prefs.edit();

        Set<String> customTripwires = new HashSet<>(prefs.getStringSet("custom_tripwires", new HashSet<>()));
        Set<String> userIgnoreList = new HashSet<>(prefs.getStringSet("user_ignore_list", new HashSet<>()));

        if (lastAction.type == ActionType.DELETE) {
            // Restore deleted items exactly where they belong
            for (RuleItem item : lastAction.affectedItems) {
                if (item.type == RuleType.TRIGGER) {
                    customTripwires.add(item.fullSignature);
                    editor.putInt("count_" + item.fullSignature, item.triggerCount);
                } else {
                    userIgnoreList.add(item.fullSignature);
                }
            }
            Toast.makeText(this, "Restored " + lastAction.affectedItems.size() + " items.", Toast.LENGTH_SHORT).show();
        }
        else if (lastAction.type == ActionType.TOGGLE) {
            // Reverse the toggle
            for (RuleItem item : lastAction.affectedItems) {
                if (item.type == RuleType.TRIGGER) {
                    userIgnoreList.remove(item.fullSignature);
                    customTripwires.add(item.fullSignature);
                } else {
                    customTripwires.remove(item.fullSignature);
                    userIgnoreList.add(item.fullSignature);
                }
            }
            Toast.makeText(this, "Action reversed.", Toast.LENGTH_SHORT).show();
        }

        editor.putStringSet("custom_tripwires", customTripwires);
        editor.putStringSet("user_ignore_list", userIgnoreList);
        editor.apply();

        if (undoStack.isEmpty()) btnUndo.setVisibility(View.GONE);

        prefs.registerOnSharedPreferenceChangeListener(liveUpdateListener);
        loadRulesWithHeaders();
    }

    // --- SELECTION LOGIC ---

    private void toggleRowSelection(String signature) {
        if (selectedSignatures.contains(signature)) selectedSignatures.remove(signature);
        else selectedSignatures.add(signature);

        if (selectedSignatures.isEmpty()) exitSelectionMode();
        else {
            updateSelectionUI();
            adapter.notifyDataSetChanged();
        }
    }

    private void enterSelectionMode(String initialSignature) {
        isSelectionMode = true;
        selectedSignatures.clear();
        selectedSignatures.add(initialSignature);

        headerContainer.setVisibility(View.GONE);
        selectionHeaderContainer.setVisibility(View.VISIBLE);
        btnBulkDelete.setVisibility(View.VISIBLE);

        updateSelectionUI();
        adapter.notifyDataSetChanged();
    }

    private void exitSelectionMode() {
        isSelectionMode = false;
        selectedSignatures.clear();

        headerContainer.setVisibility(View.VISIBLE);
        selectionHeaderContainer.setVisibility(View.GONE);
        btnBulkDelete.setVisibility(View.GONE);

        adapter.notifyDataSetChanged();
    }

    private void updateSelectionUI() {
        tvSelectionCount.setText(selectedSignatures.size() + " Selected");
    }

    private void executeBulkDelete() {
        List<RuleItem> itemsToDelete = new ArrayList<>();
        for (RuleItem item : ruleList) {
            if (!item.isHeader && selectedSignatures.contains(item.fullSignature)) {
                itemsToDelete.add(item);
            }
        }

        pushToUndoStack(ActionType.DELETE, itemsToDelete); // Log for Undo

        Set<String> customTripwires = new HashSet<>(prefs.getStringSet("custom_tripwires", new HashSet<>()));
        Set<String> userIgnoreList = new HashSet<>(prefs.getStringSet("user_ignore_list", new HashSet<>()));
        SharedPreferences.Editor editor = prefs.edit();

        for (String signature : selectedSignatures) {
            customTripwires.remove(signature);
            userIgnoreList.remove(signature);
            editor.remove("count_" + signature);
        }

        editor.putStringSet("custom_tripwires", customTripwires);
        editor.putStringSet("user_ignore_list", userIgnoreList);
        editor.apply();

        Toast.makeText(this, "Deleted " + selectedSignatures.size() + " triggers.", Toast.LENGTH_SHORT).show();

        exitSelectionMode();
        loadRulesWithHeaders();
    }

    // --- SINGLE ROW ACTIONS ---

    private void deleteRule(RuleItem item) {
        pushToUndoStack(ActionType.DELETE, Collections.singletonList(item)); // Log for Undo

        String targetKey = (item.type == RuleType.TRIGGER) ? "custom_tripwires" : "user_ignore_list";
        Set<String> currentSet = new HashSet<>(prefs.getStringSet(targetKey, new HashSet<>()));
        currentSet.remove(item.fullSignature);
        prefs.edit().putStringSet(targetKey, currentSet).apply();

        if (item.type == RuleType.TRIGGER) prefs.edit().remove("count_" + item.fullSignature).apply();

        loadRulesWithHeaders();
    }

    private void toggleRuleState(RuleItem item) {
        pushToUndoStack(ActionType.TOGGLE, Collections.singletonList(item)); // Log for Undo

        Set<String> customTripwires = new HashSet<>(prefs.getStringSet("custom_tripwires", new HashSet<>()));
        Set<String> userIgnoreList = new HashSet<>(prefs.getStringSet("user_ignore_list", new HashSet<>()));

        if (item.type == RuleType.TRIGGER) {
            customTripwires.remove(item.fullSignature);
            userIgnoreList.add(item.fullSignature);
        } else {
            userIgnoreList.remove(item.fullSignature);
            customTripwires.add(item.fullSignature);
        }

        prefs.edit().putStringSet("custom_tripwires", customTripwires).putStringSet("user_ignore_list", userIgnoreList).apply();
        loadRulesWithHeaders();
    }

    // --- DATA MODELS ---

    private enum ActionType { DELETE, TOGGLE }

    private static class UndoAction {
        ActionType type;
        List<RuleItem> affectedItems;
        UndoAction(ActionType type, List<RuleItem> items) { this.type = type; this.affectedItems = items; }
    }

    private enum RuleType { TRIGGER, IGNORE }

    private static class RuleItem {
        boolean isHeader;
        String headerTitle, fullSignature, packageName, className;
        RuleType type;
        int triggerCount;
        long lastSeenMs;

        RuleItem(String headerTitle) { this.isHeader = true; this.headerTitle = headerTitle; }

        RuleItem(String signature, RuleType type, int triggerCount, long lastSeenMs) {
            this.isHeader = false;
            this.fullSignature = signature;
            this.type = type;
            this.triggerCount = triggerCount;
            this.lastSeenMs = lastSeenMs;
            String[] parts = signature.split("\\|");
            this.packageName = parts[0];
            String rawClass = parts.length > 1 ? parts[1] : "UnknownClass";
            this.className = rawClass.contains(".") ? rawClass.substring(rawClass.lastIndexOf(".") + 1) : rawClass;
        }

        public String getFormattedLastSeen() {
            if (lastSeenMs <= 0) return "Never seen";
            long diff = System.currentTimeMillis() - lastSeenMs;
            if (diff < 60000) return "Just now";
            if (diff < 3600000) return (diff / 60000) + " mins ago";
            if (diff < 86400000) return (diff / 3600000) + " hours ago";
            return new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(new Date(lastSeenMs));
        }
    }

    // --- ADAPTER ---

    private class RuleAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_HEADER = 0, TYPE_ROW = 1;

        @Override public int getItemViewType(int position) { return ruleList.get(position).isHeader ? TYPE_HEADER : TYPE_ROW; }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_HEADER) {
                TextView tv = new TextView(parent.getContext());
                float density = parent.getContext().getResources().getDisplayMetrics().density;
                tv.setPadding((int)(24 * density), (int)(24 * density), (int)(20 * density), (int)(8 * density));
                tv.setTextColor(Color.parseColor("#00e5ff"));
                tv.setTextSize(13f);
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
                tv.setAllCaps(true);
                return new RecyclerView.ViewHolder(tv) {};
            }
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_rule_row, parent, false);
            return new RuleViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            RuleItem item = ruleList.get(position);

            if (holder.getItemViewType() == TYPE_HEADER) {
                ((TextView) holder.itemView).setText(item.headerTitle);
                return;
            }

            RuleViewHolder vh = (RuleViewHolder) holder;
            vh.tvClassName.setText(item.className);
            vh.tvPackageName.setText(item.packageName);

            // Bind the new Timestamp!
            if (vh.tvLastSeen != null) {
                vh.tvLastSeen.setText("Last seen: " + item.getFormattedLastSeen());
            }

            if (item.type == RuleType.TRIGGER) {
                vh.tvBadge.setText("TRIGGER");
                vh.tvBadge.setBackgroundColor(Color.parseColor("#06B6D4"));
                vh.tvTriggerCount.setVisibility(View.VISIBLE);
                vh.tvTriggerCount.setText("Trigger Count: " + item.triggerCount);
            } else {
                vh.tvBadge.setText("IGNORE");
                vh.tvBadge.setBackgroundColor(Color.parseColor("#6B7280"));
                vh.tvTriggerCount.setVisibility(View.GONE);
            }

            if (isSelectionMode) {
                vh.checkboxRow.setVisibility(View.VISIBLE);
                vh.btnDelete.setVisibility(View.GONE);
                vh.checkboxRow.setChecked(selectedSignatures.contains(item.fullSignature));
            } else {
                vh.checkboxRow.setVisibility(View.GONE);
                vh.btnDelete.setVisibility(View.VISIBLE);
            }

            vh.itemView.setOnClickListener(v -> {
                if (isSelectionMode) toggleRowSelection(item.fullSignature);
                else toggleRuleState(item);
            });

            vh.itemView.setOnLongClickListener(v -> {
                if (!isSelectionMode) {
                    enterSelectionMode(item.fullSignature);
                    return true;
                }
                return false;
            });

            vh.btnDelete.setOnClickListener(v -> deleteRule(item));
            vh.tvBadge.setOnClickListener(v -> { if (!isSelectionMode) toggleRuleState(item); });
            vh.checkboxRow.setOnClickListener(v -> toggleRowSelection(item.fullSignature));
        }

        @Override public int getItemCount() { return ruleList.size(); }
    }

    class RuleViewHolder extends RecyclerView.ViewHolder {
        TextView tvClassName, tvPackageName, tvBadge, tvTriggerCount, tvLastSeen; // Added tvLastSeen
        ImageView btnDelete;
        CheckBox checkboxRow;

        RuleViewHolder(@NonNull View itemView) {
            super(itemView);
            tvClassName = itemView.findViewById(R.id.tv_row_class_name);
            tvPackageName = itemView.findViewById(R.id.tv_row_package_name);
            tvBadge = itemView.findViewById(R.id.tv_row_badge);
            tvTriggerCount = itemView.findViewById(R.id.tv_row_trigger_count);
            tvLastSeen = itemView.findViewById(R.id.tv_row_last_seen); // Bound the UI component
            btnDelete = itemView.findViewById(R.id.btn_delete_row);
            checkboxRow = itemView.findViewById(R.id.checkbox_row);
        }
    }
}
