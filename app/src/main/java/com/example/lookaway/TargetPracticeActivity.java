package com.example.lookaway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class TargetPracticeActivity extends AppCompatActivity {
    private ImageView imgNext;
    private ImageView imgX;
    private BroadcastReceiver practiceReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_target_practice);

        TutorialTargetSeeder.seedIfNeeded(this);
        imgNext = findViewById(R.id.img_practice_next);
        imgX = findViewById(R.id.img_practice_x);

        practiceReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (LookAwayMasterEngine.ACTION_TUTORIAL_TARGET_SAVED.equals(intent.getAction())) {
                    handleTargetSaved();
                } else if (LookAwayMasterEngine.ACTION_TUTORIAL_TARGET_TAPPED.equals(intent.getAction())) {
                    handleTargetTapped();
                }
            }
        };
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(LookAwayMasterEngine.ACTION_TUTORIAL_TARGET_SAVED);
        filter.addAction(LookAwayMasterEngine.ACTION_TUTORIAL_TARGET_TAPPED);
        ContextCompat.registerReceiver(this, practiceReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(practiceReceiver);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showOnboardingStep();
    }

    private void showOnboardingStep() {
        if (!OnboardingManager.shouldShow(this)) return;

        int step = OnboardingManager.getStep(this);
        if (step == OnboardingManager.STEP_TARGET_PRACTICE_NEXT) {
            imgNext.setVisibility(View.VISIBLE);
            imgX.setVisibility(View.GONE);
            pulseAdamEye();
            OnboardingOverlay.showTapTarget(
                    this,
                    imgNext,
                    "New Target",
                    "Looks like we've got a target. Tap and hold ADAM's eye for one second, tap the Next button, frame it, and save it. TIP: When framing a target, make it as small as possible without cutting off the edges.");
        } else if (step == OnboardingManager.STEP_TARGET_PRACTICE_X) {
            imgNext.setVisibility(View.GONE);
            imgX.setVisibility(View.VISIBLE);
            pulseAdamEye();
            OnboardingOverlay.showTapTarget(
                    this,
                    imgX,
                    "One More Target",
                    "Teach ADAM this X the same way: hold the eye, tap the X, frame it, and save it.");
        }
    }

    private void handleTargetSaved() {
        int step = OnboardingManager.getStep(this);
        if (step != OnboardingManager.STEP_TARGET_PRACTICE_NEXT &&
                step != OnboardingManager.STEP_TARGET_PRACTICE_X) {
            return;
        }

        pulseAdamEye();
        OnboardingOverlay.showTapTarget(
                this,
                step == OnboardingManager.STEP_TARGET_PRACTICE_NEXT ? imgNext : imgX,
                "Target Saved",
                "Open ADAM's eye and let it tap the target you just taught it.");
    }

    private void handleTargetTapped() {
        int step = OnboardingManager.getStep(this);
        if (step == OnboardingManager.STEP_TARGET_PRACTICE_NEXT) {
            OnboardingManager.setStep(this, OnboardingManager.STEP_TARGET_PRACTICE_X);
            getWindow().getDecorView().postDelayed(this::showOnboardingStep, 350);
        } else if (step == OnboardingManager.STEP_TARGET_PRACTICE_X) {
            closeAdamEye();
            OnboardingManager.setStep(this, OnboardingManager.STEP_SCANNER_TUNING);
            OnboardingOverlay.remove(this);
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
        }
    }

    private void pulseAdamEye() {
        LookAwayMasterEngine engine = LookAwayMasterEngine.getInstance();
        if (engine != null) {
            engine.pulseTutorialEyeHighlight();
        }
    }

    private void closeAdamEye() {
        LookAwayMasterEngine engine = LookAwayMasterEngine.getInstance();
        if (engine != null) {
            engine.closeEyeForTutorial();
        }
    }
}
