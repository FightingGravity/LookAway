package com.example.lookaway;

import android.app.AlertDialog;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

// NEW IMPORTS FOR BLURVIEW
import eightbitlab.com.blurview.BlurView;
import eightbitlab.com.blurview.RenderScriptBlur;

public class TargetsActivity extends AppCompatActivity {
    private TargetRepository repository;
    private TargetAdapter adapter;
    private List<TargetModel> targetList;
    private ActivityResultLauncher<String> imagePickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_targets);

        // INITIALIZE THE FROSTED GLASS EFFECT
        setupBlurViews();

        repository = new TargetRepository(this);
        targetList = repository.getAllTargets();

        RecyclerView recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TargetAdapter(targetList);
        recyclerView.setAdapter(adapter);

        findViewById(R.id.btn_home).setOnClickListener(v -> finish());

        findViewById(R.id.btn_delete).setOnClickListener(v -> {
            for (TargetModel target : targetList) {
                if (target.isSelected()) {
                    new File(target.getImagePath()).delete();
                }
            }
            targetList.removeIf(TargetModel::isSelected);
            repository.saveTargets(targetList);
            adapter.notifyDataSetChanged();
        });

        imagePickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                promptForTargetName(uri);
            }
        });

        findViewById(R.id.btn_add).setOnClickListener(v -> imagePickerLauncher.launch("image/*"));
    }

    /**
     * Helper method to initialize the real-time background blur math for the navigation bar
     */
    private void setupBlurViews() {
        float blurRadius = 15f; // Match this to your MainActivity for consistency

        ViewGroup rootView = findViewById(android.R.id.content);
        Drawable windowBackground = getWindow().getDecorView().getBackground();

        // Now we only target the single wrapper panel we made in the XML!
        BlurView topNavBlur = findViewById(R.id.top_nav_blur);

        if (windowBackground != null) {
            topNavBlur.setupWith(rootView, new RenderScriptBlur(this))
                    .setFrameClearDrawable(windowBackground)
                    .setBlurRadius(blurRadius);
        }
    }

    private void promptForTargetName(Uri imageUri) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Name this Target");

        final EditText input = new EditText(this);
        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) name = "Unnamed Target";
            processNewTarget(imageUri, name);
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void processNewTarget(Uri uri, String name) {
        try {
            String fileName = "target_" + UUID.randomUUID().toString() + ".png";
            File destFile = new File(getFilesDir(), fileName);

            InputStream is = getContentResolver().openInputStream(uri);
            FileOutputStream fos = new FileOutputStream(destFile);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            fos.flush();
            fos.close();
            is.close();

            TargetModel newTarget = new TargetModel(UUID.randomUUID().toString(), name, destFile.getAbsolutePath());
            repository.addTarget(newTarget);
            targetList.add(newTarget);
            adapter.notifyItemInserted(targetList.size() - 1);

            Toast.makeText(this, "Target Added!", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Failed to save image", Toast.LENGTH_SHORT).show();
        }
    }
}