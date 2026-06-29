package com.example.lookaway;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class OnboardingOverlay {
    private static final String OVERLAY_TAG = "lookaway_onboarding_overlay";
    private static final String TUTORIAL_GREEN = "#10B981";
    private static final String TUTORIAL_GREEN_STROKE = "#6610B981";

    private OnboardingOverlay() {}

    public static void show(
            Activity activity,
            View anchor,
            String title,
            String message,
            String primaryText,
            Runnable primaryAction) {
        remove(activity);

        FrameLayout root = activity.findViewById(android.R.id.content);
        FrameLayout overlay = new FrameLayout(activity);
        overlay.setTag(OVERLAY_TAG);
        overlay.setClickable(anchor == null || primaryText != null || primaryAction != null);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        Button skip = new Button(activity);
        skip.setText("Skip Tutorial");
        skip.setTextColor(Color.WHITE);
        skip.setTextSize(12f);
        skip.setAllCaps(false);
        skip.setBackgroundColor(Color.TRANSPARENT);
        skip.setOnClickListener(v -> {
            OnboardingManager.skip(activity);
            remove(activity);
        });
        FrameLayout.LayoutParams skipParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        skipParams.setMargins(0, dp(activity, 24), dp(activity, 16), 0);
        overlay.addView(skip, skipParams);

        if (anchor != null) {
            anchor.post(() -> {
                Rect spotlight = getAnchorRect(activity, overlay, anchor);
                addSpotlightDim(activity, overlay, spotlight);
                addHighlight(activity, overlay, spotlight);
                addCard(activity, overlay, spotlight, title, message, primaryText, primaryAction);
                skip.bringToFront();
            });
        } else {
            overlay.setBackgroundColor(Color.parseColor("#CC000000"));
            addCard(activity, overlay, null, title, message, primaryText, primaryAction);
            skip.bringToFront();
        }
    }

    public static void showTapTarget(
            Activity activity,
            View anchor,
            String title,
            String message) {
        show(activity, anchor, title, message, null, null);
    }

    private static void addCard(
            Activity activity,
            FrameLayout overlay,
            Rect spotlight,
            String title,
            String message,
            String primaryText,
            Runnable primaryAction) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 16));
        card.setClickable(true);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#EE041A11"));
        cardBg.setStroke(dp(activity, 1), Color.parseColor(TUTORIAL_GREEN_STROKE));
        cardBg.setCornerRadius(dp(activity, 8));
        card.setBackground(cardBg);

        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(Color.parseColor(TUTORIAL_GREEN));
        titleView.setTextSize(20f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setLetterSpacing(0f);
        card.addView(titleView);

        TextView messageView = new TextView(activity);
        messageView.setText(message);
        messageView.setTextColor(Color.WHITE);
        messageView.setTextSize(14f);
        messageView.setLineSpacing(dp(activity, 2), 1.0f);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        messageParams.setMargins(0, dp(activity, 10), 0,
                primaryText == null || primaryAction == null ? 0 : dp(activity, 16));
        card.addView(messageView, messageParams);

        if (primaryText != null && primaryAction != null) {
            Button primary = new Button(activity);
            primary.setText(primaryText);
            primary.setTextColor(Color.BLACK);
            primary.setTextSize(14f);
            primary.setTypeface(Typeface.DEFAULT_BOLD);
            primary.setAllCaps(false);
            GradientDrawable buttonBg = new GradientDrawable();
            buttonBg.setColor(Color.parseColor(TUTORIAL_GREEN));
            buttonBg.setCornerRadius(dp(activity, 6));
            primary.setBackground(buttonBg);
            primary.setOnClickListener(v -> {
                remove(activity);
                primaryAction.run();
            });
            card.addView(primary, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(activity, 48)));
        }

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                shouldPlaceCardAtTop(overlay, spotlight) ? Gravity.TOP : Gravity.BOTTOM);
        if (shouldPlaceCardAtTop(overlay, spotlight)) {
            cardParams.setMargins(dp(activity, 20), dp(activity, 88), dp(activity, 20), 0);
        } else {
            cardParams.setMargins(dp(activity, 20), 0, dp(activity, 20), dp(activity, 28));
        }
        overlay.addView(card, cardParams);
    }

    public static void remove(Activity activity) {
        FrameLayout root = activity.findViewById(android.R.id.content);
        View existing = root.findViewWithTag(OVERLAY_TAG);
        if (existing != null) {
            root.removeView(existing);
        }
    }

    private static Rect getAnchorRect(Activity activity, FrameLayout overlay, View anchor) {
        int[] rootLocation = new int[2];
        int[] anchorLocation = new int[2];
        overlay.getLocationOnScreen(rootLocation);
        anchor.getLocationOnScreen(anchorLocation);

        int padding = dp(activity, 8);
        return new Rect(
                Math.max(0, anchorLocation[0] - rootLocation[0] - padding),
                Math.max(0, anchorLocation[1] - rootLocation[1] - padding),
                Math.min(overlay.getWidth(), anchorLocation[0] - rootLocation[0] + anchor.getWidth() + padding),
                Math.min(overlay.getHeight(), anchorLocation[1] - rootLocation[1] + anchor.getHeight() + padding));
    }

    private static void addSpotlightDim(Activity activity, FrameLayout overlay, Rect rect) {
        int width = overlay.getWidth();
        int height = overlay.getHeight();
        int dimColor = Color.parseColor("#CC000000");

        addDimPanel(activity, overlay, 0, 0, width, rect.top, dimColor);
        addDimPanel(activity, overlay, 0, rect.bottom, width, height - rect.bottom, dimColor);
        addDimPanel(activity, overlay, 0, rect.top, rect.left, rect.height(), dimColor);
        addDimPanel(activity, overlay, rect.right, rect.top, width - rect.right, rect.height(), dimColor);
    }

    private static void addDimPanel(Activity activity, FrameLayout overlay, int left, int top, int width, int height, int color) {
        if (width <= 0 || height <= 0) return;
        View dim = new View(activity);
        dim.setBackgroundColor(color);
        dim.setClickable(true);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = left;
        params.topMargin = top;
        overlay.addView(dim, params);
    }

    private static void addHighlight(Activity activity, FrameLayout overlay, Rect rect) {
        View highlight = new View(activity);
        GradientDrawable highlightBg = new GradientDrawable();
        highlightBg.setColor(Color.TRANSPARENT);
        highlightBg.setStroke(dp(activity, 3), Color.parseColor(TUTORIAL_GREEN));
        highlightBg.setCornerRadius(dp(activity, 10));
        highlight.setBackground(highlightBg);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.max(dp(activity, 44), rect.width()),
                Math.max(dp(activity, 44), rect.height()));
        params.leftMargin = rect.left;
        params.topMargin = rect.top;
        overlay.addView(highlight, params);
    }

    private static boolean shouldPlaceCardAtTop(FrameLayout overlay, Rect spotlight) {
        return spotlight != null && spotlight.centerY() > overlay.getHeight() / 2;
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
