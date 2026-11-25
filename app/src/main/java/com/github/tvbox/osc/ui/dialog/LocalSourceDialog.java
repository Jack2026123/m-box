package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.LocalSource;
import com.github.tvbox.osc.api.LocalSourceHook;
import com.github.tvbox.osc.ui.theme.ViewTheme;
import com.github.tvbox.osc.ui.page.AppBootstrap;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.util.Notify;
import com.github.tvbox.osc.util.PermissionHelper;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.utils.Prefers;
import android.widget.Button;
import android.widget.TextView;
import com.google.gson.JsonObject;

import java.util.ArrayList;

public class LocalSourceDialog {

    public static void show(Context context, Runnable callback) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        CheckBox enable = new CheckBox(context);
        enable.setText(R.string.local_source_enable);
        enable.setChecked(Prefers.getBoolean("local_source_enabled", false));
        ViewTheme.INSTANCE.tintCompound(context, enable);
        root.addView(enable);

        TextView dirsText = new TextView(context);
        dirsText.setTextSize(ViewTheme.BODY_SP);
        dirsText.setTextColor(ViewTheme.INSTANCE.onSurfaceArgb(context));
        root.addView(dirsText);

        CheckBox auto = new CheckBox(context);
        auto.setText(R.string.local_source_auto);
        auto.setChecked(Prefers.getBoolean("local_source_auto", true));
        ViewTheme.INSTANCE.tintCompound(context, auto);
        root.addView(auto);

        Button settings = new Button(context);
        settings.setText(R.string.local_source_settings_title);
        ViewTheme.INSTANCE.stylePlainTextButton(context, settings);
        settings.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(settings);

        Button fileList = new Button(context);
        fileList.setText(R.string.local_source_list_title);
        ViewTheme.INSTANCE.stylePlainTextButton(context, fileList);
        fileList.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(fileList);

        TextView summaryText = new TextView(context);
        summaryText.setTextSize(ViewTheme.BODY_SP);
        summaryText.setTextColor(ViewTheme.INSTANCE.onSurfaceVariantArgb(context));
        root.addView(summaryText);

        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            ArrayList<String> dirs = LocalSource.stringList(LocalSource.loadSettings(), "scan_dirs");
            ArrayList<String> enabled = LocalSource.stringList(LocalSource.loadSettings(), "scan_enabled_dirs");
            StringBuilder sb = new StringBuilder(context.getString(R.string.local_source_settings_dirs));
            if (dirs.isEmpty()) {
                sb.append("\n").append(context.getString(R.string.local_source_settings_dirs_empty));
            } else {
                for (String dir : dirs) sb.append("\n").append(LocalSource.isDirEnabled(dir, enabled) ? "☑ " : "☐ ").append(dir);
            }
            dirsText.setText(sb.toString());
            StringBuilder sb2 = new StringBuilder(context.getString(R.string.local_source_status, LocalSource.summary()));
            String file = Prefers.getString("local_source_file");
            if (TextUtils.isEmpty(file)) file = LocalSource.outputPath(LocalSource.loadSettings());
            if (!TextUtils.isEmpty(file)) sb2.append("\n").append(context.getString(R.string.local_source_output, file));
            summaryText.setText(sb2.toString());
        };
        refresh[0].run();
        enable.setOnCheckedChangeListener((button, checked) -> refresh[0].run());
        Dialog[] holder = new Dialog[1];
        settings.setOnClickListener(view -> LocalSourceSettingsDialog.show(context, refresh[0],
                () -> {
                    if (holder[0] != null) holder[0].dismiss();
                }));

        fileList.setOnClickListener(view -> LocalSourceListDialog.show(context, refresh[0]));

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        holder[0] = LightDialog.create(context, context.getString(R.string.local_source_title), scroll,
                context.getString(R.string.local_source_settings_save_load), view -> {
                    Prefers.put("local_source_enabled", enable.isChecked());
                    Prefers.put("local_source_auto", auto.isChecked());
                    if (LocalSource.stringList(LocalSource.loadSettings(), "scan_dirs").isEmpty()) {
                        Notify.show(context.getString(R.string.local_source_dir_required));
                        return;
                    }
                    holder[0].dismiss();
                    scanAndLoad(context, callback);
                },
                context.getString(R.string.dialog_negative), view -> holder[0].dismiss());
        holder[0].show();
    }

    private static void runCallbackOnMain(Runnable callback) {
        if (callback == null) return;
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    callback.run();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public static void reloadOnly(Context context, Runnable callback) {
        try {
            ApiConfig.get().loadConfig(false, new ApiConfig.LoadConfigCallback() {
                @Override
                public void success() {
                    AppBootstrap.INSTANCE.notifyConfigReloaded();
                    runCallbackOnMain(callback);
                }

                @Override
                public void error(String msg) {
                    runCallbackOnMain(callback);
                }

                @Override
                public void notice(String msg) {
                }
            }, null);
        } catch (Throwable ignored) {
            runCallbackOnMain(callback);
        }
    }

    public static void scanAndLoad(Context context, Runnable callback) {
        scanAndLoad(context, callback, true);
    }

    private static void scanAndLoad(Context context, Runnable callback, boolean allowAsk) {
        if (!PermissionHelper.isStorageGranted(context)) {
            Activity activity = context instanceof Activity ? (Activity) context : null;
            if (activity != null && allowAsk) {
                PermissionHelper.requestStorage(activity, (granted, denied) -> {
                    if (PermissionHelper.isStorageGranted(context)) {
                        scanAndLoad(context, callback, false);
                    } else {
                        denyNoPermission(context, callback);
                    }
                });
                return;
            }
            denyNoPermission(context, callback);
            return;
        }
        Notify.show(context.getString(R.string.local_source_scanning));
        new Thread(() -> {
            JsonObject generated = LocalSource.buildCached(true);
            String path = LocalSource.writeGenerated(generated);
            if (path != null) Prefers.put("local_source_file", path);
            if (path != null) LocalSourceHook.ensureLocalSubscription(path);
            String summary = Prefers.getString("local_source_summary");
            new Handler(Looper.getMainLooper()).post(() -> {
                Notify.show(context.getString(R.string.local_source_done, summary));
                try {
                    ApiConfig.get().loadConfig(false, new ApiConfig.LoadConfigCallback() {
                        @Override
                        public void success() {
                            AppBootstrap.INSTANCE.notifyConfigReloaded();
                            runCallbackOnMain(callback);
                        }

                        @Override
                        public void error(String msg) {
                            runCallbackOnMain(callback);
                        }

                        @Override
                        public void notice(String msg) {
                        }
                    }, null);
                } catch (Throwable ignored) {
                    runCallbackOnMain(callback);
                }
            });
        }, "local-source-scan").start();
    }

    private static void denyNoPermission(Context context, Runnable callback) {
        DebugLogStore.add("LocalSource", "未授予存储权限，无法扫描（请到设置里打开“所有文件访问权限”）");
        new Handler(Looper.getMainLooper()).post(() -> {
            Notify.show(context.getString(R.string.toast_local_source_unreadable));
            runCallbackOnMain(callback);
        });
    }
}
