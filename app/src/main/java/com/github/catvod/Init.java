package com.github.catvod;

import android.content.Context;

import com.github.tvbox.osc.util.AppContextHolder;

public class Init {

    private static Context context;

    public static void set(Context ctx) {
        context = ctx == null ? null : ctx.getApplicationContext();
    }

    public static Context context() {
        if (context != null) return context;
        return AppContextHolder.context();
    }

    private Init() {
    }
}
