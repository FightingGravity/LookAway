package com.example.lookaway;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Switch;
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

public class BlockedAppsActivity extends AppCompatActivity {

    private RecyclerView rvApps;
    private ProgressBar progressBar;
    private AppAdapter adapter;
    private SharedPreferences prefs;
    private Set<String> blockedAppsSet;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_blocked_apps);

        prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
        blockedAppsSet = new HashSet<>(prefs.getStringSet("vpn_blocked_apps_list", new HashSet<>()));

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        rvApps = findViewById(R.id.rv_apps);
        progressBar = findViewById(R.id.progress_loading);
        rvApps.setLayoutManager(new LinearLayoutManager(this));

        loadAppsInBackground();
    }

    private void loadAppsInBackground() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<AppItem> appItems = new ArrayList<>();

            for (ApplicationInfo packageInfo : packages) {
                // Filter out system apps to keep the list clean
                if ((packageInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    String appName = pm.getApplicationLabel(packageInfo).toString();
                    String packageName = packageInfo.packageName;
                    Drawable icon = pm.getApplicationIcon(packageInfo);
                    boolean isBlocked = blockedAppsSet.contains(packageName);
                    appItems.add(new AppItem(appName, packageName, icon, isBlocked));
                }
            }

            Collections.sort(appItems, (a, b) -> a.appName.compareToIgnoreCase(b.appName));

            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                adapter = new AppAdapter(appItems);
                rvApps.setAdapter(adapter);
            });
        });
    }

    private void saveSelection(String packageName, boolean isBlocked) {
        if (isBlocked) {
            blockedAppsSet.add(packageName);
        } else {
            blockedAppsSet.remove(packageName);
        }
        prefs.edit().putStringSet("vpn_blocked_apps_list", blockedAppsSet).apply();
    }

    // --- INNER CLASSES ---
    class AppItem {
        String appName;
        String packageName;
        Drawable icon;
        boolean isBlocked;

        AppItem(String appName, String packageName, Drawable icon, boolean isBlocked) {
            this.appName = appName;
            this.packageName = packageName;
            this.icon = icon;
            this.isBlocked = isBlocked;
        }
    }

    class AppAdapter extends RecyclerView.Adapter<AppAdapter.AppViewHolder> {
        private List<AppItem> appList;

        AppAdapter(List<AppItem> appList) {
            this.appList = appList;
        }

        @NonNull
        @Override
        public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app_toggle, parent, false);
            return new AppViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
            AppItem app = appList.get(position);
            holder.tvAppName.setText(app.appName);
            holder.imgIcon.setImageDrawable(app.icon);

            // Remove listener before setting state to avoid false triggers
            holder.switchBlock.setOnCheckedChangeListener(null);
            holder.switchBlock.setChecked(app.isBlocked);

            holder.switchBlock.setOnCheckedChangeListener((buttonView, isChecked) -> {
                app.isBlocked = isChecked;
                saveSelection(app.packageName, isChecked);
            });
        }

        @Override
        public int getItemCount() {
            return appList.size();
        }

        class AppViewHolder extends RecyclerView.ViewHolder {
            ImageView imgIcon;
            TextView tvAppName;
            Switch switchBlock;

            AppViewHolder(@NonNull View itemView) {
                super(itemView);
                imgIcon = itemView.findViewById(R.id.img_app_icon);
                tvAppName = itemView.findViewById(R.id.tv_app_name);
                switchBlock = itemView.findViewById(R.id.switch_app_block);
            }
        }
    }
}