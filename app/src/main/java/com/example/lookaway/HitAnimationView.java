package com.example.lookaway;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

public class HitAnimationView extends View {
    private Paint crosshairPaint;
    private Paint textPaint;

    private float drawX = -1;
    private float drawY = -1;
    private float currentScale = 0f;
    private int currentAlpha = 0;
    private int hitCount = 0;

    private AnimatorSet currentAnimatorSet;

    public HitAnimationView(Context context) {
        super(context);
        init();
    }

    private void init() {
        crosshairPaint = new Paint();
        crosshairPaint.setColor(Color.parseColor("#00e5ff"));
        crosshairPaint.setStrokeWidth(4f);
        crosshairPaint.setStyle(Paint.Style.STROKE);
        crosshairPaint.setAntiAlias(true);

        textPaint = new Paint();
        textPaint.setColor(Color.parseColor("#ffffff"));
        textPaint.setTextSize(40f);
        textPaint.setAntiAlias(true);
        textPaint.setShadowLayer(8f, 0f, 0f, Color.BLACK);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void playAnimation(float x, float y, int count) {
        if (currentAnimatorSet != null) {
            currentAnimatorSet.cancel();
        }

        drawX = x;
        drawY = y;
        hitCount = count;
        currentAlpha = 255;

        crosshairPaint.setAlpha(currentAlpha);
        textPaint.setAlpha(currentAlpha);

        ValueAnimator scaleAnim = ValueAnimator.ofFloat(0f, 1.0f);
        scaleAnim.setDuration(1250);
        scaleAnim.setInterpolator(new OvershootInterpolator());
        scaleAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                currentScale = (float) animation.getAnimatedValue();
                invalidate();
            }
        });

        ValueAnimator fadeAnim = ValueAnimator.ofInt(255, 0);
        fadeAnim.setDuration(500);
        fadeAnim.setInterpolator(new DecelerateInterpolator());
        fadeAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                currentAlpha = (int) animation.getAnimatedValue();
                crosshairPaint.setAlpha(currentAlpha);
                textPaint.setAlpha(currentAlpha);
                invalidate();
            }
        });

        currentAnimatorSet = new AnimatorSet();
        currentAnimatorSet.playSequentially(scaleAnim, fadeAnim);
        currentAnimatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                currentAlpha = 0;
                invalidate();
            }
        });

        currentAnimatorSet.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (currentAlpha <= 0 || drawX == -1 || drawY == -1) return;

        // Dynamically calculate the system UI offset (like the status bar)
        int[] screenLocation = new int[2];
        getLocationOnScreen(screenLocation);

        // Subtract the offset from the absolute click coordinates
        float adjustedX = drawX - screenLocation[0];
        float adjustedY = drawY - screenLocation[1];

        canvas.save();

        // Translate to the perfectly aligned coordinates
        canvas.translate(adjustedX, adjustedY);
        canvas.scale(currentScale, currentScale);

        float r = 30f;
        canvas.drawCircle(0, 0, r, crosshairPaint);

        canvas.drawLine(-r - 10, 0, -r + 10, 0, crosshairPaint);
        canvas.drawLine(r - 10, 0, r + 10, 0, crosshairPaint);
        canvas.drawLine(0, -r - 10, 0, -r + 10, crosshairPaint);
        canvas.drawLine(0, r - 10, 0, r + 10, crosshairPaint);

        canvas.drawText(String.valueOf(hitCount), 0, r + 45, textPaint);

        canvas.restore();
    }
}