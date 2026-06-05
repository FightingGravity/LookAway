package com.example.lookaway;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.List;

public class TargetRepository {
    private static final String PREFS_NAME = "LookAwayTargets";
    private static final String KEY_TARGETS = "saved_targets";
    private final SharedPreferences prefs;
    private final Gson gson;

    public TargetRepository(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
    }

    public List<TargetModel> getAllTargets() {
        String json = prefs.getString(KEY_TARGETS, null);
        if (json == null) return new ArrayList<>();
        return gson.fromJson(json, new TypeToken<List<TargetModel>>(){}.getType());
    }

    public void saveTargets(List<TargetModel> targets) {
        String json = gson.toJson(targets);
        prefs.edit().putString(KEY_TARGETS, json).apply();
    }

    public void addTarget(TargetModel target) {
        List<TargetModel> targets = getAllTargets();
        targets.add(target);
        saveTargets(targets);
    }

    public void removeSelectedTargets(List<TargetModel> targetsToRemove) {
        List<TargetModel> currentTargets = getAllTargets();
        currentTargets.removeAll(targetsToRemove);
        saveTargets(currentTargets);
    }

    public void incrementHitCountForTarget(String targetId) {
        List<TargetModel> targets = getAllTargets();
        boolean updated = false;
        for (TargetModel target : targets) {
            if (target.getId().equals(targetId)) {
                target.incrementHitCount();
                updated = true;
                break;
            }
        }
        if (updated) {
            saveTargets(targets);
        }
    }

    // NEW METHOD: Fetch the exact hit count for the animation display
    public int getHitCountForTarget(String targetId) {
        List<TargetModel> targets = getAllTargets();
        for (TargetModel target : targets) {
            if (target.getId().equals(targetId)) {
                return target.getHitCount30Days();
            }
        }
        return 0; // Default to 0 if the target somehow doesn't exist
    }
}