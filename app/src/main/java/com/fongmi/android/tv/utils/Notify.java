package com.fongmi.android.tv.utils;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import com.fongmi.android.tv.App;

public class Notify {

    public static void show(String text) {
        if (TextUtils.isEmpty(text) || App.get() == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(App.get(), text, Toast.LENGTH_SHORT).show();
        } else {
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(App.get(), text, Toast.LENGTH_SHORT).show());
        }
    }

    public static void show(int resId) {
        if (resId != 0) show(ResUtil.getString(resId));
    }
}
