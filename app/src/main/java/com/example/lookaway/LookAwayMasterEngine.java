package com.example.lookaway;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import android.os.Vibrator;
import android.os.VibrationEffect;

public class LookAwayMasterEngine extends Service {
    public static final String ACTION_TUTORIAL_TARGET_SAVED = "com.example.lookaway.TUTORIAL_TARGET_SAVED";
    public static final String ACTION_TUTORIAL_TARGET_TAPPED = "com.example.lookaway.TUTORIAL_TARGET_TAPPED";
    public static final String EXTRA_TARGET_NAME = "target_name";
    public static final String EXTRA_TARGET_X = "target_x";
    public static final String EXTRA_TARGET_Y = "target_y";

    private volatile boolean hasTappedDuringSession = false;
    private static LookAwayMasterEngine instance;
    public static boolean isRunning = false;

    private WindowManager mWindowManager;
    private View mFloatingView;
    private ImageView playStopIcon;
    private volatile boolean isScanning = false;

    private WindowManager.LayoutParams params;
    private int initialX;
    private int initialY;
    private float initialTouchX;
    private float initialTouchY;
    private boolean isDragEnabled = false;
    private final Handler longPressHandler = new Handler(Looper.getMainLooper());
    private Runnable longPressRunnable;
    private android.os.HandlerThread imageReaderThread;
    private Handler imageReaderHandler;
    private View acquisitionFullScreenCatcher;
    private View acquisitionControlPanel;
    private Mat frozenMat;
    private int targetTapX, targetTapY;
    private Runnable acquisitionRunnable;
    private boolean isWaitingForTargetTap = false;
    private boolean isDeviceAsleep = false;

    private volatile boolean needFrameForBrain = false;
    private volatile boolean needFrameForCrop = false;
    private volatile Bitmap sharedSnapshot = null;
    private Bitmap singleReusableBitmap = null;
    private long lastClickTime = 0;
    private static final long CLICK_COOLDOWN_MS = 1500;
    private static final int SAME_TAP_BUCKET_PX = 32;
    private static final int MAX_SAME_TAP_STREAK = 3;
    private static final int REPEATED_MATCH_MASK_RADIUS_PX = 48;
    private static final int MAX_SUPPRESSED_MATCHES_PER_TEMPLATE = 4;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Thread brainThread;
    private boolean mediaMutedForScan = false;
    private int mediaVolumeBeforeScan = -1;

    private long sessionStartTime = 0;
    private final Object displayLock = new Object();
    private final Object bitmapLock = new Object();
    private final Object repeatedTapLock = new Object();
    private String repeatedTapTargetKey = null;
    private int repeatedTapBucketX = -1;
    private int repeatedTapBucketY = -1;
    private int repeatedTapStreak = 0;
    private boolean repeatedTapSuppressionLogged = false;

    private DisplayManager mDisplayManager;
    private DisplayManager.DisplayListener mDisplayListener;
    private Runnable pendingDisplayRebuildRunnable;

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoStopRunnable = () -> stopAutomationBrain(true);

    private double currentMatchThreshold = 0.85;

    private int screenWidth, screenHeight, screenDensity;
    private List<ImageLibrary.Template> targetTemplates;

    private static final String CHANNEL_ID = "LookAway_Scanner_Channel";
    private static final int NOTIFICATION_ID = 9911;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private BroadcastReceiver screenStateReceiver;
    private BroadcastReceiver resizeReceiver;

    private volatile boolean isAnalyzing = false;

    public static LookAwayMasterEngine getInstance() {
        return instance;
    }

    public void pulseTutorialEyeHighlight() {
        uiHandler.post(() -> {
            if (mFloatingView == null) return;
            mFloatingView.animate().cancel();
            mFloatingView.animate()
                    .scaleX(1.18f)
                    .scaleY(1.18f)
                    .setDuration(260)
                    .withEndAction(() -> {
                        if (mFloatingView != null) {
                            mFloatingView.animate()
                                    .scaleX(1.0f)
                                    .scaleY(1.0f)
                                    .setDuration(260)
                                    .start();
                        }
                    })
                    .start();
        });
    }

    public void closeEyeForTutorial() {
        stopAutomationBrain(false);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        imageReaderThread = new android.os.HandlerThread("LookAwayDrainer");
        imageReaderThread.start();
        imageReaderHandler = new Handler(imageReaderThread.getLooper());
        createNotificationChannel();

        resizeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.example.lookaway.WIDGET_RESIZE".equals(intent.getAction())) {
                    int newScale = intent.getIntExtra("new_scale", 100);
                    applyWidgetScale(newScale);
                }
            }
        };

        ContextCompat.registerReceiver(
                this,
                resizeReceiver,
                new IntentFilter("com.example.lookaway.WIDGET_RESIZE"),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );

        screenStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    Log.i("LookAway", "Screen OFF detected. Hiding overlay and pausing scan.");
                    isDeviceAsleep = true;

                    stopAutomationBrain();
                    removeFullScreenTapCatcher();
                    closeCropPanel();

                    uiHandler.post(() -> removeFloatingWidget());

                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    Log.i("LookAway", "Screen ON detected. Restoring overlay.");
                    isDeviceAsleep = false;

                    try {
                        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                        DisplayMetrics newMetrics = new DisplayMetrics();
                        wm.getDefaultDisplay().getRealMetrics(newMetrics);
                        screenWidth = newMetrics.widthPixels;
                        screenHeight = newMetrics.heightPixels;

                        if (mediaProjection != null) {
                            uiHandler.post(() -> createFloatingWidget());
                        }

                    } catch (Exception e) {
                        Log.e("LookAway", "Error getting metrics: " + e.getMessage());
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenStateReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if ("ACTION_DISABLE_OVERLAY".equals(action)) {
            Log.i("LookAway", "ADAM service shutting down manually.");
            restoreMediaVolumeAfterScan();
            resetShieldStorefrontRecovery("adam_service_disabled");
            stopForeground(true);

            new Thread(() -> {
                try { Thread.sleep(300); } catch (Exception ignored) {}
                synchronized (displayLock) {
                    if (virtualDisplay != null) {
                        virtualDisplay.release();
                        virtualDisplay = null;
                    }
                    if (imageReader != null) {
                        imageReader.close();
                        imageReader = null;
                    }
                    if (mediaProjection != null) {
                        mediaProjection.stop();
                        mediaProjection = null;
                    }
                }
            }).start();

            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildDynamicNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, buildDynamicNotification());
            }
        } catch (Exception e) {
            Log.w("LookAway", "OS blocked Foreground Projection State.");
        }

        if (intent != null && intent.hasExtra("projection_data")) {
            setupProjectionEngine(intent.getParcelableExtra("projection_data"));
            createFloatingWidget();
            updateDynamicNotification();
        }

        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID, "Ad Detecting & Advancing Machine", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(serviceChannel);
        }
    }

    public void updateDynamicNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildDynamicNotification());
    }

    private Notification buildDynamicNotification() {
        SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
        Set<String> pendingQueue = prefs.getStringSet("pending_interrogation_classes", new HashSet<>());

        String title = (mediaProjection == null) ? "ADAM is Paused" : "Ad Detecting & Advancing Machine";
        String contentText = (mediaProjection == null) ? "Tap here to enable ADAM." : "ADAM is monitoring.";

        if (!pendingQueue.isEmpty() && mediaProjection != null) {
            contentText = "⚠️ " + pendingQueue.size() + " Unsorted Trigger(s). Tap to review.";
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setGroup("ENGINE_EXPLICIT_ISOLATION")
                .setOngoing(true);

        if (mediaProjection == null) {
            Intent summonIntent = new Intent(this, LookAwayPermissionProxyActivity.class);
            summonIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent piSummon = PendingIntent.getActivity(this, 3, summonIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            builder.addAction(R.drawable.ic_notification, "Enable ADAM", piSummon);
            builder.setContentIntent(piSummon);
        } else {
            if (!pendingQueue.isEmpty()) {
                Intent reviewIntent = new Intent(this, InterrogationActivity.class);
                reviewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent piReview = PendingIntent.getActivity(this, 4, reviewIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

                builder.addAction(R.drawable.ic_notification, "Review Triggers", piReview);
                builder.setContentIntent(piReview);
            } else {
                Intent mainIntent = new Intent(this, MainActivity.class);
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent piMain = PendingIntent.getActivity(this, 5, mainIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                builder.setContentIntent(piMain);
            }
        }

        return builder.build();
    }

    public void triggerScannerFromShield() {
        if (!isScanning && mediaProjection != null && !isDeviceAsleep) {
            Log.d("LookAway", "SCAN_START requested_by_shield");
            isScanning = true;
            startAutomationBrain(true);
        } else {
            Log.d("LookAway", "SCAN_START ignored requested_by_shield scanning=" + isScanning +
                    " hasProjection=" + (mediaProjection != null) +
                    " asleep=" + isDeviceAsleep);
        }
    }

    public void stopScannerFromShield() {
        if (isScanning) {
            Log.d("LookAway", "SCAN_STOP requested_by_shield");
            stopAutomationBrain(true);
        } else {
            Log.d("LookAway", "SCAN_STOP ignored requested_by_shield already_closed");
        }
    }

    private void setupProjectionEngine(Intent data) {
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            screenDensity = metrics.densityDpi;

            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager != null && data != null) {
                // MediaProjection frames stay local and are used only for on-device template matching.
                mediaProjection = manager.getMediaProjection(Activity.RESULT_OK, data);
                mediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() {
                        Log.w("LookAway", "Screen capture permission was revoked by Android.");
                        isScanning = false;
                        timerHandler.removeCallbacks(autoStopRunnable);

                        mediaProjection = null;
                        virtualDisplay = null;
                        imageReader = null;

                        uiHandler.post(() -> removeFloatingWidget());
                        updateDynamicNotification();
                    }
                }, new Handler(Looper.getMainLooper()));

                uiHandler.post(() -> createFloatingWidget());

                Toast.makeText(this, "ADAM Reconnected!", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    buildVirtualDisplay();
                    targetTemplates = ImageLibrary.loadTargetTemplates(this);
                    needFrameForBrain = true;
                    int waits = 0;
                    while (needFrameForBrain && waits < 100) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException ignored) {
                        }
                        waits++;
                    }
                }).start();
                updateDynamicNotification();
            }

            mDisplayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            if (mDisplayListener != null) {
                try { mDisplayManager.unregisterDisplayListener(mDisplayListener); } catch (Exception ignored) {}
            }

            mDisplayListener = new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int displayId) {}
                @Override public void onDisplayRemoved(int displayId) {}
                @Override public void onDisplayChanged(int displayId) {
                    if (displayId != Display.DEFAULT_DISPLAY) return;
                    if (isDeviceAsleep) return;

                    try {
                        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                        DisplayMetrics newMetrics = new DisplayMetrics();
                        wm.getDefaultDisplay().getRealMetrics(newMetrics);

                        if (newMetrics.widthPixels != screenWidth || newMetrics.heightPixels != screenHeight) {
                            screenWidth = newMetrics.widthPixels;
                            screenHeight = newMetrics.heightPixels;

                            synchronized (bitmapLock) {
                                sharedSnapshot = null;
                            }
                            needFrameForBrain = false;
                            needFrameForCrop = false;

                            if (isScanning || isWaitingForTargetTap) {
                                scheduleVirtualDisplayRebuild();
                            }

                            if (params != null && mFloatingView != null && mWindowManager != null) {
                                if (params.x > screenWidth) params.x = Math.max(0, screenWidth - 150);
                                if (params.y > screenHeight) params.y = Math.max(0, screenHeight - 150);
                                try { mWindowManager.updateViewLayout(mFloatingView, params); } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception e) {
                        Log.e("LookAway", "Rotation rebuild failed: " + e.getMessage());
                    }
                }
            };
            mDisplayManager.registerDisplayListener(mDisplayListener, new Handler(Looper.getMainLooper()));

        } catch (Exception e) { Log.e("LookAway", "Projection Init Failed: " + e.getMessage()); }
    }

    private final ImageReader.OnImageAvailableListener imageDrainer = reader -> {
        try {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
            } catch (IllegalStateException e) {
                return;
            }

            if (image != null) {
                if (needFrameForBrain || needFrameForCrop) {
                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int imageWidth = image.getWidth();
                    int imageHeight = image.getHeight();
                    if (pixelStride <= 0 || imageWidth <= 0 || imageHeight <= 0) {
                        image.close();
                        return;
                    }
                    int rowPadding = rowStride - (pixelStride * imageWidth);
                    int paddedWidth = imageWidth + (rowPadding / pixelStride);

                    synchronized (bitmapLock) {

                        Bitmap freshBitmap = Bitmap.createBitmap(
                                paddedWidth,
                                imageHeight,
                                Bitmap.Config.ARGB_8888);

                        buffer.rewind();
                        freshBitmap.copyPixelsFromBuffer(buffer);

                        sharedSnapshot = freshBitmap;
                    }
                    needFrameForBrain = false;
                    needFrameForCrop = false;
                }
                image.close();
            }
        } catch (Exception ignored) {}
    };

    private void scheduleVirtualDisplayRebuild() {
        if (pendingDisplayRebuildRunnable != null) {
            uiHandler.removeCallbacks(pendingDisplayRebuildRunnable);
        }

        pendingDisplayRebuildRunnable = () -> {
            pendingDisplayRebuildRunnable = null;
            if (!isDeviceAsleep && mediaProjection != null) {
                buildVirtualDisplay();
            }
        };
        uiHandler.postDelayed(pendingDisplayRebuildRunnable, 500);
    }

    private void buildVirtualDisplay() {
        synchronized (displayLock) {
            if (screenWidth <= 0 || screenHeight <= 0 || mediaProjection == null || imageReaderHandler == null) {
                return;
            }

            try {
                if (imageReader != null && (imageReader.getWidth() != screenWidth || imageReader.getHeight() != screenHeight)) {
                    imageReader.setOnImageAvailableListener(null, null);
                    imageReader.close();
                    imageReader = null;
                }

                if (imageReader == null) {
                    imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3);
                    imageReader.setOnImageAvailableListener(imageDrainer, imageReaderHandler);
                }

                if (virtualDisplay == null) {
                    virtualDisplay = mediaProjection.createVirtualDisplay("LookAway_Eyes", screenWidth, screenHeight, screenDensity,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                            imageReader.getSurface(), null, null);
                } else {
                    virtualDisplay.resize(screenWidth, screenHeight, screenDensity);
                    virtualDisplay.setSurface(imageReader.getSurface());
                }
            } catch (Exception e) {
                Log.w("LookAway", "Virtual display rebuild skipped: " + e.getMessage());
            }
        }
    }

    private void createFloatingWidget() {
        if (isRunning) return;
        isRunning = true;

        mFloatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null);
        playStopIcon = mFloatingView.findViewById(R.id.play_stop_btn);

        ImageView widgetBackground = mFloatingView.findViewById(R.id.widget_background);
        if (widgetBackground != null) {
            widgetBackground.setColorFilter(android.graphics.Color.parseColor("#06B6D4"), android.graphics.PorterDuff.Mode.SRC_IN);
        }

        if (playStopIcon != null) {
            playStopIcon.setColorFilter(android.graphics.Color.parseColor("#06B6D4"), android.graphics.PorterDuff.Mode.SRC_IN);
        }

        int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.CENTER_VERTICAL | Gravity.START;

        SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
        params.x = prefs.getInt("widget_x", 0);
        params.y = prefs.getInt("widget_y", 0);

        applyWidgetScale(prefs.getInt("widget_scale", 50));

        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            mWindowManager.addView(mFloatingView, params);
        } catch (Exception e) {
            Log.w("LookAway", "Floating widget attach skipped: " + e.getMessage());
            isRunning = false;
            mFloatingView = null;
            playStopIcon = null;
            return;
        }

        setupWidgetTouchListeners();
    }

    private void setupWidgetTouchListeners() {
        acquisitionRunnable = () -> {
            isWaitingForTargetTap = true;
            stopAutomationBrain();

            if (mediaProjection != null) {
                buildVirtualDisplay();
            }

            uiHandler.post(() -> {
                if (playStopIcon != null) {
                    android.graphics.drawable.Drawable current = playStopIcon.getDrawable();
                    if (current instanceof android.graphics.drawable.AnimationDrawable) {
                        ((android.graphics.drawable.AnimationDrawable) current).stop();
                    }
                    playStopIcon.setImageResource(R.drawable.ic_target_mode);
                    playStopIcon.setColorFilter(android.graphics.Color.parseColor("#10B981"), android.graphics.PorterDuff.Mode.SRC_IN);
                }
            });

            launchFullScreenTapCatcher();
        };

        longPressRunnable = () -> {
            isWaitingForTargetTap = false;
            removeFullScreenTapCatcher();
            isDragEnabled = true;

            uiHandler.post(() -> {
                if (playStopIcon != null) {
                    playStopIcon.setImageResource(R.drawable.ic_eye_closed);
                    playStopIcon.setColorFilter(android.graphics.Color.parseColor("#06B6D4"), android.graphics.PorterDuff.Mode.SRC_IN);
                }
            });
        };

        playStopIcon.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isDragEnabled = false;
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();

                    if (mediaProjection != null) {
                        longPressHandler.postDelayed(acquisitionRunnable, 1000);
                        longPressHandler.postDelayed(longPressRunnable, 2500);
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    int deltaX = (int) (event.getRawX() - initialTouchX);
                    int deltaY = (int) (event.getRawY() - initialTouchY);
                    if (isDragEnabled) {
                        params.x = initialX + deltaX;
                        params.y = initialY + deltaY;
                        try {
                            mWindowManager.updateViewLayout(mFloatingView, params);
                        } catch (Exception e) {
                            Log.w("LookAway", "Widget drag layout skipped: " + e.getMessage());
                        }
                    } else {
                        if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                            longPressHandler.removeCallbacks(acquisitionRunnable);
                            longPressHandler.removeCallbacks(longPressRunnable);
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    longPressHandler.removeCallbacks(acquisitionRunnable);
                    longPressHandler.removeCallbacks(longPressRunnable);
                    if (isDragEnabled) {
                        isDragEnabled = false;
                        getSharedPreferences("LookAwayPrefs", MODE_PRIVATE).edit().putInt("widget_x", params.x).putInt("widget_y", params.y).apply();
                    } else if (!isWaitingForTargetTap) {
                        int finalDeltaX = (int) (event.getRawX() - initialTouchX);
                        int finalDeltaY = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(finalDeltaX) < 10 && Math.abs(finalDeltaY) < 10) {
                            if (mediaProjection == null) {
                                Intent proxyIntent = new Intent(LookAwayMasterEngine.this, LookAwayPermissionProxyActivity.class);
                                proxyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                                startActivity(proxyIntent);
                                return true;
                            }
                            if (isScanning) {
                                String recoveryState = LookAwayShieldService.getInstance() == null
                                        ? "shield=null"
                                        : LookAwayShieldService.getInstance().getStorefrontRecoveryDebugState();
                                Log.d("LookAway", "SCAN_STOP requested_by_manual_eye_tap " + recoveryState);
                                stopAutomationBrain();
                            } else {
                                SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
                                boolean isAuto = prefs.getBoolean("is_automatic_mode", true);
                                if (isAuto && LookAwayShieldService.getInstance() != null) {
                                    String foregroundPackage = LookAwayShieldService.getInstance().getBaseAppPackage();
                                    Set<String> monitoredApps = prefs.getStringSet("monitored_apps_list", new HashSet<>());
                                    if (foregroundPackage != null && monitoredApps.contains(foregroundPackage)) {
                                        LookAwayShieldService.getInstance().launchInterrogation();
                                    }
                                }
                                isScanning = true;
                                startAutomationBrain(false);
                            }
                        }
                    }
                    return true;
            }
            return false;
        });
    }

    private void removeFloatingWidget() {
        isRunning = false;
        if (mFloatingView != null) removeViewSafely(mFloatingView);
        mFloatingView = null;
        playStopIcon = null;
    }

    private void removeViewSafely(View view) {
        if (view == null || mWindowManager == null) return;

        try {
            mWindowManager.removeView(view);
        } catch (IllegalArgumentException e) {
            Log.d("LookAway", "Overlay already detached: " + e.getMessage());
        } catch (Exception e) {
            Log.w("LookAway", "Overlay remove skipped: " + e.getMessage());
        }
    }

    private void applyWidgetScale(int widgetScale) {
        if (mFloatingView == null) return;

        float scaleFactor = widgetScale / 100f;
        float density = getResources().getDisplayMetrics().density;

        int scaledGlassPx = (int) (62 * density * scaleFactor);
        int scaledBackgroundPx = (int) (60 * density * scaleFactor);
        int scaledEyePx = (int) (60 * density * scaleFactor);

        ImageView pupilLayer = mFloatingView.findViewById(R.id.pupil_layer);
        if (pupilLayer != null) {
            ViewGroup.LayoutParams pupilParams = pupilLayer.getLayoutParams();
            if (pupilParams != null) {
                pupilParams.width = scaledEyePx;
                pupilParams.height = scaledEyePx;
                pupilLayer.setLayoutParams(pupilParams);
            }
            pupilLayer.setPadding(0, 0, 0, 0);
        }

        ImageView glassBase = mFloatingView.findViewById(R.id.widget_glass_base);
        if (glassBase != null) {
            ViewGroup.LayoutParams glassParams = glassBase.getLayoutParams();
            if (glassParams != null) {
                glassParams.width = scaledGlassPx;
                glassParams.height = scaledGlassPx;
                glassBase.setLayoutParams(glassParams);
            }
        }

        ImageView backgroundView = mFloatingView.findViewById(R.id.widget_background);
        if (backgroundView != null) {
            ViewGroup.LayoutParams bgParams = backgroundView.getLayoutParams();
            if (bgParams != null) {
                bgParams.width = scaledBackgroundPx;
                bgParams.height = scaledBackgroundPx;
                backgroundView.setLayoutParams(bgParams);
            }
        }

        if (playStopIcon != null) {
            ViewGroup.LayoutParams iconParams = playStopIcon.getLayoutParams();
            if (iconParams != null) {
                iconParams.width = scaledEyePx;
                iconParams.height = scaledEyePx;
                playStopIcon.setLayoutParams(iconParams);
            }
            playStopIcon.setPadding(0, 0, 0, 0);
        }

        if (params != null) {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            if (mWindowManager != null && mFloatingView != null) {
                try {
                    mWindowManager.updateViewLayout(mFloatingView, params);
                } catch (Exception ignored) {}
            }
        }
    }

    private void launchFullScreenTapCatcher() {
        if (acquisitionFullScreenCatcher != null) return;
        acquisitionFullScreenCatcher = new View(this);

        acquisitionFullScreenCatcher.setBackgroundColor(android.graphics.Color.parseColor("#99000000"));

        WindowManager.LayoutParams catchParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            catchParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(50);
            }
        }

        acquisitionFullScreenCatcher.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                targetTapX = (int) event.getRawX();
                targetTapY = (int) event.getRawY();
                v.setBackgroundColor(android.graphics.Color.parseColor("#00000000"));
                freezeScreenForCroppingAsync();
            }
            return true;
        });
        try {
            mWindowManager.addView(acquisitionFullScreenCatcher, catchParams);
        } catch (Exception e) {
            Log.w("LookAway", "Target catcher skipped: " + e.getMessage());
            acquisitionFullScreenCatcher = null;
            isWaitingForTargetTap = false;
        }
    }

    private void removeFullScreenTapCatcher() {
        if (acquisitionFullScreenCatcher != null) {
            removeViewSafely(acquisitionFullScreenCatcher);
            acquisitionFullScreenCatcher = null;
        }
    }

    private void freezeScreenForCroppingAsync() {
        sharedSnapshot = null;
        needFrameForCrop = true;

        new Thread(() -> {
            int waits = 0;
            while (needFrameForCrop && waits < 15) {
                try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                waits++;
            }

            needFrameForCrop = false;

            if (sharedSnapshot != null) {
                try {
                    Mat tempFullMat = new Mat();
                    synchronized (bitmapLock) {
                        if (sharedSnapshot.isRecycled()) return;
                        Utils.bitmapToMat(sharedSnapshot, tempFullMat);
                    }

                    Rect screenCrop = buildSafeScreenCrop(tempFullMat);
                    if (screenCrop == null) {
                        tempFullMat.release();
                        return;
                    }
                    Mat exactMat = new Mat(tempFullMat, screenCrop);

                    frozenMat = new Mat();
                    Imgproc.cvtColor(exactMat, frozenMat, Imgproc.COLOR_RGBA2GRAY);

                    tempFullMat.release();
                    exactMat.release();
                    sharedSnapshot = null;

                    uiHandler.post(() -> {
                        removeFullScreenTapCatcher();
                        launchCropControlPanel();
                    });
                } catch (Exception e) {
                    Log.e("LookAway", "Failed to convert cropped frame", e);
                    sharedSnapshot = null;
                    uiHandler.post(() -> {
                        removeFullScreenTapCatcher();
                        closeCropPanel();
                    });
                }
            } else {
                Log.e("LookAway", "Frame freeze failed: Buffer remained empty.");
                uiHandler.post(() -> {
                    Toast.makeText(LookAwayMasterEngine.this, "Capture Error: Try again.", Toast.LENGTH_SHORT).show();
                    removeFullScreenTapCatcher();
                    closeCropPanel();
                });
            }
        }).start();
    }

    private void syncCyanBox(View reticleBox, View visualIndicator) {
        if (visualIndicator != null && visualIndicator.getLayoutParams() != null) {
            float density = getResources().getDisplayMetrics().density;
            final int touchOffset = (int) (80 * density);
            visualIndicator.getLayoutParams().width = reticleBox.getWidth() + (touchOffset * 2);
            visualIndicator.getLayoutParams().height = reticleBox.getHeight() + (touchOffset * 2);
            visualIndicator.setX(reticleBox.getX() - touchOffset);
            visualIndicator.setY(reticleBox.getY() - touchOffset);
            visualIndicator.requestLayout();
        }
    }

    private void updateReticleTouchDelegate(View reticleBox, View parentContainer, View visualIndicator) {
        syncCyanBox(reticleBox, visualIndicator);

        float density = getResources().getDisplayMetrics().density;
        final int touchOffset = (int) (80 * density);

        parentContainer.post(() -> {
            android.graphics.Rect delegateArea = new android.graphics.Rect();
            delegateArea.left = (int) reticleBox.getX();
            delegateArea.top = (int) reticleBox.getY();
            delegateArea.right = delegateArea.left + reticleBox.getWidth();
            delegateArea.bottom = delegateArea.top + reticleBox.getHeight();
            delegateArea.left -= touchOffset; delegateArea.top -= touchOffset;
            delegateArea.right += touchOffset; delegateArea.bottom += touchOffset;
            parentContainer.setTouchDelegate(new android.view.TouchDelegate(delegateArea, reticleBox));
        });
    }

    private void launchCropControlPanel() {
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        acquisitionControlPanel = inflater.inflate(R.layout.layout_acquisition_panel, null);

        WindowManager.LayoutParams panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            panelParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        View reticleBox = acquisitionControlPanel.findViewById(R.id.reticle_box);
        SeekBar cropSlider = acquisitionControlPanel.findViewById(R.id.crop_slider);
        EditText nameInput = acquisitionControlPanel.findViewById(R.id.target_name_input);
        Button btnCancel = acquisitionControlPanel.findViewById(R.id.btn_cancel_crop);
        Button btnSave = acquisitionControlPanel.findViewById(R.id.btn_save_crop);

        View touchIndicator = new View(this);
        touchIndicator.setBackgroundColor(android.graphics.Color.parseColor("#3300FFFF"));
        touchIndicator.setLayoutParams(new ViewGroup.LayoutParams(0, 0));
        ((ViewGroup) acquisitionControlPanel).addView(touchIndicator, 0);

        LinearLayout controlPanelContainer = acquisitionControlPanel.findViewById(R.id.control_panel_container);
        RelativeLayout.LayoutParams containerParams = (RelativeLayout.LayoutParams) controlPanelContainer.getLayoutParams();

        containerParams.removeRule(RelativeLayout.ALIGN_PARENT_TOP);
        containerParams.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        containerParams.removeRule(RelativeLayout.ALIGN_PARENT_START);
        containerParams.removeRule(RelativeLayout.ALIGN_PARENT_END);
        containerParams.removeRule(RelativeLayout.CENTER_VERTICAL);

        if (screenWidth > screenHeight) {
            containerParams.width = (int) (screenWidth * 0.35);
            containerParams.addRule(RelativeLayout.CENTER_VERTICAL, RelativeLayout.TRUE);

            if (targetTapX > (screenWidth / 2)) {
                containerParams.addRule(RelativeLayout.ALIGN_PARENT_START, RelativeLayout.TRUE);
            } else {
                containerParams.addRule(RelativeLayout.ALIGN_PARENT_END, RelativeLayout.TRUE);
            }
        } else {
            containerParams.width = ViewGroup.LayoutParams.MATCH_PARENT;

            if (targetTapY > (screenHeight / 2)) {
                containerParams.addRule(RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.TRUE);
            } else {
                containerParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE);
            }
        }
        controlPanelContainer.setLayoutParams(containerParams);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) cropSlider.setMin(16);
        int initialSize = cropSlider.getProgress();
        reticleBox.getLayoutParams().width = initialSize; reticleBox.getLayoutParams().height = initialSize;

        reticleBox.setX(targetTapX - (initialSize / 2f));
        reticleBox.setY(targetTapY - (initialSize / 2f));

        updateReticleTouchDelegate(reticleBox, acquisitionControlPanel, touchIndicator);
        final float[] dTouch = new float[2];

        reticleBox.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dTouch[0] = v.getX() - event.getRawX();
                    dTouch[1] = v.getY() - event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float newX = event.getRawX() + dTouch[0];
                    float newY = event.getRawY() + dTouch[1];
                    v.setX(newX); v.setY(newY);
                    targetTapX = (int) (newX + (v.getWidth() / 2f));
                    targetTapY = (int) (newY + (v.getHeight() / 2f));
                    syncCyanBox(v, touchIndicator);
                    return true;
                case MotionEvent.ACTION_UP:
                    updateReticleTouchDelegate(v, acquisitionControlPanel, touchIndicator);
                    return true;
                default: return false;
            }
        });

        cropSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int safeSize = Math.max(16, progress);
                reticleBox.getLayoutParams().width = safeSize; reticleBox.getLayoutParams().height = safeSize;
                reticleBox.setX(targetTapX - (safeSize / 2f));
                reticleBox.setY(targetTapY - (safeSize / 2f));
                reticleBox.requestLayout();
                syncCyanBox(reticleBox, touchIndicator);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                updateReticleTouchDelegate(reticleBox, acquisitionControlPanel, touchIndicator);
            }
        });

        btnCancel.setOnClickListener(v -> closeCropPanel());
        btnSave.setOnClickListener(v -> {
            String targetName = nameInput.getText().toString().trim();
            if (targetName.isEmpty()) targetName = "Unnamed Target";
            saveTargetAndCleanup(cropSlider.getProgress(), targetName);
        });

        mWindowManager.addView(acquisitionControlPanel, panelParams);
        performAutoCrop();
    }

    private void performAutoCrop() {
        if (frozenMat == null || frozenMat.empty() || acquisitionControlPanel == null) return;

        new Thread(() -> {
            try {
                int searchRadius = 150;
                int safeLeft = Math.max(0, targetTapX - searchRadius);
                int safeTop = Math.max(0, targetTapY - searchRadius);
                int safeRight = Math.min(frozenMat.cols(), targetTapX + searchRadius);
                int safeBottom = Math.min(frozenMat.rows(), targetTapY + searchRadius);

                Rect searchRoi = new Rect(safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop);
                Mat localMat = new Mat(frozenMat, searchRoi);

                Mat gray = new Mat();
                if (localMat.channels() > 1) {
                    Imgproc.cvtColor(localMat, gray, Imgproc.COLOR_RGBA2GRAY);
                } else {
                    localMat.copyTo(gray);
                }

                Mat blurred = new Mat();
                Imgproc.GaussianBlur(gray, blurred, new org.opencv.core.Size(5, 5), 0);

                Mat edges = new Mat();
                Imgproc.Canny(blurred, edges, 50, 150);

                Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new org.opencv.core.Size(5, 5));
                Imgproc.dilate(edges, edges, kernel);

                java.util.List<org.opencv.core.MatOfPoint> contours = new java.util.ArrayList<>();
                Mat hierarchy = new Mat();
                Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

                org.opencv.core.Rect bestRect = null;
                org.opencv.core.Point localTap = new org.opencv.core.Point(targetTapX - safeLeft, targetTapY - safeTop);

                double closestDistance = Double.MAX_VALUE;

                for (org.opencv.core.MatOfPoint contour : contours) {
                    org.opencv.core.Rect rect = Imgproc.boundingRect(contour);
                    if (rect.width < 15 || rect.height < 15 || rect.width > 280 || rect.height > 280) continue;
                    org.opencv.core.Rect clickZone = new org.opencv.core.Rect(rect.x - 20, rect.y - 20, rect.width + 40, rect.height + 40);

                    if (clickZone.contains(localTap)) {
                        if (bestRect == null || rect.area() > bestRect.area()) {
                            bestRect = rect;
                        }
                    }
                }

                if (bestRect == null) {
                    for (org.opencv.core.MatOfPoint contour : contours) {
                        org.opencv.core.Rect rect = Imgproc.boundingRect(contour);
                        if (rect.width < 15 || rect.height < 15 || rect.width > 280 || rect.height > 280) continue;

                        double centerX = rect.x + (rect.width / 2.0);
                        double centerY = rect.y + (rect.height / 2.0);
                        double dist = Math.sqrt(Math.pow(centerX - localTap.x, 2) + Math.pow(centerY - localTap.y, 2));

                        if (dist < 60 && dist < closestDistance) {
                            closestDistance = dist;
                            bestRect = rect;
                        }
                    }
                }

                if (bestRect != null) {
                    int globalX = safeLeft + bestRect.x;
                    int globalY = safeTop + bestRect.y;

                    int newTapX = globalX + (bestRect.width / 2);
                    int newTapY = globalY + (bestRect.height / 2);

                    int recommendedSize = Math.max(bestRect.width, bestRect.height) + 24;
                    int finalSize = Math.min(256, Math.max(32, recommendedSize));

                    uiHandler.post(() -> {
                        targetTapX = newTapX;
                        targetTapY = newTapY;
                        if (acquisitionControlPanel != null) {
                            SeekBar cropSlider = acquisitionControlPanel.findViewById(R.id.crop_slider);
                            if (cropSlider != null) {
                                cropSlider.setProgress(finalSize);
                            }
                        }
                    });
                }

                gray.release();
                blurred.release();
                edges.release();
                kernel.release();
                hierarchy.release();
                localMat.release();
            } catch (Exception e) {
                Log.e("LookAway", "AutoCrop failed: " + e.getMessage());
            }
        }).start();
    }

    private void closeCropPanel() {
        if (acquisitionControlPanel != null) {
            removeViewSafely(acquisitionControlPanel);
            acquisitionControlPanel = null;
        }
        if (frozenMat != null) { frozenMat.release(); frozenMat = null; }
        isWaitingForTargetTap = false;

        uiHandler.post(() -> {
            if (playStopIcon != null) {
                playStopIcon.setImageResource(R.drawable.ic_eye_closed);
                playStopIcon.setColorFilter(android.graphics.Color.parseColor("#06B6D4"), android.graphics.PorterDuff.Mode.SRC_IN);
            }
        });
    }

    private void saveTargetAndCleanup(int size, String name) {
        if (frozenMat != null && !frozenMat.empty()) {
            int halfSize = size / 2;
            int safeLeft = Math.max(0, targetTapX - halfSize);
            int safeTop = Math.max(0, targetTapY - halfSize);
            int safeWidth = Math.min(frozenMat.cols() - safeLeft, size);
            int safeHeight = Math.min(frozenMat.rows() - safeTop, size);

            Rect finalCropRegion = new Rect(safeLeft, safeTop, safeWidth, safeHeight);
            Mat targetTemplate = new Mat(frozenMat, finalCropRegion);

            try {
                File targetsDir = new File(getFilesDir(), "targets");
                if (!targetsDir.exists()) targetsDir.mkdirs();

                String uniqueId = UUID.randomUUID().toString();
                File imageFile = new File(targetsDir, uniqueId + ".png");
                Imgcodecs.imwrite(imageFile.getAbsolutePath(), targetTemplate);

                TargetRepository repo = new TargetRepository(this);
                TargetModel newTarget = new TargetModel(uniqueId, name, imageFile.getAbsolutePath());
                repo.addTarget(newTarget);

                Toast.makeText(this, "Target Saved: " + name, Toast.LENGTH_SHORT).show();
                Intent savedIntent = new Intent(ACTION_TUTORIAL_TARGET_SAVED);
                savedIntent.setPackage(getPackageName());
                savedIntent.putExtra(EXTRA_TARGET_NAME, name);
                sendBroadcast(savedIntent);
            } catch (Exception e) {
                Log.e("LookAway", "Error saving cropped target", e);
                Toast.makeText(this, "Save Error: Check logs.", Toast.LENGTH_SHORT).show();
            }
            targetTemplate.release();
        } else {
            Toast.makeText(this, "Capture Error: Screen buffer was empty. Please try again.", Toast.LENGTH_LONG).show();
        }
        closeCropPanel();
    }

    private void saveActiveTime(long durationMillis) {
        if (durationMillis <= 0) return;
        SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
        String dateString = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        String key = "active_time_" + dateString;
        prefs.edit().putLong(key, prefs.getLong(key, 0) + durationMillis).apply();
    }

    private void startAutomationBrain(boolean openedAutomatically) {
        if (mediaProjection == null) {
            Log.w("LookAway", "Screen capture permission unavailable. Requesting a new capture session.");
            isScanning = false;

            uiHandler.post(() -> {
                if (playStopIcon != null) {
                    playStopIcon.setImageResource(R.drawable.ic_eye_closed);
                    playStopIcon.setColorFilter(android.graphics.Color.parseColor("#06B6D4"), android.graphics.PorterDuff.Mode.SRC_IN);
                }
            });

            Toast.makeText(this, "ADAM disconnected. Tap 'Enable ADAM' in notification.", Toast.LENGTH_SHORT).show();
            return;
        }

        buildVirtualDisplay();
        applyMediaMuteForScan(openedAutomatically);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isScanning) return;
            if (targetTemplates == null || targetTemplates.isEmpty()) {
                targetTemplates = ImageLibrary.loadTargetTemplates(this);
            }
            resetAutoStopTimer();

            uiHandler.post(() -> {
                if (playStopIcon != null) {
                    playStopIcon.setImageResource(R.drawable.anim_eye_open);
                    playStopIcon.setColorFilter(android.graphics.Color.parseColor("#06B6D4"), android.graphics.PorterDuff.Mode.SRC_IN);

                    android.graphics.drawable.AnimationDrawable openAnim = (android.graphics.drawable.AnimationDrawable) playStopIcon.getDrawable();
                    if (openAnim != null) {
                        openAnim.start();
                    }

                    if (mFloatingView != null) {
                        ImageView pupilLayer = mFloatingView.findViewById(R.id.pupil_layer);
                        if (pupilLayer != null) {
                            pupilLayer.setImageResource(R.drawable.pupil_0);
                            pupilLayer.setColorFilter(android.graphics.Color.parseColor("#F59E0B"), android.graphics.PorterDuff.Mode.SRC_IN);
                            pupilLayer.setAlpha(1.0f);
                        }
                    }

                    uiHandler.postDelayed(() -> {
                        if (isScanning && mFloatingView != null) {
                            ImageView pupilLayer = mFloatingView.findViewById(R.id.pupil_layer);
                            if (pupilLayer != null) {
                                pupilLayer.setImageDrawable(null);
                                pupilLayer.setImageResource(R.drawable.anim_eye_scan);

                                pupilLayer.post(() -> {
                                    android.graphics.drawable.AnimationDrawable scanAnim =
                                            (android.graphics.drawable.AnimationDrawable) pupilLayer.getDrawable();
                                    if (scanAnim != null) {
                                        scanAnim.start();
                                    }
                                });
                            }
                        }
                    }, 200);
                }
            });

            sessionStartTime = System.currentTimeMillis();
            hasTappedDuringSession = false;
            resetRepeatedTapSuppression();

            brainThread = new Thread(() -> {
                while (isScanning) {
                    long startTime = System.currentTimeMillis();
                    long safeSessionStart = sessionStartTime;

                    if (safeSessionStart > 0 && (startTime - safeSessionStart >= 60000)) {
                        saveActiveTime(startTime - safeSessionStart);
                        if (sessionStartTime > 0) {
                            sessionStartTime = startTime;
                        }
                    }

                    SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
                    currentMatchThreshold = prefs.getInt("match_accuracy", 35) / 100.0;

                    captureAndAnalyzeFrame();

                    long processTime = System.currentTimeMillis() - startTime;
                    long targetDelayMs = 1000;

                    if (!hasTappedDuringSession) {
                        long elapsedSessionSec = (System.currentTimeMillis() - sessionStartTime) / 1000;

                        if (elapsedSessionSec <= 10) {
                            targetDelayMs = 1000;
                        } else if (elapsedSessionSec <= 25) {
                            targetDelayMs = 3000;
                        } else if (elapsedSessionSec <= 38) {
                            targetDelayMs = 1000;
                        } else if (elapsedSessionSec <= 56) {
                            targetDelayMs = 5000;
                        } else if (elapsedSessionSec <= 76) {
                            targetDelayMs = 1000;
                        } else {
                            targetDelayMs = 5000;
                        }
                    }

                    long sleepTime = targetDelayMs - processTime;

                    if (sleepTime > 0) {
                        long elapsedSleep = 0;
                        while (elapsedSleep < sleepTime && isScanning) {
                            long chunk = Math.min(250, sleepTime - elapsedSleep);
                            try {
                                Thread.sleep(chunk);
                            } catch (InterruptedException e) {
                                break;
                            }
                            elapsedSleep += chunk;
                        }
                    }
                }

                if (targetTemplates != null) {
                    for (ImageLibrary.Template template : targetTemplates) {
                        if (template.grayMat != null && !template.grayMat.empty()) {
                            template.grayMat.release();
                        }
                    }
                    targetTemplates.clear();
                    targetTemplates = null;
                }

            });
            brainThread.start();
        }, 500);
    }

    private void resetAutoStopTimer() {
        timerHandler.removeCallbacks(autoStopRunnable);
        SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
        long dynamicTimeoutDuration = prefs.getBoolean("is_automatic_mode", true) ? 180000L : prefs.getInt("timeout_seconds", 30) * 1000L;
        timerHandler.postDelayed(autoStopRunnable, dynamicTimeoutDuration);
    }

    private void captureAndAnalyzeFrame() {
        if (System.currentTimeMillis() - lastClickTime < CLICK_COOLDOWN_MS) return;

        if (isAnalyzing) return;

        // DON'T erase the last good frame.
        // sharedSnapshot = null;

        needFrameForBrain = true;
        int waits = 0;
        while (needFrameForBrain && waits < 100) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                break;
            }
            waits++;
        }
        needFrameForBrain = false;

        Mat safeMatToProcess = null;

        synchronized (bitmapLock) {
            if (sharedSnapshot != null && !sharedSnapshot.isRecycled()) {
                safeMatToProcess = new Mat();
                Utils.bitmapToMat(sharedSnapshot, safeMatToProcess);

                // DON'T clear it afterwards either.
                // sharedSnapshot = null;
            }
        }

        if (safeMatToProcess != null && !safeMatToProcess.empty()) {
            isAnalyzing = true;

            try {
                processVisualPixels(safeMatToProcess);
            } catch (Exception e) {
                Log.w("LookAway", "Frame skipped during visual processing: " + e.getMessage());
            } finally {
                safeMatToProcess.release();
                isAnalyzing = false;
            }
        }
    }
    private Rect buildSafeScreenCrop(Mat sourceMat) {
        if (sourceMat == null || sourceMat.empty()) return null;

        int requestedWidth = screenWidth;
        int requestedHeight = screenHeight;
        int cropWidth = Math.min(Math.max(requestedWidth, 0), sourceMat.cols());
        int cropHeight = Math.min(Math.max(requestedHeight, 0), sourceMat.rows());

        if (cropWidth <= 0 || cropHeight <= 0) return null;

        if (cropWidth != requestedWidth || cropHeight != requestedHeight) {
            Log.d("LookAway", "FRAME_DIM_MISMATCH frame=" + sourceMat.cols() + "x" + sourceMat.rows() +
                    " screen=" + requestedWidth + "x" + requestedHeight +
                    " crop=" + cropWidth + "x" + cropHeight);
        }

        return new Rect(0, 0, cropWidth, cropHeight);
    }

    private void processVisualPixels(Mat fullMat) {
        Rect screenCrop = buildSafeScreenCrop(fullMat);
        if (screenCrop == null) return;

        Mat exactScreenMat = new Mat(fullMat, screenCrop);

        Mat grayScreen = new Mat();
        Imgproc.cvtColor(exactScreenMat, grayScreen, Imgproc.COLOR_RGBA2GRAY);

        exactScreenMat.release();

        SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);

        int roiMode = prefs.getInt("roi_mode", 1);
        int roiPercentProgress = prefs.getInt("roi_percent", 30);

        double marginPercentage = (roiPercentProgress + 5) / 100.0;

        int width = grayScreen.cols();
        int height = grayScreen.rows();
        int marginHeight = (int) (height * marginPercentage);

        boolean clickFound = false;

        if (roiMode == 1 || roiMode == 0) {
            Rect topCrop = new Rect(0, 0, width, marginHeight);
            Mat topSlice = new Mat(grayScreen, topCrop);
            clickFound = searchTemplatesInSlice(topSlice, 0, 0);
            topSlice.release();
        }

        if (!clickFound && (roiMode == 2 || roiMode == 0)) {
            int botStartY = height - marginHeight;
            Rect botCrop = new Rect(0, botStartY, width, marginHeight);
            Mat botSlice = new Mat(grayScreen, botCrop);
            clickFound = searchTemplatesInSlice(botSlice, 0, botStartY);
            botSlice.release();
        }

        grayScreen.release();
    }

    private boolean searchTemplatesInSlice(Mat slice, int xOffset, int yOffset) {
        if (targetTemplates == null || targetTemplates.isEmpty()) {
            return false;
        }

        for (ImageLibrary.Template template : targetTemplates) {
            if (template.grayMat == null || template.grayMat.empty()) {
                continue;
            }
            if (template.grayMat.cols() <= slice.cols() && template.grayMat.rows() <= slice.rows()) {
                if (scanAndClick(slice, template, xOffset, yOffset)) return true;
            }
        }
        return false;
    }

    private boolean scanAndClick(Mat slice, ImageLibrary.Template template, int xOffset, int yOffset) {
        Mat result = new Mat();
        Imgproc.matchTemplate(slice, template.grayMat, result, Imgproc.TM_CCOEFF_NORMED);

        for (int attempt = 0; attempt < MAX_SUPPRESSED_MATCHES_PER_TEMPLATE; attempt++) {
            Core.MinMaxLocResult mmr = Core.minMaxLoc(result);
            if (mmr.maxVal < currentMatchThreshold) {
                break;
            }

            int clickX = (int)(mmr.maxLoc.x + template.grayMat.cols() / 2.0) + xOffset;
            int clickY = (int)(mmr.maxLoc.y + template.grayMat.rows() / 2.0) + yOffset;

            if (isRepeatedTapCandidateSuppressed(template, clickX, clickY, mmr.maxVal)) {
                maskSuppressedMatch(result, (int) mmr.maxLoc.x, (int) mmr.maxLoc.y);
                continue;
            }

            LookAwayShieldService shieldService = LookAwayShieldService.getInstance();
            String recoveryState = shieldService == null
                    ? "shield=null"
                    : shieldService.getStorefrontRecoveryDebugState();
            Log.d("LookAway", "TARGET_TAP name=" + template.name +
                    " x=" + clickX +
                    " y=" + clickY +
                    " score=" + mmr.maxVal +
                    " threshold=" + currentMatchThreshold +
                    " " + recoveryState);
            if (shieldService != null) {
                shieldService.clickAtCoordinates(clickX, clickY);
                shieldService.extendStorefrontRecovery();
            }

            hasTappedDuringSession = true;
            recordAcceptedTargetTap(template, clickX, clickY);
            Intent tappedIntent = new Intent(ACTION_TUTORIAL_TARGET_TAPPED);
            tappedIntent.setPackage(getPackageName());
            tappedIntent.putExtra(EXTRA_TARGET_NAME, template.name);
            tappedIntent.putExtra(EXTRA_TARGET_X, clickX);
            tappedIntent.putExtra(EXTRA_TARGET_Y, clickY);
            sendBroadcast(tappedIntent);
            lastClickTime = System.currentTimeMillis();
            resetAutoStopTimer();

            TargetRepository repo = new TargetRepository(LookAwayMasterEngine.this);
            repo.incrementHitCountForTarget(template.id);
            int totalHits = repo.getHitCountForTarget(template.id);

            uiHandler.post(() -> {
                HitAnimationView tempHitView = new HitAnimationView(LookAwayMasterEngine.this);

                int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

                WindowManager.LayoutParams animParams = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                        layoutFlag,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                );

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    animParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                }

                try {
                    mWindowManager.addView(tempHitView, animParams);
                    tempHitView.playAnimation(clickX, clickY, totalHits);
                } catch (Exception e) {
                    Log.e("LookAway", "Failed to draw hit animation", e);
                }
                uiHandler.postDelayed(() -> {
                    if (tempHitView != null && tempHitView.getWindowToken() != null) {
                        try {
                            mWindowManager.removeView(tempHitView);
                        } catch (Exception ignored) {}
                    }
                }, 1500);
            });
            result.release();
            return true;
        }
        result.release();
        return false;
    }

    private void maskSuppressedMatch(Mat result, int resultX, int resultY) {
        int left = Math.max(0, resultX - REPEATED_MATCH_MASK_RADIUS_PX);
        int top = Math.max(0, resultY - REPEATED_MATCH_MASK_RADIUS_PX);
        int right = Math.min(result.cols(), resultX + REPEATED_MATCH_MASK_RADIUS_PX + 1);
        int bottom = Math.min(result.rows(), resultY + REPEATED_MATCH_MASK_RADIUS_PX + 1);

        if (right <= left || bottom <= top) return;

        Mat maskedRegion = new Mat(result, new Rect(left, top, right - left, bottom - top));
        maskedRegion.setTo(new Scalar(-1.0));
        maskedRegion.release();
    }

    private boolean isRepeatedTapCandidateSuppressed(ImageLibrary.Template template, int clickX, int clickY, double score) {
        String targetKey = getRepeatedTapTargetKey(template);
        int bucketX = clickX / SAME_TAP_BUCKET_PX;
        int bucketY = clickY / SAME_TAP_BUCKET_PX;

        synchronized (repeatedTapLock) {
            boolean sameTapCandidate = repeatedTapStreak >= MAX_SAME_TAP_STREAK &&
                    targetKey.equals(repeatedTapTargetKey) &&
                    bucketX == repeatedTapBucketX &&
                    bucketY == repeatedTapBucketY;

            if (sameTapCandidate && !repeatedTapSuppressionLogged) {
                Log.d("LookAway", "REPEATED_TAP_SUPPRESS name=" + template.name +
                        " x=" + clickX +
                        " y=" + clickY +
                        " streak=" + repeatedTapStreak +
                        " score=" + score);
                repeatedTapSuppressionLogged = true;
            }

            return sameTapCandidate;
        }
    }

    private void recordAcceptedTargetTap(ImageLibrary.Template template, int clickX, int clickY) {
        String targetKey = getRepeatedTapTargetKey(template);
        int bucketX = clickX / SAME_TAP_BUCKET_PX;
        int bucketY = clickY / SAME_TAP_BUCKET_PX;

        synchronized (repeatedTapLock) {
            boolean sameTapCandidate = targetKey.equals(repeatedTapTargetKey) &&
                    bucketX == repeatedTapBucketX &&
                    bucketY == repeatedTapBucketY;

            if (sameTapCandidate) {
                repeatedTapStreak++;
            } else {
                repeatedTapTargetKey = targetKey;
                repeatedTapBucketX = bucketX;
                repeatedTapBucketY = bucketY;
                repeatedTapStreak = 1;
                repeatedTapSuppressionLogged = false;
            }
        }
    }

    private String getRepeatedTapTargetKey(ImageLibrary.Template template) {
        if (template.id != null && !template.id.isEmpty()) {
            return template.id;
        }
        return template.name == null ? "" : template.name;
    }

    private void resetRepeatedTapSuppression() {
        synchronized (repeatedTapLock) {
            repeatedTapTargetKey = null;
            repeatedTapBucketX = -1;
            repeatedTapBucketY = -1;
            repeatedTapStreak = 0;
            repeatedTapSuppressionLogged = false;
        }
    }

    private void stopAutomationBrain() {
        stopAutomationBrain(false);
    }

    private void stopAutomationBrain(boolean pulseOnAutoClose) {
        boolean wasScanning = isScanning;
        isScanning = false;
        restoreMediaVolumeAfterScan();
        if (!pulseOnAutoClose) {
            resetShieldStorefrontRecovery("manual_scan_stop");
        }
        resetRepeatedTapSuppression();
        timerHandler.removeCallbacks(autoStopRunnable);

        if (wasScanning && pulseOnAutoClose) {
            pulseAutoCloseVibration();
        }

        uiHandler.post(() -> {
            if (isDeviceAsleep) return;

            if (playStopIcon != null) {
                if (mFloatingView != null) {
                    ImageView pupilLayer = mFloatingView.findViewById(R.id.pupil_layer);
                    if (pupilLayer != null) pupilLayer.setAlpha(0f);
                }

                if (wasScanning) {
                    playStopIcon.setColorFilter(android.graphics.Color.parseColor("#06B6D4"), android.graphics.PorterDuff.Mode.SRC_IN);
                    try {
                        playStopIcon.setImageResource(R.drawable.anim_eye_close);
                        android.graphics.drawable.Drawable current = playStopIcon.getDrawable();
                        if (current instanceof android.graphics.drawable.AnimationDrawable) {
                            ((android.graphics.drawable.AnimationDrawable) current).start();
                        }
                    } catch (Exception e) {
                        playStopIcon.setImageResource(R.drawable.ic_eye_closed);
                    }

                    uiHandler.postDelayed(() -> {
                        if (!isScanning && !isWaitingForTargetTap && !isDeviceAsleep && playStopIcon != null) {
                            playStopIcon.setImageResource(R.drawable.ic_eye_closed);
                        }
                    }, 200);
                } else if (!isWaitingForTargetTap) {
                    playStopIcon.setImageResource(R.drawable.ic_eye_closed);
                }
            }
        });

        if (sessionStartTime > 0) {
            saveActiveTime(System.currentTimeMillis() - sessionStartTime);
            sessionStartTime = 0;
        }
    }

    private void pulseAutoCloseVibration() {
        SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
        if (!prefs.getBoolean("auto_close_vibration", true)) return;

        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;

        int intensity = normalizeAutoCloseVibrationIntensity(
                prefs.getInt("auto_close_vibration_intensity", 180));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(32, intensity));
        } else {
            vibrator.vibrate(32);
        }
    }

    private int normalizeAutoCloseVibrationIntensity(int intensity) {
        int clamped = Math.max(45, Math.min(300, intensity));
        return Math.round(clamped / 5f) * 5;
    }

    private void applyMediaMuteForScan(boolean openedAutomatically) {
        SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
        boolean shouldMute = openedAutomatically
                ? prefs.getBoolean("mute_media_auto_mode", true)
                : prefs.getBoolean("mute_media_manual_mode", false);
        if (!shouldMute || mediaMutedForScan) return;

        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;

        try {
            mediaVolumeBeforeScan = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            mediaMutedForScan = true;
            Log.d("LookAway", "MEDIA_MUTE start mode=" +
                    (openedAutomatically ? "auto" : "manual") +
                    " previous=" + mediaVolumeBeforeScan);
        } catch (SecurityException e) {
            Log.w("LookAway", "Media mute blocked by OS permissions.");
            mediaVolumeBeforeScan = -1;
            mediaMutedForScan = false;
        } catch (Exception e) {
            Log.w("LookAway", "Media mute failed: " + e.getMessage());
            mediaVolumeBeforeScan = -1;
            mediaMutedForScan = false;
        }
    }

    private void restoreMediaVolumeAfterScan() {
        if (!mediaMutedForScan || mediaVolumeBeforeScan < 0) return;

        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;

        try {
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int restoredVolume = Math.max(0, Math.min(maxVolume, mediaVolumeBeforeScan));
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoredVolume, 0);
            Log.d("LookAway", "MEDIA_MUTE restore volume=" + restoredVolume);
        } catch (SecurityException e) {
            Log.w("LookAway", "Media volume restore blocked by OS permissions.");
        } catch (Exception e) {
            Log.w("LookAway", "Media volume restore failed: " + e.getMessage());
        } finally {
            mediaMutedForScan = false;
            mediaVolumeBeforeScan = -1;
        }
    }

    public boolean isCurrentlyScanning() {
        return isScanning;
    }

    public void enforceCeasefire() {
        lastClickTime = System.currentTimeMillis();
    }

    private void resetShieldStorefrontRecovery(String reason) {
        LookAwayShieldService shield = LookAwayShieldService.getInstance();
        if (shield != null) {
            shield.resetStorefrontRecovery(reason);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        restoreMediaVolumeAfterScan();
        resetShieldStorefrontRecovery("adam_service_destroyed");
        instance = null;
        isRunning = false;
        isScanning = false;
        timerHandler.removeCallbacks(autoStopRunnable);
        if (pendingDisplayRebuildRunnable != null) {
            uiHandler.removeCallbacks(pendingDisplayRebuildRunnable);
            pendingDisplayRebuildRunnable = null;
        }

        if (sessionStartTime > 0) {
            saveActiveTime(System.currentTimeMillis() - sessionStartTime);
            sessionStartTime = 0;
        }

        if (imageReaderThread != null) {
            imageReaderThread.quitSafely();
            imageReaderThread = null;
        }

        if (screenStateReceiver != null) { try { unregisterReceiver(screenStateReceiver); } catch (Exception ignored) {} }
        if (resizeReceiver != null) { try { unregisterReceiver(resizeReceiver); } catch (Exception ignored) {} }

        if (mDisplayManager != null && mDisplayListener != null) { try { mDisplayManager.unregisterDisplayListener(mDisplayListener); } catch (Exception ignored) {} }

        removeFloatingWidget();

        synchronized (displayLock) {
            if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
            if (imageReader != null) imageReader.close();
            if (mediaProjection != null) { mediaProjection.stop(); mediaProjection = null; }
        }

        synchronized (bitmapLock) {
            if (singleReusableBitmap != null && !singleReusableBitmap.isRecycled()) {
                singleReusableBitmap.recycle();
                singleReusableBitmap = null;
            }
        }

        Intent uiIntent = new Intent("com.example.lookaway.RESET_UI");
        uiIntent.setPackage(getPackageName());
        sendBroadcast(uiIntent);
    }
}
