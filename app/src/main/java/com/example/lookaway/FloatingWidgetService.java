package com.example.lookaway;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
import android.view.Surface;
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
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class FloatingWidgetService extends Service {

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

    // Acquisition Mode Variables
    private View acquisitionFullScreenCatcher;
    private View acquisitionControlPanel;
    private Mat frozenMat;
    private int targetTapX, targetTapY;
    private Runnable acquisitionRunnable;
    private boolean isWaitingForTargetTap = false;

    private long lastClickTime = 0;
    private static final long CLICK_COOLDOWN_MS = 1000;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Thread brainThread;

    // NEW: Time Tracking
    private long sessionStartTime = 0;

    private final Object displayLock = new Object();

    private DisplayManager mDisplayManager;
    private DisplayManager.DisplayListener mDisplayListener;

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoStopRunnable = this::stopAutomationBrain;

    private double currentMatchThreshold = 0.85;

    // ROI Config Variables
    private int roiMode = 0; // 0=Multi, 1=Top, 2=Bot
    private int roiPercent = 15;

    private int screenWidth, screenHeight, screenDensity;
    private List<ImageLibrary.Template> targetTemplates;

    private HitAnimationView hitAnimationView;

    private static final String CHANNEL_ID = "LookAway_Scanner_Channel";
    private static final int NOTIFICATION_ID = 9911;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private BroadcastReceiver screenStateReceiver;

    // --- NEW: Real-Time Resize Receiver ---
    private BroadcastReceiver resizeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.example.lookaway.WIDGET_RESIZE".equals(intent.getAction())) {
                int newScale = intent.getIntExtra("new_scale", 100);
                applyWidgetScale(newScale);
            }
        }
    };

    // --- NEW: Automation Receiver ---
    private BroadcastReceiver automationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("com.example.lookaway.START_AUTO_SCAN".equals(action)) {
                if (!isScanning) {
                    isScanning = true;
                    startAutomationBrain();
                }
            } else if ("com.example.lookaway.STOP_AUTO_SCAN".equals(action)) {
                if (isScanning) {
                    isScanning = false;
                    stopAutomationBrain();
                }
            }
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("LookAway Engine Active")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (intent != null && intent.hasExtra("projection_data")) {
            setupProjectionEngine((Intent) intent.getParcelableExtra("projection_data"));
        } else {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID, "LookAway Scanner Service", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(serviceChannel);
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
                mediaProjection = manager.getMediaProjection(Activity.RESULT_OK, data);
                mediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() { Log.d("LookAway", "Projection stopped."); }
                }, new Handler(Looper.getMainLooper()));

                buildVirtualDisplay();
            }

            mDisplayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            mDisplayListener = new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int displayId) {}
                @Override public void onDisplayRemoved(int displayId) {}
                @Override public void onDisplayChanged(int displayId) {
                    if (displayId != Display.DEFAULT_DISPLAY) return;
                    try {
                        DisplayMetrics newMetrics = new DisplayMetrics();
                        wm.getDefaultDisplay().getRealMetrics(newMetrics);

                        if (newMetrics.widthPixels != screenWidth || newMetrics.heightPixels != screenHeight) {
                            screenWidth = newMetrics.widthPixels;
                            screenHeight = newMetrics.heightPixels;
                            buildVirtualDisplay();

                            if (params != null && mFloatingView != null && mWindowManager != null) {
                                if (params.x > screenWidth) params.x = Math.max(0, screenWidth - 150);
                                if (params.y > screenHeight) params.y = Math.max(0, screenHeight - 150);
                                try { mWindowManager.updateViewLayout(mFloatingView, params); } catch (Exception e) {}
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

    private void buildVirtualDisplay() {
        synchronized (displayLock) {
            if (imageReader != null) imageReader.close();

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3);

            if (virtualDisplay == null) {
                virtualDisplay = mediaProjection.createVirtualDisplay("LookAway_Eyes", screenWidth, screenHeight, screenDensity,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                        imageReader.getSurface(), null, null);
            } else {
                virtualDisplay.setSurface(null);
                virtualDisplay.resize(screenWidth, screenHeight, screenDensity);
                virtualDisplay.setSurface(imageReader.getSurface());
            }
        }
    }

    // --- NEW: Dynamic Resizer Math ---
    private void applyWidgetScale(int widgetScale) {
        float scaleFactor = widgetScale / 100f;
        float density = getResources().getDisplayMetrics().density;

        int scaledSizePx = (int) (80 * density * scaleFactor);
        int scaledPaddingPx = (int) (8 * density * scaleFactor);

        if (playStopIcon != null) {
            ViewGroup.LayoutParams iconParams = playStopIcon.getLayoutParams();
            if (iconParams != null) {
                iconParams.width = scaledSizePx;
                iconParams.height = scaledSizePx;
                playStopIcon.setLayoutParams(iconParams);
            }
            playStopIcon.setPadding(scaledPaddingPx, scaledPaddingPx, scaledPaddingPx, scaledPaddingPx);
        }

        if (params != null) {
            // Explicitly force the WindowManager master container to match the new size
            params.width = scaledSizePx;
            params.height = scaledSizePx;

            if (mWindowManager != null && mFloatingView != null) {
                try {
                    mWindowManager.updateViewLayout(mFloatingView, params);
                } catch (Exception e) {
                    // Ignored: Window not fully attached yet, perfectly fine!
                }
            }
        }
    }
    // ---------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;

        mFloatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null);
        mFloatingView.setAlpha(0.85f);

        playStopIcon = mFloatingView.findViewById(R.id.play_stop_btn);

        int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, layoutFlag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER_VERTICAL | Gravity.START;

        SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
        params.x = prefs.getInt("widget_x", 0);
        params.y = prefs.getInt("widget_y", 0);

        // Run the math BEFORE adding to WindowManager
        int initialScale = prefs.getInt("widget_scale", 100);
        applyWidgetScale(initialScale);

        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mWindowManager.addView(mFloatingView, params);

        hitAnimationView = new HitAnimationView(this);
        WindowManager.LayoutParams animParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        mWindowManager.addView(hitAnimationView, animParams);

        // Register the live resize listener
        ContextCompat.registerReceiver(
                this,
                resizeReceiver,
                new IntentFilter("com.example.lookaway.WIDGET_RESIZE"),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );

        // Register the automation trigger listener
        IntentFilter automationFilter = new IntentFilter();
        automationFilter.addAction("com.example.lookaway.START_AUTO_SCAN");
        automationFilter.addAction("com.example.lookaway.STOP_AUTO_SCAN");
        ContextCompat.registerReceiver(this, automationReceiver, automationFilter, ContextCompat.RECEIVER_NOT_EXPORTED);

        acquisitionRunnable = () -> {
            isWaitingForTargetTap = true;
            playStopIcon.setImageResource(R.drawable.ic_target_mode);
            launchFullScreenTapCatcher();
        };

        longPressRunnable = () -> {
            isWaitingForTargetTap = false;
            removeFullScreenTapCatcher();
            isDragEnabled = true;
            playStopIcon.setAlpha(0.5f);
            playStopIcon.setImageResource(R.drawable.ic_eye_closed);
        };

        playStopIcon.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isDragEnabled = false;
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();

                    longPressHandler.postDelayed(acquisitionRunnable, 1000);
                    longPressHandler.postDelayed(longPressRunnable, 2500);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    int deltaX = (int) (event.getRawX() - initialTouchX);
                    int deltaY = (int) (event.getRawY() - initialTouchY);

                    if (isDragEnabled) {
                        params.x = initialX + deltaX;
                        params.y = initialY + deltaY;
                        mWindowManager.updateViewLayout(mFloatingView, params);
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
                        playStopIcon.setAlpha(1.0f);
                        SharedPreferences.Editor editor = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE).edit();
                        editor.putInt("widget_x", params.x);
                        editor.putInt("widget_y", params.y);
                        editor.apply();

                    } else if (isWaitingForTargetTap) {
                        // Waiting for tap
                    } else {
                        int finalDeltaX = (int) (event.getRawX() - initialTouchX);
                        int finalDeltaY = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(finalDeltaX) < 10 && Math.abs(finalDeltaY) < 10) {
                            isScanning = !isScanning;
                            if (isScanning) {
                                startAutomationBrain();
                            } else {
                                stopAutomationBrain();
                            }
                        }
                    }
                    return true;
            }
            return false;
        });

        screenStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    stopSelf();
                }
            }
        };
        registerReceiver(screenStateReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    private void launchFullScreenTapCatcher() {
        if (acquisitionFullScreenCatcher != null) return;

        acquisitionFullScreenCatcher = new View(this);
        acquisitionFullScreenCatcher.setBackgroundColor(0x00000000);

        WindowManager.LayoutParams catchParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        acquisitionFullScreenCatcher.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                targetTapX = (int) event.getRawX();
                targetTapY = (int) event.getRawY();

                freezeScreenForCropping();

                removeFullScreenTapCatcher();
                launchCropControlPanel();
            }
            return true;
        });

        mWindowManager.addView(acquisitionFullScreenCatcher, catchParams);
    }

    private void removeFullScreenTapCatcher() {
        if (acquisitionFullScreenCatcher != null) {
            mWindowManager.removeView(acquisitionFullScreenCatcher);
            acquisitionFullScreenCatcher = null;
        }
    }

    private void freezeScreenForCropping() {
        synchronized (displayLock) {
            if (imageReader == null) return;
            try {
                Image image = imageReader.acquireLatestImage();
                if (image != null) {
                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - (pixelStride * screenWidth);

                    Bitmap paddedBitmap = Bitmap.createBitmap(screenWidth + (rowPadding / pixelStride), screenHeight, Bitmap.Config.ARGB_8888);
                    paddedBitmap.copyPixelsFromBuffer(buffer);
                    Bitmap exactScreen = Bitmap.createBitmap(paddedBitmap, 0, 0, screenWidth, screenHeight);
                    paddedBitmap.recycle();

                    frozenMat = new Mat();
                    Utils.bitmapToMat(exactScreen, frozenMat);
                    Imgproc.cvtColor(frozenMat, frozenMat, Imgproc.COLOR_RGBA2GRAY);

                    exactScreen.recycle();
                    image.close();
                }
            } catch (Exception e) {
                Log.e("LookAway", "Failed to freeze frame for crop", e);
            }
        }
    }

    // --- RECALCULATION HELPER METHOD ---
    private void updateReticleTouchDelegate(View reticleBox, View parentContainer) {
        float density = getResources().getDisplayMetrics().density;
        final int touchOffset = (int) (40 * density);

        parentContainer.post(() -> {
            android.graphics.Rect delegateArea = new android.graphics.Rect();

            // Calculate bounds manually using the live, translated X/Y coordinates
            delegateArea.left = (int) reticleBox.getX();
            delegateArea.top = (int) reticleBox.getY();
            delegateArea.right = delegateArea.left + reticleBox.getWidth();
            delegateArea.bottom = delegateArea.top + reticleBox.getHeight();

            // Inflate the bounding box
            delegateArea.left -= touchOffset;
            delegateArea.top -= touchOffset;
            delegateArea.right += touchOffset;
            delegateArea.bottom += touchOffset;

            parentContainer.setTouchDelegate(new android.view.TouchDelegate(delegateArea, reticleBox));
        });
    }

    private void launchCropControlPanel() {
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        acquisitionControlPanel = inflater.inflate(R.layout.layout_acquisition_panel, null);

        WindowManager.LayoutParams panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                0,
                PixelFormat.TRANSLUCENT
        );

        View reticleBox = acquisitionControlPanel.findViewById(R.id.reticle_box);
        SeekBar cropSlider = acquisitionControlPanel.findViewById(R.id.crop_slider);
        EditText nameInput = acquisitionControlPanel.findViewById(R.id.target_name_input);
        Button btnCancel = acquisitionControlPanel.findViewById(R.id.btn_cancel_crop);
        Button btnSave = acquisitionControlPanel.findViewById(R.id.btn_save_crop);

        LinearLayout controlPanelContainer = acquisitionControlPanel.findViewById(R.id.control_panel_container);
        RelativeLayout.LayoutParams containerParams = (RelativeLayout.LayoutParams) controlPanelContainer.getLayoutParams();

        if (targetTapY > (screenHeight / 2)) {
            containerParams.addRule(RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.TRUE);
        } else {
            containerParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE);
        }
        controlPanelContainer.setLayoutParams(containerParams);

        int statusBarHeight = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = getResources().getDimensionPixelSize(resourceId);
        }
        final int finalStatusBarHeight = statusBarHeight;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { cropSlider.setMin(16); }

        int initialSize = cropSlider.getProgress();
        reticleBox.getLayoutParams().width = initialSize;
        reticleBox.getLayoutParams().height = initialSize;

        reticleBox.setX(targetTapX - (initialSize / 2f));
        reticleBox.setY(targetTapY - (initialSize / 2f) - finalStatusBarHeight);

        // Call the touch target helper for initial setup
        updateReticleTouchDelegate(reticleBox, acquisitionControlPanel);

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

                    v.setX(newX);
                    v.setY(newY);

                    targetTapX = (int) (newX + (v.getWidth() / 2f));
                    targetTapY = (int) (newY + (v.getHeight() / 2f) + finalStatusBarHeight);
                    return true;

                case MotionEvent.ACTION_UP:
                    // Recalculate touch pad after dropping the box
                    updateReticleTouchDelegate(v, acquisitionControlPanel);
                    return true;

                default:
                    return false;
            }
        });

        cropSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int safeSize = Math.max(16, progress);
                reticleBox.getLayoutParams().width = safeSize;
                reticleBox.getLayoutParams().height = safeSize;

                reticleBox.setX(targetTapX - (safeSize / 2f));
                reticleBox.setY(targetTapY - (safeSize / 2f) - finalStatusBarHeight);

                reticleBox.requestLayout();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                // Recalculate touch pad after resizing is finished
                updateReticleTouchDelegate(reticleBox, acquisitionControlPanel);
            }
        });

        btnCancel.setOnClickListener(v -> closeCropPanel());

        btnSave.setOnClickListener(v -> {
            String targetName = nameInput.getText().toString().trim();
            if (targetName.isEmpty()) targetName = "Unnamed Target";

            saveTargetAndCleanup(cropSlider.getProgress(), targetName);
        });

        mWindowManager.addView(acquisitionControlPanel, panelParams);
    }

    private void closeCropPanel() {
        if (acquisitionControlPanel != null) {
            mWindowManager.removeView(acquisitionControlPanel);
            acquisitionControlPanel = null;
        }
        if (frozenMat != null) {
            frozenMat.release();
            frozenMat = null;
        }
        isWaitingForTargetTap = false;
        playStopIcon.setImageResource(R.drawable.ic_eye_closed);
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

            } catch (Exception e) {
                Log.e("LookAway", "Error saving cropped target", e);
            }

            targetTemplate.release();
        }
        closeCropPanel();
    }

    // NEW: Helper method to save daily time
    private void saveActiveTime(long durationMillis) {
        if (durationMillis <= 0) return;
        SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
        String dateString = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        String key = "active_time_" + dateString;
        long currentDayTime = prefs.getLong(key, 0);
        prefs.edit().putLong(key, currentDayTime + durationMillis).apply();
    }

    private void startAutomationBrain() {
        if (imageReader == null) return;

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isScanning) return;

            targetTemplates = ImageLibrary.loadTargetTemplates(this);

            // Start the idle timeout countdown
            resetAutoStopTimer();

            uiHandler.post(() -> {
                playStopIcon.setImageResource(R.drawable.ic_eye_open);
            });

            // NEW: Set session start time
            sessionStartTime = System.currentTimeMillis();

            brainThread = new Thread(() -> {
                while (isScanning) {
                    long startTime = System.currentTimeMillis();

                    // NEW: Periodic rolling save every 60 seconds
                    if (startTime - sessionStartTime >= 60000) {
                        saveActiveTime(startTime - sessionStartTime);
                        sessionStartTime = startTime;
                    }

                    SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
                    int accuracyPercent = prefs.getInt("match_accuracy", 85);
                    currentMatchThreshold = accuracyPercent / 100.0;
                    roiMode = prefs.getInt("roi_mode", 0);
                    roiPercent = prefs.getInt("roi_percent", 15);

                    captureAndAnalyzeFrame();

                    long processTime = System.currentTimeMillis() - startTime;
                    long sleepTime = 1000 - processTime;
                    try { if (sleepTime > 0) Thread.sleep(sleepTime); } catch (InterruptedException e) { break; }
                }
            });
            brainThread.start();
        }, 500);
    }

    private void resetAutoStopTimer() {
        timerHandler.removeCallbacks(autoStopRunnable);

        SharedPreferences prefs = getSharedPreferences("LookAwayPrefs", MODE_PRIVATE);
        boolean isAutomatic = prefs.getBoolean("is_automatic_mode", false);
        long dynamicTimeoutDuration;

        if (isAutomatic) {
            // Hardcode 3 minutes (180,000 milliseconds) for automatic mode
            dynamicTimeoutDuration = 180000L;
        } else {
            // Pull the user's saved slider setting, defaulting to 30 if null
            int timeoutSeconds = prefs.getInt("timeout_seconds", 30);
            dynamicTimeoutDuration = timeoutSeconds * 1000L;
        }

        timerHandler.postDelayed(autoStopRunnable, dynamicTimeoutDuration);
    }

    private void captureAndAnalyzeFrame() {
        Bitmap screenSnapshot = null;

        synchronized (displayLock) {
            if (imageReader == null) return;

            Image image = null;
            try {
                image = imageReader.acquireLatestImage();
            } catch (Exception e) {
                return;
            }

            if (image == null) return;

            if (System.currentTimeMillis() - lastClickTime < CLICK_COOLDOWN_MS) {
                image.close();
                return;
            }

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - (pixelStride * screenWidth);

            if (screenWidth > 0 && screenHeight > 0) {
                Bitmap paddedBitmap = Bitmap.createBitmap(screenWidth + (rowPadding / pixelStride), screenHeight, Bitmap.Config.ARGB_8888);
                paddedBitmap.copyPixelsFromBuffer(buffer);
                screenSnapshot = Bitmap.createBitmap(paddedBitmap, 0, 0, screenWidth, screenHeight);
                paddedBitmap.recycle();
            }
            image.close();
        }

        if (screenSnapshot != null) {
            processVisualPixels(screenSnapshot);
        }
    }

    private void processVisualPixels(Bitmap snapshot) {
        Mat fullMat = new Mat();
        Utils.bitmapToMat(snapshot, fullMat);
        Mat grayScreen = new Mat();
        Imgproc.cvtColor(fullMat, grayScreen, Imgproc.COLOR_RGBA2GRAY);
        fullMat.release();

        double marginRatio = (roiPercent + 5.0) / 100.0;
        int cropHeight = (int) (grayScreen.rows() * marginRatio);
        int screenBottomStart = grayScreen.rows() - cropHeight;

        Mat topSlice = null;
        Mat bottomSlice = null;

        if (roiMode == 0 || roiMode == 1) {
            topSlice = new Mat(grayScreen, new Rect(0, 0, grayScreen.cols(), cropHeight));
        }
        if (roiMode == 0 || roiMode == 2) {
            bottomSlice = new Mat(grayScreen, new Rect(0, screenBottomStart, grayScreen.cols(), cropHeight));
        }

        boolean foundMatch = false;

        if (targetTemplates != null) {
            for (ImageLibrary.Template template : targetTemplates) {
                if (template.grayMat == null || template.grayMat.empty()) continue;

                if (topSlice != null && template.grayMat.cols() < topSlice.cols() && template.grayMat.rows() < topSlice.rows()) {
                    foundMatch = scanAndClick(topSlice, template, 0);
                    if (foundMatch) break;
                }

                if (bottomSlice != null && template.grayMat.cols() < bottomSlice.cols() && template.grayMat.rows() < bottomSlice.rows()) {
                    foundMatch = scanAndClick(bottomSlice, template, screenBottomStart);
                    if (foundMatch) break;
                }
            }
        }

        if (topSlice != null) topSlice.release();
        if (bottomSlice != null) bottomSlice.release();
        grayScreen.release();
        snapshot.recycle();
    }

    private boolean scanAndClick(Mat slice, ImageLibrary.Template template, int yOffset) {
        Mat result = new Mat();
        Imgproc.matchTemplate(slice, template.grayMat, result, Imgproc.TM_CCOEFF_NORMED);
        Core.MinMaxLocResult mmr = Core.minMaxLoc(result);

        if (mmr.maxVal >= currentMatchThreshold) {
            int clickX = (int)(mmr.maxLoc.x + template.grayMat.cols() / 2.0);
            int clickY = (int)(mmr.maxLoc.y + template.grayMat.rows() / 2.0) + yOffset;

            triggerSystemClick(clickX, clickY);
            lastClickTime = System.currentTimeMillis();

            // Reset the idle timer because we found a target!
            resetAutoStopTimer();

            TargetRepository repo = new TargetRepository(FloatingWidgetService.this);
            repo.incrementHitCountForTarget(template.id);
            int totalHits = repo.getHitCountForTarget(template.id);

            uiHandler.post(() -> {
                if (hitAnimationView != null) {
                    hitAnimationView.playAnimation(clickX, clickY, totalHits);
                }
            });

            result.release();
            return true;
        }
        result.release();
        return false;
    }

    private void triggerSystemClick(int x, int y) {
        if (LookAwayClickerService.getInstance() != null) LookAwayClickerService.getInstance().clickAtCoordinates(x, y);
    }

    private void stopAutomationBrain() {
        isScanning = false;
        timerHandler.removeCallbacks(autoStopRunnable);
        uiHandler.post(() -> {
            playStopIcon.setImageResource(R.drawable.ic_eye_closed);
        });

        // NEW: Final save when stopped
        if (sessionStartTime > 0) {
            saveActiveTime(System.currentTimeMillis() - sessionStartTime);
            sessionStartTime = 0;
        }

        Intent uiIntent = new Intent("com.example.lookaway.RESET_UI");
        uiIntent.setPackage(getPackageName());
        sendBroadcast(uiIntent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        isScanning = false;
        timerHandler.removeCallbacks(autoStopRunnable);

        // NEW: Final save if service is destroyed while scanning
        if (sessionStartTime > 0) {
            saveActiveTime(System.currentTimeMillis() - sessionStartTime);
            sessionStartTime = 0;
        }

        if (screenStateReceiver != null) {
            try { unregisterReceiver(screenStateReceiver); } catch (Exception e) {}
        }

        if (resizeReceiver != null) {
            try { unregisterReceiver(resizeReceiver); } catch (Exception e) {}
        }

        if (automationReceiver != null) {
            try { unregisterReceiver(automationReceiver); } catch (Exception e) {}
        }

        if (mDisplayManager != null && mDisplayListener != null) {
            try { mDisplayManager.unregisterDisplayListener(mDisplayListener); } catch (Exception e) {}
        }

        if (mWindowManager != null && hitAnimationView != null) {
            try { mWindowManager.removeView(hitAnimationView); } catch (Exception e) {}
        }

        if (mWindowManager != null && mFloatingView != null) {
            try { mWindowManager.removeView(mFloatingView); } catch (Exception e) {}
        }

        synchronized (displayLock) {
            if (virtualDisplay != null) {
                virtualDisplay.setSurface(null);
                virtualDisplay.release();
            }
            if (imageReader != null) imageReader.close();
        }

        Intent uiIntent = new Intent("com.example.lookaway.RESET_UI");
        uiIntent.setPackage(getPackageName());
        sendBroadcast(uiIntent);
    }
}