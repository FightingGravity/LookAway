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

        rvAppList = findViewById(R.id.rv_app_list);
        progressBar = findViewById(R.id.progress_loading_apps);

        rvAppList.setLayoutManager(new LinearLayoutManager(this));

        setupBlurViews();
        loadInstalledAppsAsync();
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
            List<AppItem> appItems = new ArrayList<>();
            Set<String> savedApps = prefs.getStringSet("monitored_apps_list", new HashSet<>());

            for (ResolveInfo resolveInfo : resolveInfoList) {
                String packageName = resolveInfo.activityInfo.packageName;

                if (packageName.equals(getPackageName())) continue;

                String appName = resolveInfo.loadLabel(pm).toString();
                Drawable icon = resolveInfo.loadIcon(pm);
                boolean isChecked = savedApps.contains(packageName);

                appItems.add(new AppItem(appName, packageName, icon, isChecked));
            }

            Collections.sort(appItems, (a, b) -> a.name.compareToIgnoreCase(b.name));

            new Handler(Looper.getMainLooper()).post(() -> {
                progressBar.setVisibility(View.GONE);
                rvAppList.setVisibility(View.VISIBLE);
                adapter = new AppListAdapter(appItems);
                rvAppList.setAdapter(adapter);
            });
        }).start();
    }

    // --- DATA MODEL ---
    private static class AppItem {
        String name;
        String packageName;
        Drawable icon;
        boolean isChecked;

        AppItem(String name, String packageName, Drawable icon, boolean isChecked) {
            this.name = name;
            this.packageName = packageName;
            this.icon = icon;
            this.isChecked = isChecked;
        }
    }

    // --- INNER ADAPTER CLASS ---
    private class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.AppViewHolder> {

        private List<AppItem> apps;

        AppListAdapter(List<AppItem> apps) {
            this.apps = apps;
        }

        @NonNull
        @Override
        public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app_row, parent, false);
            return new AppViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
            AppItem app = apps.get(position);

            holder.tvAppName.setText(app.name);
            holder.ivAppIcon.setImageDrawable(app.icon);

            holder.cbSelected.setOnCheckedChangeListener(null);
            holder.cbSelected.setChecked(app.isChecked);

            holder.cbSelected.setOnCheckedChangeListener((buttonView, isChecked) -> {
                app.isChecked = isChecked;
                saveSelectionState();
            });

            holder.itemView.setOnClickListener(v -> holder.cbSelected.toggle());
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }

        private void saveSelectionState() {
            Set<String> selectedPackages = new HashSet<>();
            for (AppItem item : apps) {
                if (item.isChecked) {
                    selectedPackages.add(item.packageName);
                }
            }
            prefs.edit().putStringSet("monitored_apps_list", new HashSet<>(selectedPackages)).apply();
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
    }
}