package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.DialogInterface;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import com.github.tvbox.osc.util.AppManager;

/**
 * Cloudflare 验证弹窗（从 muse-build 移植，P0-1）。
 * 隐藏嗅探 WebView 遇到 Cloudflare 验证时，把 WebView 装进这个对话框弹出来，让用户手动过验证；
 * 关掉对话框就停掉嗅探。muse 原版在 com.muse.android.tv.ui.dialog.WebDialog；
 * App.activity() 换成 AppManager.currentActivity()。
 * 1.19：Material 弹层在 AppTheme（AppCompat，非 MaterialComponents）下弹不出，改用 AppCompat 的 AlertDialog.Builder。
 */
public class WebDialog {

    private final AlertDialog dialog;

    public static WebDialog create(View view) {
        return new WebDialog(view);
    }

    private WebDialog(View view) {
        Activity activity = currentActivity();
        this.dialog = new AlertDialog.Builder(activity).setView(view).create();
        this.dialog.setOnDismissListener((DialogInterface.OnDismissListener) view);
    }

    public WebDialog show() {
        initDialog();
        return this;
    }

    public void dismiss() {
        dialog.setOnDismissListener(null);
        dialog.dismiss();
    }

    private void initDialog() {
        dialog.show();
    }

    private static Activity currentActivity() {
        try {
            return AppManager.getInstance().currentActivity();
        } catch (Exception ignored) {
            return null;
        }
    }
}
