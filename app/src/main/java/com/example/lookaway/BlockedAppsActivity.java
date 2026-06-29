package com.example.lookaway;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eightbitlab.com.blurview.BlurView;
import eightbitlab.com.blurview.RenderScriptBlur;

public class BlockedAppsActivity extends AppCompatActivity {

    private RecyclerView rvApps;
    private ProgressBar progressBar;
    private AppAdapter adapter;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_blocked_apps);

        prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        rvApps = findViewById(R.id.rv_apps);
        progressBar = findViewById(R.id.progress_loading);
        rvApps.setLayoutManager(new LinearLayoutManager(this));

        setupBlurViews();
        loadAppsInBackground();
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().postDelayed(this::showOnboardingStep, 250);
    }

    private void showOnboardingStep() {
        if (!OnboardingManager.shouldShow(this) ||
                OnboardingManager.getStep(this) != OnboardingManager.STEP_SAM_ENABLE) {
            return;
        }

        if (rvApps.getVisibility() != View.VISIBLE ||
                rvApps.getWidth() <= 0 ||
                rvApps.getHeight() <= 0 ||
                rvApps.getAdapter() == null) {
            getWindow().getDecorView().postDelayed(this::showOnboardingStep, 200);
            return;
        }

        OnboardingOverlay.show(
                this,
                rvApps,
                "Pick a SAM App",
                "This is the SAM app list. Here you can choose the apps on you phone that you want to act as if they were in airplane mode.",
                "Continue Tutorial",
                () -> {
                    startActivity(new android.content.Intent(this, MainActivity.class)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
                    finish();
                });
    }

    private void setupBlurViews() {
        float blurRadius = 3f;
        ViewGroup rootView = findViewById(android.R.id.content);
        Drawable windowBackground = getWindow().getDecorView().getBackground();

        BlurView appsBlur = findViewById(R.id.blocked_apps_blur_container);
        appsBlur.setupWith(rootView, new RenderScriptBlur(this))
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(blurRadius);
    }

    private void loadAppsInBackground() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);

            Set<String> activeSet = prefs.getStringSet("vpn_blocked_apps_list", new HashSet<>());
            Set<String> historySet = new HashSet<>(prefs.getStringSet("vpn_blocked_apps_history", new HashSet<>()));

            if (!activeSet.isEmpty()) {
                historySet.addAll(activeSet);
                prefs.edit().putStringSet("vpn_blocked_apps_history", historySet).apply();
            }

            List<AppItem> selectedApps = new ArrayList<>();
            List<AppItem> previousApps = new ArrayList<>();
            List<AppItem> remainingApps = new ArrayList<>();

            for (ApplicationInfo packageInfo : packages) {
                if ((packageInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    String appName = pm.getApplicationLabel(packageInfo).toString();
                    String packageName = packageInfo.packageName;
                    Drawable icon = pm.getApplicationIcon(packageInfo);
                    boolean isBlocked = activeSet.contains(packageName);

                    long installTime = 0;
                    try {
                        installTime = pm.getPackageInfo(packageName, 0).firstInstallTime;
                    } catch (PackageManager.NameNotFoundException ignored) {}

                    AppItem item = new AppItem(appName, packageName, icon, isBlocked, installTime);

                    if (isBlocked) {
                        selectedApps.add(item);
                    } else if (historySet.contains(packageName)) {
                        previousApps.add(item);
                    } else {
                        remainingApps.add(item);
                    }
                }
            }

            Collections.sort(remainingApps, (a, b) -> Long.compare(b.installTime, a.installTime));

            List<AppItem> newApps = new ArrayList<>();
            List<AppItem> otherApps = new ArrayList<>();

            for (int i = 0; i < remainingApps.size(); i++) {
                if (i < 5) newApps.add(remainingApps.get(i));
                else otherApps.add(remainingApps.get(i));
            }

            java.util.Comparator<AppItem> alphaSort = (a, b) -> a.appName.compareToIgnoreCase(b.appName);
            Collections.sort(selectedApps, alphaSort);
            Collections.sort(previousApps, alphaSort);
            Collections.sort(otherApps, alphaSort);

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

            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                adapter = new AppAdapter(finalList);
                rvApps.setAdapter(adapter);
            });
        });
    }

    // --- INNER CLASSES ---
    class AppItem {
        boolean isHeader;
        String headerTitle;

        String appName;
        String packageName;
        Drawable icon;
        boolean isBlocked;
        long installTime;

        AppItem(String appName, String packageName, Drawable icon, boolean isBlocked, long installTime) {
            this.isHeader = false;
            this.appName = appName;
            this.packageName = packageName;
            this.icon = icon;
            this.isBlocked = isBlocked;
            this.installTime = installTime;
        }

        AppItem(String headerTitle) {
            this.isHeader = true;
            this.headerTitle = headerTitle;
        }
    }

    class AppAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_APP = 1;
        private List<AppItem> appList;

        AppAdapter(List<AppItem> appList) {
            this.appList = appList;
        }

        @Override
        public int getItemViewType(int position) {
            return appList.get(position).isHeader ? TYPE_HEADER : TYPE_APP;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_HEADER) {
                TextView tv = new TextView(parent.getContext());
                tv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                float density = parent.getContext().getResources().getDisplayMetrics().density;
                tv.setPadding((int)(24 * density), (int)(24 * density), (int)(20 * density), (int)(8 * density));
                tv.setTextColor(android.graphics.Color.parseColor("#00e5ff"));
                tv.setTextSize(13f);
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
                tv.setAllCaps(true);
                return new HeaderViewHolder(tv);
            } else {
                View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app_toggle, parent, false);
                return new AppViewHolder(view);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            AppItem app = appList.get(position);

            if (holder instanceof HeaderViewHolder) {
                ((HeaderViewHolder) holder).tvTitle.setText(app.headerTitle);
            } else if (holder instanceof AppViewHolder) {
                AppViewHolder appHolder = (AppViewHolder) holder;
                appHolder.tvAppName.setText(app.appName);
                appHolder.imgIcon.setImageDrawable(app.icon);

                appHolder.cbBlock.setOnCheckedChangeListener(null);
                appHolder.cbBlock.setChecked(app.isBlocked);

                appHolder.cbBlock.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    app.isBlocked = isChecked;
                    saveSelectionState();
                });

                // --- NEW: Entire row acts as a touch target to toggle the CheckBox ---
                appHolder.itemView.setOnClickListener(v -> appHolder.cbBlock.toggle());
            }
        }

        @Override
        public int getItemCount() {
            return appList.size();
        }

        private void saveSelectionState() {
            Set<String> selectedPackages = new HashSet<>();
            Set<String> historySet = new HashSet<>(prefs.getStringSet("vpn_blocked_apps_history", new HashSet<>()));

            for (AppItem item : appList) {
                if (item.isHeader) continue;
                if (item.isBlocked) {
                    selectedPackages.add(item.packageName);
                    historySet.add(item.packageName);
                }
            }

            prefs.edit()
                    .putStringSet("vpn_blocked_apps_list", selectedPackages)
                    .putStringSet("vpn_blocked_apps_history", historySet)
                    .apply();
        }

        class AppViewHolder extends RecyclerView.ViewHolder {
            ImageView imgIcon;
            TextView tvAppName;
            CheckBox cbBlock; // Swapped from Switch

            AppViewHolder(@NonNull View itemView) {
                super(itemView);
                imgIcon = itemView.findViewById(R.id.img_app_icon);
                tvAppName = itemView.findViewById(R.id.tv_app_name);
                cbBlock = itemView.findViewById(R.id.cb_app_block);
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
