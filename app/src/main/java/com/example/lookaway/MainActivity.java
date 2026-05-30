package com.example.lookaway;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.opencv.android.OpenCVLoader;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "LookAway";
    private static final int DRAW_OVER_OTHER_APP_PERMISSION_REQUEST_CODE = 1222;
    private static final int SCREEN_CAPTURE_PERMISSION_REQUEST_CODE = 1333;
    private static final int FGS_PERMISSION_REQUEST_CODE = 1444;

    private Button toggleButton;
    private boolean isServiceRunning = false;
    private Intent pendingProjectionData = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (OpenCVLoader.initDebug()) {
            Log.d(TAG, "OpenCV loaded successfully!");
        } else {
            Log.e(TAG, "OpenCV initialization failed.");
        }

        setContentView(R.layout.activity_main);
        toggleButton = findViewById(R.id.btn_toggle_overlay);

        toggleButton.setOnClickListener(v -> {
            if (!isServiceRunning) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(MainActivity.this)) {
                    requestOverlayPermission();
                } else {
                    requestScreenCapturePermission();
                }
            } else {
                stopFloatingWidgetService();
            }
        });
    }

    private void requestOverlayPermission() {
        Toast.makeText(this, "Please enable 'Display over other apps'", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, DRAW_OVER_OTHER_APP_PERMISSION_REQUEST_CODE);
    }

    private void requestScreenCapturePermission() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager != null) {
            startActivityForResult(manager.createScreenCaptureIntent(), SCREEN_CAPTURE_PERMISSION_REQUEST_CODE);
        }
    }

    private void checkAndStartService(Intent data) {
        List<String> permissionsToRequest = new ArrayList<>();

        // Check for Notification Permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        // Check for Media Projection Foreground Service Permission (Android 14+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            Log.d(TAG, "Permissions missing. Requesting now...");
            pendingProjectionData = data;
            requestPermissions(permissionsToRequest.toArray(new String[0]), FGS_PERMISSION_REQUEST_CODE);
            return;
        }

        Log.d(TAG, "Permissions already granted, proceeding to start service.");
        startFloatingWidgetService(data);
    }

    private void startFloatingWidgetService(Intent projectionTokenData) {
        Intent serviceIntent = new Intent(MainActivity.this, FloatingWidgetService.class);
        if (projectionTokenData != null) {
            serviceIntent.putExtra("projection_data", projectionTokenData);
        }

        Log.d(TAG, "Starting Foreground Service...");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        toggleButton.setText("Disable Overlay");
        isServiceRunning = true;
    }

    private void stopFloatingWidgetService() {
        stopService(new Intent(MainActivity.this, FloatingWidgetService.class));
        toggleButton.setText("Enable Overlay");
        isServiceRunning = false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == FGS_PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                Log.d(TAG, "Permissions granted by user, starting service.");
                startFloatingWidgetService(pendingProjectionData);
            } else {
                Log.e(TAG, "Permissions denied by user.");
                Toast.makeText(this, "Required permissions denied. Cannot start service.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == DRAW_OVER_OTHER_APP_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                requestScreenCapturePermission();
            }
        } else if (requestCode == SCREEN_CAPTURE_PERMISSION_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                checkAndStartService(data);
            } else {
                Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_LONG).show();
            }
        }
    }
}