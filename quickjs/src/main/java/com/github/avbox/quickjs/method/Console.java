package com.github.avbox.quickjs.method;

import android.util.Log;

import com.whl.quickjs.wrapper.QuickJSContext;

/**
 * AVBox 移植垫片：QuickJS 控制台输出（AVBOX 用 Logger，这里落 Logcat）。
 */
public class Console implements QuickJSContext.Console {
    private static final String TAG = "quickjs";

    @Override
    public void log(String info) {
        Log.d(TAG, info == null ? "" : info);
    }

    @Override
    public void info(String info) {
        Log.i(TAG, info == null ? "" : info);
    }

    @Override
    public void warn(String info) {
        Log.w(TAG, info == null ? "" : info);
    }

    @Override
    public void error(String info) {
        Log.e(TAG, info == null ? "" : info);
    }
}