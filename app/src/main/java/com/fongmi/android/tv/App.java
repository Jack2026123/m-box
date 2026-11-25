package com.fongmi.android.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;

public class App {

    private static Context context;
    private static final Gson gson = new Gson();
    private static final long time = System.currentTimeMillis();
    private static final Handler handler = new Handler(Looper.getMainLooper());

    public static void init(Context ctx) {
        if (ctx != null) context = ctx.getApplicationContext();
    }

    public static Context get() {
        return context;
    }

    public static Gson gson() {
        return gson;
    }

    public static long time() {
        return time;
    }

    public static void post(Runnable runnable) {
        handler.post(runnable);
    }

    public static void post(Runnable runnable, long delayMillis) {
        handler.postDelayed(runnable, delayMillis);
    }

    public static void removeCallbacks(Runnable runnable) {
        handler.removeCallbacks(runnable);
    }
}
