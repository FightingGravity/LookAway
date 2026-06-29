package com.example.lookaway;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

public class LookAwayPermissionProxyActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager != null) {
            // Launch the system screen-capture permission dialog.
            startActivityForResult(manager.createScreenCaptureIntent(), 999);
        } else {
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 999 && resultCode == RESULT_OK && data != null) {
            // Pass the granted screen-capture session to ADAM's foreground service.
            Intent engineIntent = new Intent(this, LookAwayMasterEngine.class);
            engineIntent.setAction("ACTION_UPDATE_TOKEN");
            engineIntent.putExtra("projection_data", data);
            startService(engineIntent);
        }

        // Close this transparent proxy without leaving a visible transition.
        finish();
        overridePendingTransition(0, 0);
    }
}
