package com.example.lookaway;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ImageLibrary {

    public static class Template {
        public String id;
        public String name;
        public Bitmap bitmap;
        public Mat grayMat;

        public Template(String id, String name, Bitmap bitmap, Mat grayMat) {
            this.id = id;
            this.name = name;
            this.bitmap = bitmap;
            this.grayMat = grayMat;
        }
    }

    public static List<Template> loadTargetTemplates(Context context) {
        List<Template> templates = new ArrayList<>();
        TargetRepository repository = new TargetRepository(context);
        List<TargetModel> savedTargets = repository.getAllTargets();

        for (TargetModel target : savedTargets) {
            File imgFile = new File(target.getImagePath());
            if (imgFile.exists()) {
                Bitmap bitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                if (bitmap != null) {
                    try {
                        Mat mat = new Mat();
                        Utils.bitmapToMat(bitmap, mat);

                        Mat grayMat = new Mat();
                        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGBA2GRAY);
                        mat.release();

                        templates.add(new Template(target.getId(), target.getName(), bitmap, grayMat));
                        Log.d("LookAway", "Successfully loaded target: " + target.getName());
                    } catch (Exception e) {
                        Log.e("LookAway", "OpenCV Conversion failed for: " + target.getName(), e);
                    }
                }
            }
        }
        Log.d("LookAway", "Total targets loaded into brain: " + templates.size());
        return templates;
    }
}