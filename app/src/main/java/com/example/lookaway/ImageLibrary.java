package com.example.lookaway;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import java.util.ArrayList;
import java.util.List;

public class ImageLibrary {

    public static class Template {
        public String name;
        public Bitmap bitmap;
        public Mat grayMat; // Pre-cached grayscale Mat

        public Template(String name, Bitmap bitmap, Mat grayMat) {
            this.name = name;
            this.bitmap = bitmap;
            this.grayMat = grayMat;
        }
    }

    public static List<Template> loadTargetTemplates(Context context) {
        List<Template> templates = new ArrayList<>();
        int[] ids = {R.drawable.close1, R.drawable.close2, R.drawable.close3, R.drawable.close4, R.drawable.close5,
                R.drawable.close6, R.drawable.close7, R.drawable.close8, R.drawable.close9, R.drawable.close10,
                R.drawable.close11, R.drawable.close12, R.drawable.close13};
        String[] names = {"close1", "close2", "close3", "close4", "close5", "close6", "close7", "close8", "close9",
                "close10", "close11", "close12", "close13"};

        for (int i = 0; i < ids.length; i++) {
            Template template = load(context, names[i], ids[i]);
            // Only add healthy, fully-loaded templates to the engine
            if (template != null) {
                templates.add(template);
            }
        }
        return templates;
    }

    private static Template load(Context context, String name, int resourceId) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false; // Important: prevent Android from resizing your assets
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), resourceId, options);

        // SAFTEY CHECK 1: Did the image file actually decode into a Bitmap?
        if (bitmap == null) {
            Log.e("LookAway", "Asset Error: Could not decode " + name + ". Check if the file is corrupted or missing.");
            return null;
        }

        Mat temp = new Mat();
        Utils.bitmapToMat(bitmap, temp);

        // Convert to Grayscale (1-channel, 8-bit)
        Mat gray = new Mat();
        Imgproc.cvtColor(temp, gray, Imgproc.COLOR_RGBA2GRAY);

        // Release temporary RGBA Mat to save memory
        temp.release();

        // SAFETY CHECK 2: Is the resulting OpenCV Matrix valid?
        if (gray.empty()) {
            Log.e("LookAway", "Asset Error: OpenCV created an empty matrix for " + name);
            gray.release();
            bitmap.recycle();
            return null;
        }

        // Return template with full-resolution grayscale Mat
        return new Template(name, bitmap, gray);
    }
}