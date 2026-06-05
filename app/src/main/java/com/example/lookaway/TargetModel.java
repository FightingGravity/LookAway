package com.example.lookaway;

import java.io.Serializable;

public class TargetModel implements Serializable {
    private String id;
    private String name;
    private String imagePath;
    private int hitCount30Days;
    private boolean isSelected;

    public TargetModel(String id, String name, String imagePath) {
        this.id = id;
        this.name = name;
        this.imagePath = imagePath;
        this.hitCount30Days = 0;
        this.isSelected = false;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getImagePath() { return imagePath; }
    public int getHitCount30Days() { return hitCount30Days; }
    public void incrementHitCount() { this.hitCount30Days++; }
    public boolean isSelected() { return isSelected; }
    public void setSelected(boolean selected) { isSelected = selected; }
}