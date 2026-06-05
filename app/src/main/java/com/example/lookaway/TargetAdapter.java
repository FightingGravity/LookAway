package com.example.lookaway;

import android.app.Activity;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

// NEW IMPORTS FOR BLURVIEW
import eightbitlab.com.blurview.BlurView;
import eightbitlab.com.blurview.RenderScriptBlur;

public class TargetAdapter extends RecyclerView.Adapter<TargetAdapter.TargetViewHolder> {
    private List<TargetModel> targetList;

    public TargetAdapter(List<TargetModel> targetList) {
        this.targetList = targetList;
    }

    @NonNull
    @Override
    public TargetViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_target, parent, false);
        // Cast the parent context to Activity so we can grab the main window background
        return new TargetViewHolder(view, (Activity) parent.getContext());
    }

    @Override
    public void onBindViewHolder(@NonNull TargetViewHolder holder, int position) {
        TargetModel target = targetList.get(position);
        holder.nameText.setText(target.getName());
        holder.hitCountText.setText("Hits (30d): " + target.getHitCount30Days());
        holder.checkBox.setChecked(target.isSelected());

        if (target.getImagePath() != null) {
            holder.thumbnail.setImageBitmap(BitmapFactory.decodeFile(target.getImagePath()));
        }

        holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> target.setSelected(isChecked));
    }

    @Override
    public int getItemCount() { return targetList.size(); }

    static class TargetViewHolder extends RecyclerView.ViewHolder {
        TextView nameText, hitCountText;
        CheckBox checkBox;
        ImageView thumbnail;
        BlurView blurView;

        TargetViewHolder(View itemView, Activity activity) {
            super(itemView);
            nameText = itemView.findViewById(R.id.target_name);
            hitCountText = itemView.findViewById(R.id.target_hits);
            checkBox = itemView.findViewById(R.id.target_checkbox);
            thumbnail = itemView.findViewById(R.id.target_thumbnail);
            blurView = itemView.findViewById(R.id.item_blur_wrapper);

            // INITIALIZE THE FROSTED GLASS EFFECT FOR THIS SPECIFIC ROW
            float blurRadius = 15f;
            ViewGroup rootView = activity.findViewById(android.R.id.content);
            Drawable windowBackground = activity.getWindow().getDecorView().getBackground();

            if (windowBackground != null) {
                blurView.setupWith(rootView, new RenderScriptBlur(itemView.getContext()))
                        .setFrameClearDrawable(windowBackground)
                        .setBlurRadius(blurRadius);
            }
        }
    }
}