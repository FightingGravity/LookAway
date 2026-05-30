package com.example.lookaway;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
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
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import androidx.core.app.NotificationCompat;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;
import java.nio.ByteBuffer;
import java.util.List;

public class FloatingWidgetService extends Service {
    private WindowManager mWindowManager;
    private View mFloatingView;
    private Button playStopButton;
    private boolean isScanning = false;
    private String lastLockedTemplate = null;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Thread brainThread;

    // DYNAMIC TIMER COMPONENTS
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoStopRunnable = this::stopAutomationBrain;
    private static final long TIMEOUT_DURATION = 45000; // 45 seconds

    private int screenWidth, screenHeight, screenDensity;
    private List<ImageLibrary.Template> targetTemplates;

    private static final double MATCH_THRESHOLD = 0.85;
    private static final String CHANNEL_ID = "LookAway_Scanner_Channel";
    private static final int NOTIFICATION_ID = 9911;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

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

                imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3);
                virtualDisplay = mediaProjection.createVirtualDisplay("LookAway_Eyes", screenWidth, screenHeight, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, null);
            }
        } catch (Exception e) { Log.e("LookAway", "Projection Init Failed: " + e.getMessage()); }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mFloatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null);
        int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, layoutFlag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mWindowManager.addView(mFloatingView, params);
        playStopButton = mFloatingView.findViewById(R.id.play_stop_btn);
        playStopButton.setOnClickListener(v -> {
            isScanning = !isScanning;
            if (isScanning) startAutomationBrain(); else stopAutomationBrain();
        });
    }

    private void startAutomationBrain() {
        if (imageReader == null) return;
        targetTemplates = ImageLibrary.loadTargetTemplates(this);

        // Arm the dynamic timer
        resetAutoStopTimer();

        uiHandler.post(() -> {
            playStopButton.setText("Stop");
            playStopButton.setBackgroundTintList(ColorStateList.valueOf(Color.RED));
        });

        brainThread = new Thread(() -> {
            while (isScanning) {
                long startTime = System.currentTimeMillis();
                captureAndAnalyzeFrame();
                long processTime = System.currentTimeMillis() - startTime;
                Log.d("LookAway", "Frame processed in: " + processTime + "ms");
                long sleepTime = 1000 - processTime;
                try { if (sleepTime > 0) Thread.sleep(sleepTime); } catch (InterruptedException e) { break; }
            }
        });
        brainThread.start();
    }

    private void resetAutoStopTimer() {
        timerHandler.removeCallbacks(autoStopRunnable);
        timerHandler.postDelayed(autoStopRunnable, TIMEOUT_DURATION);
    }

    private void captureAndAnalyzeFrame() {
        Image image = imageReader.acquireLatestImage();
        if (image == null) return;
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - (pixelStride * screenWidth);
        Bitmap paddedBitmap = Bitmap.createBitmap(screenWidth + (rowPadding / pixelStride), screenHeight, Bitmap.Config.ARGB_8888);
        paddedBitmap.copyPixelsFromBuffer(buffer);
        Bitmap screenSnapshot = Bitmap.createBitmap(paddedBitmap, 0, 0, screenWidth, screenHeight);
        paddedBitmap.recycle();
        image.close();
        processVisualPixels(screenSnapshot);
    }

    private void processVisualPixels(Bitmap snapshot) {
        Mat fullMat = new Mat();
        Utils.bitmapToMat(snapshot, fullMat);
        Mat grayScreen = new Mat();
        Imgproc.cvtColor(fullMat, grayScreen, Imgproc.COLOR_RGBA2GRAY);
        fullMat.release();

        int cropHeight = (int) (grayScreen.rows() * 0.30);
        Mat croppedScreen = new Mat(grayScreen, new Rect(0, 0, grayScreen.cols(), cropHeight));
        grayScreen.release();

        if (targetTemplates != null) {
            for (ImageLibrary.Template template : targetTemplates) {
                if (template.grayMat == null || template.grayMat.empty()) continue;
                Mat result = new Mat();
                Imgproc.matchTemplate(croppedScreen, template.grayMat, result, Imgproc.TM_CCOEFF_NORMED);
                Core.MinMaxLocResult mmr = Core.minMaxLoc(result);

                if (mmr.maxVal >= MATCH_THRESHOLD && !template.name.equals(lastLockedTemplate)) {
                    // HIT DETECTED: Reset the 45s timer
                    resetAutoStopTimer();

                    triggerSystemClick((int)(mmr.maxLoc.x + template.bitmap.getWidth()/2.0), (int)(mmr.maxLoc.y + template.bitmap.getHeight()/2.0));
                    lastLockedTemplate = template.name;
                }
                result.release();
            }
        }
        if (croppedScreen != null) croppedScreen.release();
        snapshot.recycle();
    }

    private void triggerSystemClick(int x, int y) {
        if (LookAwayClickerService.getInstance() != null) LookAwayClickerService.getInstance().clickAtCoordinates(x, y);
    }

    private void stopAutomationBrain() {
        isScanning = false;
        timerHandler.removeCallbacks(autoStopRunnable);
        uiHandler.post(() -> {
            playStopButton.setText("Play");
            playStopButton.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#669900")));
        });
        lastLockedTemplate = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isScanning = false;
        timerHandler.removeCallbacks(autoStopRunnable);
        if (mWindowManager != null && mFloatingView != null) {
            try { mWindowManager.removeView(mFloatingView); } catch (Exception e) {}
        }
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
    }
}