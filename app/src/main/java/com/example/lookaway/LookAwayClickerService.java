package com.example.lookaway;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class LookAwayClickerService extends AccessibilityService {

    private static LookAwayClickerService instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Allows other parts of our app (like the Floating Widget or automation loops)
     * to safely access the clicker engine.
     */
    public static LookAwayClickerService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        // Lock in the global static instance so the brain can find the engine
        instance = this;

        android.accessibilityservice.AccessibilityServiceInfo info = new android.accessibilityservice.AccessibilityServiceInfo();

        // Tell the system what events we care about (clicks, scrolls, etc.)
        info.eventTypes = android.view.accessibility.AccessibilityEvent.TYPES_ALL_MASK;

        // Set the feedback type to generic since we are an automation tool
        info.feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC;

        // Use FLAG_INCLUDE_NOT_IMPORTANT_VIEWS so the engine can interact with non-standard view layers
        info.flags = android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;

        info.notificationTimeout = 100;

        this.setServiceInfo(info);
    }

    /**
     * Simulates a single finger tap at a specific (x, y) coordinate on the screen.
     */
    public void clickAtCoordinates(int x, int y) {
        // Prevent out-of-bounds negative coordinates from crashing the gesture builder
        if (x < 0 || y < 0) {
            Log.w("LookAway", "Invalid click coordinates: (" + x + ", " + y + "). Ignoring.");
            return;
        }

        // CRITICAL: Accessibility gestures MUST be dispatched on the Main Thread
        mainHandler.post(() -> {
            try {
                Path clickPath = new Path();
                clickPath.moveTo(x, y);

                // Build a stroke description:
                // Arguments: (path, start time delay in ms, duration of touch in ms)
                // 50ms duration mimics a crisp, natural human tap.
                GestureDescription.StrokeDescription clickStroke =
                        new GestureDescription.StrokeDescription(clickPath, 0, 50);

                GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
                gestureBuilder.addStroke(clickStroke);

                // Dispatches the gesture to the active screen layer
                boolean dispatched = dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        super.onCompleted(gestureDescription);
                        Log.d("LookAway", "Successfully clicked at (" + x + ", " + y + ")");
                    }
                }, null);

                if (!dispatched) {
                    Log.e("LookAway", "OS refused to dispatch gesture.");
                }
            } catch (Exception e) {
                Log.e("LookAway", "Error triggering system click: " + e.getMessage());
            }
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Required override. We don't need to listen to system events actively.
    }

    @Override
    public void onInterrupt() {
        // Required override. Handles what happens if the system cuts off the service.
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null; // Clean up the reference if the service is hard closed
    }
}