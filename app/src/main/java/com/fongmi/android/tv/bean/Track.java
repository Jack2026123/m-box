package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import java.util.Collections;
import java.util.List;

public class Track {

    private int id;
    private int type;
    private String key;
    private String name;
    private String format;
    private boolean selected;

    public Track(int type, String name, String format) {
        this.type = type;
        this.name = name;
        this.format = format;
    }



    public static List<Track> find(String key) {
        return Collections.emptyList();
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public Track key(String key) {
        setKey(key);
        return this;
    }

    public Track toggle() {
        setSelected(!isSelected());
        return this;
    }

}
