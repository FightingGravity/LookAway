package com.example.lookaway;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class OnboardingManager {
    private static final String PREFS_NAME = "LookAwayPrefs";
    private static final String KEY_COMPLETED = "onboarding_completed";
    private static final String KEY_STEP = "onboarding_step";

    public static final int STEP_WELCOME = 0;
    public static final int STEP_PERMISSIONS = 1;
    public static final int STEP_SAM_SETTINGS = 2;
    public static final int STEP_SAM_APPS = 3;
    public static final int STEP_SAM_ENABLE = 4;
    public static final int STEP_ADAM_ENABLE = 5;
    public static final int STEP_ADAM_SETTINGS = 6;
    public static final int STEP_MONITORED_APPS = 7;
    public static final int STEP_TARGET_PRACTICE_NEXT = 8;
    public static final int STEP_TARGET_PRACTICE_X = 9;
    public static final int STEP_SCANNER_TUNING = 10;
    public static final int STEP_TRIGGER_MANAGER = 11;
    public static final int STEP_FINISH = 12;

    private OnboardingManager() {}

    public static boolean shouldShow(Context context) {
        return !prefs(context).getBoolean(KEY_COMPLETED, false);
    }

    public static int getStep(Context context) {
        return prefs(context).getInt(KEY_STEP, STEP_WELCOME);
    }

    public static void setStep(Context context, int step) {
        prefs(context).edit().putInt(KEY_STEP, step).apply();
    }

    public static void start(Activity activity) {
        prefs(activity).edit()
                .putBoolean(KEY_COMPLETED, false)
                .putInt(KEY_STEP, STEP_WELCOME)
                .apply();
        Intent intent = new Intent(activity, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(intent);
    }

    public static void complete(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_COMPLETED, true)
                .putInt(KEY_STEP, STEP_FINISH)
                .apply();
    }

    public static void skip(Context context) {
        complete(context);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
