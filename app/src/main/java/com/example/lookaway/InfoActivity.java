package com.example.lookaway;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import eightbitlab.com.blurview.BlurView;
import eightbitlab.com.blurview.RenderScriptBlur;

public class InfoActivity extends AppCompatActivity {

    private LinearLayout btnBack;

    // Tabs
    private TextView tabAdam, tabSam, tabFaq;
    private View lineAdam, lineSam, lineFaq;

    // Content Containers
    private LinearLayout contentAdam, contentSam, contentFaq;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_info);
        setupBlurViews();

        btnBack = findViewById(R.id.btn_back_info);

        tabAdam = findViewById(R.id.tab_adam);
        tabSam = findViewById(R.id.tab_sam);
        tabFaq = findViewById(R.id.tab_faq);

        lineAdam = findViewById(R.id.line_adam);
        lineSam = findViewById(R.id.line_sam);
        lineFaq = findViewById(R.id.line_faq);

        contentAdam = findViewById(R.id.content_adam);
        contentSam = findViewById(R.id.content_sam);
        contentFaq = findViewById(R.id.content_faq);

        // Back Button
        btnBack.setOnClickListener(v -> finish());

        // Tab Clicks
        tabAdam.setOnClickListener(v -> switchTab(0));
        tabSam.setOnClickListener(v -> switchTab(1));
        tabFaq.setOnClickListener(v -> switchTab(2));
    }

    private void setupBlurViews() {
        BlurView infoBlur = findViewById(R.id.info_blur_container);
        ViewGroup rootView = findViewById(android.R.id.content);
        Drawable windowBackground = getWindow().getDecorView().getBackground();
        infoBlur.setupWith(rootView, new RenderScriptBlur(this))
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(3f);
    }

    private void switchTab(int tabIndex) {
        // Reset all text to Gray (#9CA3AF) and hide all underlines/content
        int colorInactive = Color.parseColor("#9CA3AF");
        int colorActive = Color.parseColor("#F59E0B");
        int colorTransparent = Color.parseColor("#00000000");

        tabAdam.setTextColor(colorInactive);
        tabSam.setTextColor(colorInactive);
        tabFaq.setTextColor(colorInactive);

        lineAdam.setBackgroundColor(colorTransparent);
        lineSam.setBackgroundColor(colorTransparent);
        lineFaq.setBackgroundColor(colorTransparent);

        contentAdam.setVisibility(View.GONE);
        contentSam.setVisibility(View.GONE);
        contentFaq.setVisibility(View.GONE);

        // Activate the selected tab
        if (tabIndex == 0) {
            tabAdam.setTextColor(colorActive);
            lineAdam.setBackgroundColor(colorActive);
            contentAdam.setVisibility(View.VISIBLE);
        } else if (tabIndex == 1) {
            tabSam.setTextColor(colorActive);
            lineSam.setBackgroundColor(colorActive);
            contentSam.setVisibility(View.VISIBLE);
        } else if (tabIndex == 2) {
            tabFaq.setTextColor(colorActive);
            lineFaq.setBackgroundColor(colorActive);
            contentFaq.setVisibility(View.VISIBLE);
        }
    }
}
