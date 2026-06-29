package com.example.lookaway;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import eightbitlab.com.blurview.BlurView;
import eightbitlab.com.blurview.RenderScriptBlur;

public class MonitoredAppsActivity extends AppCompatActivity {

    private RecyclerView rvAppList;
    private ProgressBar progressBar;
    private AppListAdapter adapter;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_monitored_apps);

        prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);

        // --- NEW: Wire the back button ---
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        rvAppList = findViewById(R.id.rv_app_list);
        progressBar = findViewById(R.id.progress_loading_apps);

        rvAppList.setLayoutManager(new LinearLayoutManager(this));

        setupBlurViews();
        loadInstalledAppsAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().postDelayed(this::showOnboardingStep, 250);
    }

    private void showOnboardingStep() {
        if (!OnboardingManager.shouldShow(this) ||
                OnboardingManager.getStep(this) != OnboardingManager.STEP_TARGET_PRACTICE_NEXT) {
            return;
        }

        if (rvAppList.getVisibility() != View.VISIBLE ||
                rvAppList.getWidth() <= 0 ||
                rvAppList.getHeight() <= 0 ||
                rvAppList.getAdapter() == null) {
            getWindow().getDecorView().postDelayed(this::showOnboardingStep, 200);
            return;
        }

        OnboardingOverlay.show(
                this,
                rvAppList,
                "Pick a Monitored App",
                "This is where you choose the apps you want ADAM to catch ads in.",
                "Target Practice",
                () -> {
                    startActivity(new Intent(this, TargetPracticeActivity.class));
                    finish();
                });
    }

    private void setupBlurViews() {
        float blurRadius = 3f;

        ViewGroup rootView = findViewById(android.R.id.content);
        Drawable windowBackground = getWindow().getDecorView().getBackground();

        BlurView appsBlur = findViewById(R.id.apps_blur_container);

        appsBlur.setupWith(rootView, new RenderScriptBlur(this))
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(blurRadius);
    }

    private void loadInstalledAppsAsync() {
        progressBar.setVisibility(View.VISIBLE);
        rvAppList.setVisibility(View.GONE);

        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN, null);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);

            List<ResolveInfo> resolveInfoList = pm.queryIntentActivities(intent, 0);

            Set<String> activeSet = prefs.getStringSet("monitored_apps_list", new HashSet<>());
            Set<String> historySet = new HashSet<>(prefs.getStringSet("monitored_apps_history", new HashSet<>()));

            // Seed history with current active apps so we don't lose them
            if (!activeSet.isEmpty()) {
                historySet.addAll(activeSet);
                prefs.edit().putStringSet("monitored_apps_history", historySet).apply();
            }

            List<AppItem> selectedApps = new ArrayList<>();
            List<AppItem> previousApps = new ArrayList<>();
            List<AppItem> remainingApps = new ArrayList<>();

            for (ResolveInfo resolveInfo : resolveInfoList) {
                String packageName = resolveInfo.activityInfo.packageName;
                if (packageName.equals(getPackageName())) continue;

                String appName = resolveInfo.loadLabel(pm).toString();
                Drawable icon = resolveInfo.loadIcon(pm);
                boolean isChecked = activeSet.contains(packageName);

                long installTime = 0;
                try {
                    installTime = pm.getPackageInfo(packageName, 0).firstInstallTime;
                } catch (PackageManager.NameNotFoundException ignored) {}

                AppItem item = new AppItem(appName, packageName, icon, isChecked, installTime);

                if (isChecked) {
                    selectedApps.add(item);
                } else if (historySet.contains(packageName)) {
                    previousApps.add(item);
                } else {
                    remainingApps.add(item);
                }
            }

            // Sort Remaining Apps by Newest Install Time
            Collections.sort(remainingApps, (a, b) -> Long.compare(b.installTime, a.installTime));

            List<AppItem> newApps = new ArrayList<>();
            List<AppItem> otherApps = new ArrayList<>();

            for (int i = 0; i < remainingApps.size(); i++) {
                if (i < 5) newApps.add(remainingApps.get(i));
                else otherApps.add(remainingApps.get(i));
            }

            // Sort the categories alphabetically (Except New Apps, which stays chronological)
            java.util.Comparator<AppItem> alphaSort = (a, b) -> a.name.compareToIgnoreCase(b.name);
            Collections.sort(selectedApps, alphaSort);
            Collections.sort(previousApps, alphaSort);
            Collections.sort(otherApps, alphaSort);

            // Compile the final Display List with Headers
            List<AppItem> finalList = new ArrayList<>();

            if (!selectedApps.isEmpty()) {
                finalList.add(new AppItem("Currently Selected Apps"));
                finalList.addAll(selectedApps);
            }
            if (!previousApps.isEmpty()) {
                finalList.add(new AppItem("Previously Selected Apps"));
                finalList.addAll(previousApps);
            }
            if (!newApps.isEmpty()) {
                finalList.add(new AppItem("New Apps"));
                finalList.addAll(newApps);
            }
            if (!otherApps.isEmpty()) {
                finalList.add(new AppItem("All Other Apps"));
                finalList.addAll(otherApps);
            }

            new Handler(Looper.getMainLooper()).post(() -> {
                progressBar.setVisibility(View.GONE);
                rvAppList.setVisibility(View.VISIBLE);
                adapter = new AppListAdapter(finalList);
                rvAppList.setAdapter(adapter);
            });
        }).start();
    }

    // --- DATA MODEL ---
    private static class AppItem {
        boolean isHeader;
        String headerTitle;

        String name;
        String packageName;
        Drawable icon;
        boolean isChecked;
        long installTime;

        // Constructor for App Row
        AppItem(String name, String packageName, Drawable icon, boolean isChecked, long installTime) {
            this.isHeader = false;
            this.name = name;
            this.packageName = packageName;
            this.icon = icon;
            this.isChecked = isChecked;
            this.installTime = installTime;
        }

        // Constructor for Header
        AppItem(String headerTitle) {
            this.isHeader = true;
            this.headerTitle = headerTitle;
        }
    }

    // --- INNER ADAPTER CLASS ---
    private class AppListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_HEADER = 0;
        private static final int TYPE_APP = 1;
        private List<AppItem> apps;

        AppListAdapter(List<AppItem> apps) {
            this.apps = apps;
        }

        @Override
        public int getItemViewType(int position) {
            return apps.get(position).isHeader ? TYPE_HEADER : TYPE_APP;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_HEADER) {
                // Programmatically build the Header View to save making an XML file
                TextView tv = new TextView(parent.getContext());
                tv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                float density = parent.getContext().getResources().getDisplayMetrics().density;
                tv.setPadding((int)(24 * density), (int)(24 * density), (int)(20 * density), (int)(8 * density));
                tv.setTextColor(android.graphics.Color.parseColor("#00e5ff")); // Cyan
                tv.setTextSize(13f);
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
                tv.setAllCaps(true);
                return new HeaderViewHolder(tv);
            } else {
                View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app_row, parent, false);
                return new AppViewHolder(v);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            AppItem app = apps.get(position);

            if (holder instanceof HeaderViewHolder) {
                ((HeaderViewHolder) holder).tvTitle.setText(app.headerTitle);
            } else if (holder instanceof AppViewHolder) {
                AppViewHolder appHolder = (AppViewHolder) holder;
                appHolder.tvAppName.setText(app.name);
                appHolder.ivAppIcon.setImageDrawable(app.icon);

                appHolder.cbSelected.setOnCheckedChangeListener(null);
                appHolder.cbSelected.setChecked(app.isChecked);

                appHolder.cbSelected.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    app.isChecked = isChecked;
                    saveSelectionState();
                });

                appHolder.itemView.setOnClickListener(v -> appHolder.cbSelected.toggle());
            }
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }

        private void saveSelectionState() {
            Set<String> selectedPackages = new HashSet<>();
            Set<String> historySet = new HashSet<>(prefs.getStringSet("monitored_apps_history", new HashSet<>()));

            for (AppItem item : apps) {
                if (item.isHeader) continue;
                if (item.isChecked) {
                    selectedPackages.add(item.packageName);
                    historySet.add(item.packageName); // Add to history bank
                }
            }

            prefs.edit()
                    .putStringSet("monitored_apps_list", selectedPackages)
                    .putStringSet("monitored_apps_history", historySet)
                    .apply();
        }

        class AppViewHolder extends RecyclerView.ViewHolder {
            ImageView ivAppIcon;
            TextView tvAppName;
            CheckBox cbSelected;

            AppViewHolder(@NonNull View itemView) {
                super(itemView);
                ivAppIcon = itemView.findViewById(R.id.iv_app_icon);
                tvAppName = itemView.findViewById(R.id.tv_app_name);
                cbSelected = itemView.findViewById(R.id.cb_app_selected);
            }
        }

        class HeaderViewHolder extends RecyclerView.ViewHolder {
            TextView tvTitle;
            HeaderViewHolder(View v) {
                super(v);
                tvTitle = (TextView) v;
            }
        }
    }
}
