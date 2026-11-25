package com.github.tvbox.osc.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.github.tvbox.osc.base.App;

// AVBox 适配：对应 webhtv 的 Notify.show（主线程 Toast）。
public class Notify {

    public static void show(String msg) {
        if (msg == null || msg.isEmpty()) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            Context context = App.getInstance();
            if (context != null) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
        });
    }
}
