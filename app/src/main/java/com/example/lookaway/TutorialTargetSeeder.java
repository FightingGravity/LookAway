package com.example.lookaway;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.Iterator;
import java.util.List;

public class TutorialTargetSeeder {
    private static final String TAG = "LookAway";
    private static final String NEXT_ID = "tutorial_target_next";
    private static final String X_ID = "tutorial_target_google_play_x";

    private TutorialTargetSeeder() {}

    public static void seedIfNeeded(Context context) {
        TargetRepository repository = new TargetRepository(context);
        List<TargetModel> targets = repository.getAllTargets();
        boolean removedAny = false;

        Iterator<TargetModel> iterator = targets.iterator();
        while (iterator.hasNext()) {
            TargetModel target = iterator.next();
            if (isDeprecatedTutorialTarget(target)) {
                deleteTargetImage(target);
                iterator.remove();
                removedAny = true;
            }
        }

        if (removedAny) {
            repository.saveTargets(targets);
            Log.d(TAG, "Removed deprecated tutorial seed targets from target library.");
        }
    }

    private static boolean isDeprecatedTutorialTarget(TargetModel target) {
        return NEXT_ID.equals(target.getId()) || X_ID.equals(target.getId());
    }

    private static void deleteTargetImage(TargetModel target) {
        String imagePath = target.getImagePath();
        if (imagePath == null || imagePath.isEmpty()) return;

        File imageFile = new File(imagePath);
        if (imageFile.exists() && !imageFile.delete()) {
            Log.w(TAG, "Unable to delete deprecated tutorial target image: " + imagePath);
        }
    }
}
