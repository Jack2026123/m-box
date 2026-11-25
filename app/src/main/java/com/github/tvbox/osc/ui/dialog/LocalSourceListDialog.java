package com.github.tvbox.osc.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import androidx.core.content.FileProvider;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.LocalSource;
import com.github.tvbox.osc.api.LocalSourceSingle;
import com.github.tvbox.osc.ui.theme.ViewTheme;
import com.github.tvbox.osc.util.Notify;
import android.widget.Button;
import android.widget.TextView;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;

// Source file list for the local-source folder: every scanned file with its
// folder tab, enable switch and generation status. Jar rows can be promoted
// to the main (spider) jar; rule sources from the json folder can be bound
// to a secondary jar, which the generator then writes as that site's own
// "jar" field.
public class LocalSourceListDialog {

    private final Context context;
    private final Runnable onClose;
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView headerText;
    private LinearLayout listBox;
    private LinearLayout tabBox;
    private final ArrayList<String> tabKeys = new ArrayList<>();
    private final ArrayList<Button> tabButtons = new ArrayList<>();
    private String tab = "all";
    private ArrayList<LocalSource.FileEntry> entries = new ArrayList<>();
    private ArrayList<String> jarPaths = new ArrayList<>();
    private ArrayList<String> emptyFolders = new ArrayList<>();
    private int shown = 120;
    private boolean closed;

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private LocalSourceListDialog(Context context, Runnable onClose) {
        this.context = context;
        this.onClose = onClose;
    }

    public static void show(Context context, Runnable onClose) {
        new LocalSourceListDialog(context, onClose).build();
    }

    private TextView note(String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(ViewTheme.SECONDARY_SP);
        view.setTextColor(ViewTheme.INSTANCE.onSurfaceVariantArgb(context));
        return view;
    }

    private void build() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(4, 0, 4, 0);

        headerText = new TextView(context);
        headerText.setTextSize(ViewTheme.BODY_SP);
        headerText.setTextColor(ViewTheme.INSTANCE.onSurfaceArgb(context));
        headerText.setText(R.string.local_source_list_loading);
        root.addView(headerText);

        tabBox = new LinearLayout(context);
        tabBox.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView tabScroll = new HorizontalScrollView(context);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.addView(tabBox);
        root.addView(tabScroll);
        buildTabs();

        listBox = new LinearLayout(context);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Dialog[] holder = new Dialog[1];
        holder[0] = LightDialog.create(context, context.getString(R.string.local_source_list_title), scroll,
                context.getString(R.string.dialog_negative), view -> holder[0].dismiss(), null, null);
        holder[0].setOnDismissListener(dialog -> {
            closed = true;
            if (onClose != null) onClose.run();
        });
        holder[0].show();
        load();
    }

    // Tabs are the scan's top-level folders, not extensions: "all" first,
    // then every folder holding at least one collected file, fewest files
    // first (List.sort is stable, so ties keep first-seen order). The row is
    // a single horizontally scrollable line — buttons never wrap or squeeze.
    // Files directly in a scan root have no folder and only show under "all".
    private void buildTabs() {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (LocalSource.FileEntry entry : entries) {
            if (TextUtils.isEmpty(entry.folder)) continue;
            Integer count = counts.get(entry.folder);
            counts.put(entry.folder, count == null ? 1 : count + 1);
        }
        ArrayList<String> folders = new ArrayList<>(counts.keySet());
        folders.sort((a, b) -> Integer.compare(counts.get(a), counts.get(b)));
        tabKeys.clear();
        tabKeys.add("all");
        tabKeys.addAll(folders);
        ArrayList<String> empty = new ArrayList<>(emptyFolders);
        Collections.sort(empty);
        for (String name : empty) {
            if (!tabKeys.contains(name)) tabKeys.add(name);
        }
        if (!tabKeys.contains(tab)) tab = "all";
        tabBox.removeAllViews();
        tabButtons.clear();
        for (final String key : tabKeys) {
            Button button = new Button(context);
            button.setText("all".equals(key) ? context.getString(R.string.local_source_list_tab_all) : key);
            button.setSingleLine(true);
            button.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            button.setOnClickListener(view -> {
                tab = key;
                shown = 120;
                refreshTabs();
                fillList();
            });
            tabButtons.add(button);
            tabBox.addView(button);
        }
        refreshTabs();
    }

    private void refreshTabs() {
        for (int i = 0; i < tabKeys.size(); i++) {
            boolean selected = tabKeys.get(i).equals(tab);
            tabButtons.get(i).setEnabled(!selected);
            ViewTheme.INSTANCE.styleTabButton(context, tabButtons.get(i), selected);
        }
    }

    private void load() {
        new Thread(() -> {
            ArrayList<LocalSource.FileEntry> scanned = LocalSource.collectFiles();
            ArrayList<String> jars = new ArrayList<>();
            for (LocalSource.FileEntry entry : scanned) {
                if ("jar".equals(entry.kind)) jars.add(entry.path);
            }
            ArrayList<String> empty = new ArrayList<>();
            JsonObject cfg = LocalSource.loadSettings();
            ArrayList<String> blocked = LocalSource.stringList(cfg, "blocked_dirs");
            for (String dir : LocalSource.enabledDirs(cfg)) {
                File root = new File(dir);
                File[] children = root.isDirectory() ? root.listFiles() : null;
                if (children == null) continue;
                for (File child : children) {
                    if (child.isDirectory() && !child.getName().startsWith(".") && !blocked.contains(child.getName()) && !empty.contains(child.getName())) {
                        empty.add(child.getName());
                    }
                }
            }
            main.post(() -> {
                if (closed) return;
                entries = scanned;
                jarPaths = jars;
                emptyFolders = empty;
                int generated = 0;
                for (LocalSource.FileEntry entry : entries) {
                    if (entry.generated) generated++;
                }
                headerText.setText(context.getString(R.string.local_source_list_count, generated, entries.size()));
                buildTabs();
                fillList();
            });
        }, "local-source-list").start();
    }

    private boolean inTab(LocalSource.FileEntry entry) {
        return "all".equals(tab) || tab.equals(entry.folder);
    }

    private void fillList() {
        listBox.removeAllViews();
        ArrayList<LocalSource.FileEntry> filtered = new ArrayList<>();
        for (LocalSource.FileEntry entry : entries) {
            if (inTab(entry)) filtered.add(entry);
        }
        if (filtered.isEmpty()) {
            listBox.addView(note(context.getString(entries.isEmpty()
                    ? R.string.local_source_list_empty : R.string.local_source_list_not_generated)));
            return;
        }
        int min = Math.min(shown, filtered.size());
        for (int i = 0; i < min; i++) {
            listBox.addView(row(filtered.get(i)));
        }
        int left = filtered.size() - min;
        if (left > 0) {
            Button more = new Button(context);
            more.setText(context.getString(R.string.local_source_list_more, left));
            ViewTheme.INSTANCE.styleTonalButton(context, more);
            more.setOnClickListener(view -> {
                shown += 120;
                fillList();
            });
            listBox.addView(more);
        }
    }

    private String statusText(LocalSource.FileEntry entry) {
        ArrayList<String> parts = new ArrayList<>();
        if ("jar".equals(entry.kind)) {
            if (entry.excluded) parts.add(context.getString(R.string.local_source_list_disabled));
            if (entry.jarRole == 1) parts.add(context.getString(R.string.local_source_list_main_manual));
            else if (entry.jarRole == 2) parts.add(context.getString(R.string.local_source_list_main_config));
            else if (entry.jarRole == 3) parts.add(context.getString(R.string.local_source_list_main_auto));
            if (entry.boundBy > 0) parts.add(context.getString(R.string.local_source_list_secondary, entry.boundBy));
            if (parts.isEmpty()) parts.add(context.getString(R.string.local_source_list_unused));
            return TextUtils.join(" · ", parts);
        }
        if (entry.excluded) {
            parts.add(context.getString(R.string.local_source_list_disabled));
        } else if (entry.live) {
            parts.add(context.getString(R.string.local_source_list_live));
        } else if (entry.generated) {
            parts.add(context.getString(R.string.local_source_list_generated));
        } else {
            parts.add(context.getString(R.string.local_source_list_not_generated));
        }
        if ("json".equals(entry.kind) && entry.rule == 1) parts.add("XBPQ");
        if ("json".equals(entry.kind) && entry.rule == 2) parts.add("XYQ");
        if (!TextUtils.isEmpty(entry.boundJar)) {
            parts.add(context.getString(R.string.local_source_list_bound_tag, new File(entry.boundJar).getName()));
        }
        return TextUtils.join(" · ", parts);
    }

    private View row(LocalSource.FileEntry entry) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), dp(8), dp(4), dp(8));
        row.setOnLongClickListener(view -> {
            showManageMenu(entry);
            return true;
        });

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsParams.rightMargin = dp(8);
        texts.setLayoutParams(textsParams);
        texts.setOnLongClickListener(view -> {
            showManageMenu(entry);
            return true;
        });
        CheckBox box = new CheckBox(context);
        box.setText(entry.name);
        box.setSingleLine(true);
        box.setChecked(!entry.excluded);
        ViewTheme.INSTANCE.tintCompound(context, box);
        box.setOnCheckedChangeListener((button, checked) -> {
            entry.excluded = !checked;
            toggleExcluded(entry.name, !checked);
            fillList();
        });
        TextView status = new TextView(context);
        status.setTextSize(ViewTheme.SECONDARY_SP);
        status.setTextColor(ViewTheme.INSTANCE.onSurfaceVariantArgb(context));
        status.setText(statusText(entry));
        texts.addView(box);
        texts.addView(status);
        row.addView(texts);

        if ("jar".equals(entry.kind)) {
            Button action = new Button(context);
            if (entry.jarRole > 0) {
                action.setText(R.string.local_source_list_is_main);
                ViewTheme.INSTANCE.styleMutedButton(context, action);
                action.setEnabled(false);
            } else {
                action.setText(R.string.local_source_list_set_main);
                ViewTheme.INSTANCE.styleTonalButton(context, action);
                action.setOnClickListener(view -> new Thread(() -> {
                    LocalSource.applySpiderJar(entry.path);
                    load();
                }, "local-source-main-jar").start());
            }
            row.addView(action);
        } else if (bindable(entry)) {
            Button action = new Button(context);
            action.setText(TextUtils.isEmpty(entry.boundJar)
                    ? context.getString(R.string.local_source_list_bind)
                    : new File(entry.boundJar).getName());
            ViewTheme.INSTANCE.styleTonalButton(context, action);
            action.setOnClickListener(view -> pickSecondaryJar(entry));
            row.addView(action);
        }
        return row;
    }

    private Button menuButton(LinearLayout box, int resId, View.OnClickListener onClick) {
        Button button = new Button(context);
        button.setText(resId);
        ViewTheme.INSTANCE.styleTonalButton(context, button);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        button.setLayoutParams(params);
        button.setOnClickListener(onClick);
        box.addView(button);
        return button;
    }

    private void showManageMenu(LocalSource.FileEntry entry) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(box, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Dialog[] holder = new Dialog[1];
        menuButton(box, R.string.local_source_manage_rename, view -> {
            holder[0].dismiss();
            renameFile(entry);
        });
        menuButton(box, R.string.local_source_manage_move, view -> {
            holder[0].dismiss();
            moveToCategory(entry);
        });
        menuButton(box, R.string.local_source_manage_add_category, view -> {
            holder[0].dismiss();
            addCategory(entry);
        });
        menuButton(box, R.string.local_source_manage_delete_category, view -> {
            holder[0].dismiss();
            deleteCategory(entry);
        });
        menuButton(box, R.string.local_source_manage_up, view -> {
            holder[0].dismiss();
            upLevel();
        });
        menuButton(box, R.string.local_source_manage_rename_category, view -> {
            holder[0].dismiss();
            renameCategory(entry);
        });
        menuButton(box, R.string.local_source_manage_share, view -> {
            holder[0].dismiss();
            shareFile(entry);
        });
        menuButton(box, R.string.local_source_manage_delete, view -> {
            holder[0].dismiss();
            deleteFile(entry);
        });
        holder[0] = LightDialog.create(context, entry.name, scroll,
                context.getString(R.string.dialog_negative), view -> holder[0].dismiss(), null, null);
        holder[0].show();
    }

    private interface InputConfirm {
        boolean onConfirm(EditText input);
    }

    private void inputDialog(String title, String initial, InputConfirm onConfirm) {
        EditText input = new EditText(context);
        input.setText(initial);
        input.setSingleLine(true);
        ViewTheme.INSTANCE.styleInput(context, input);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(input);
        Dialog[] holder = new Dialog[1];
        holder[0] = LightDialog.create(context, title, box,
                context.getString(R.string.dialog_positive), view -> {
                    if (onConfirm.onConfirm(input)) holder[0].dismiss();
                },
                context.getString(R.string.dialog_negative), view -> holder[0].dismiss());
        holder[0].show();
    }

    private void confirmDialog(String title, String message, String positive, Runnable onConfirm) {
        TextView text = new TextView(context);
        text.setText(message);
        text.setTextSize(ViewTheme.BODY_SP);
        text.setTextColor(ViewTheme.INSTANCE.onSurfaceArgb(context));
        Dialog[] holder = new Dialog[1];
        holder[0] = LightDialog.create(context, title, text,
                positive, view -> {
                    holder[0].dismiss();
                    onConfirm.run();
                },
                context.getString(R.string.dialog_negative), view -> holder[0].dismiss());
        holder[0].show();
    }

    private File uniqueName(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return candidate;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int n = 1;
        while (candidate.exists()) {
            n++;
            candidate = new File(dir, base + "_" + n + ext);
        }
        return candidate;
    }

    private String withOriginalExt(String input, String original) {
        String name = input == null ? "" : input.trim();
        if (name.isEmpty()) return original;
        int dot = name.lastIndexOf('.');
        int origDot = original.lastIndexOf('.');
        if (dot <= 0 && origDot > 0) return name + original.substring(origDot);
        return name;
    }

    private void afterFileChange(File oldFile, File newFile) {
        new Thread(() -> {
            boolean ok;
            if (oldFile != null && newFile != null) ok = LocalSourceSingle.moveFileEntry(oldFile, newFile);
            else if (newFile != null) ok = LocalSourceSingle.registerFile(newFile);
            else if (oldFile != null) ok = LocalSourceSingle.removeFile(oldFile);
            else ok = false;
            main.post(() -> {
                if (ok) LocalSourceDialog.reloadOnly(context, () -> load());
                else LocalSourceDialog.scanAndLoad(context, () -> load());
            });
        }, "local-source-single").start();
    }

    private void renameFile(LocalSource.FileEntry entry) {
        File oldFile = new File(entry.path);
        if (!oldFile.isFile()) {
            Notify.show(context.getString(R.string.toast_local_source_missing));
            return;
        }
        inputDialog(context.getString(R.string.local_source_manage_rename), entry.name, input -> {
            String name = withOriginalExt(input.getText() == null ? "" : input.getText().toString(), entry.name);
            if (name.equals(entry.name)) return true;
            File target = uniqueName(oldFile.getParentFile(), name);
            boolean renamed = oldFile.renameTo(target);
            if (!renamed) renamed = LocalSource.moveFile(oldFile, target);
            if (!renamed || !target.exists()) {
                Notify.show(context.getString(R.string.local_source_manage_move_failed, ""));
                load();
                return true;
            }
            Notify.show(context.getString(R.string.local_source_manage_moved, target.getAbsolutePath()));
            afterFileChange(oldFile, target);
            return true;
        });
    }

    private void moveToCategory(LocalSource.FileEntry entry) {
        File oldFile = new File(entry.path);
        if (!oldFile.isFile()) {
            Notify.show(context.getString(R.string.toast_local_source_missing));
            return;
        }
        ArrayList<File> targets = LocalSourceSingle.categoryDirs();
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        if (targets.isEmpty()) box.addView(note(context.getString(R.string.local_source_manage_pick_category_empty)));
        RadioGroup group = new RadioGroup(context);
        group.setOrientation(LinearLayout.VERTICAL);
        box.addView(group);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(box, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Dialog[] holder = new Dialog[1];
        ArrayList<String> seenNames = new ArrayList<>();
        for (File target : targets) {
            String label = target.getName();
            if (seenNames.contains(label)) label = label + "（" + target.getParentFile().getName() + "）";
            seenNames.add(target.getName());
            RadioButton item = new RadioButton(context);
            item.setText(label);
            item.setSingleLine(true);
            ViewTheme.INSTANCE.tintCompound(context, item);
            item.setLayoutParams(new RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            File picked = target;
            item.setOnClickListener(view -> {
                holder[0].dismiss();
                File dest = uniqueName(picked, oldFile.getName());
                boolean moved = oldFile.renameTo(dest);
                if (!moved) moved = LocalSource.moveFile(oldFile, dest);
                if (!moved || !dest.exists()) {
                    Notify.show(context.getString(R.string.local_source_manage_move_failed, ""));
                    load();
                    return;
                }
                Notify.show(context.getString(R.string.local_source_manage_moved, dest.getAbsolutePath()));
                afterFileChange(oldFile, dest);
            });
            group.addView(item);
        }
        holder[0] = LightDialog.create(context, context.getString(R.string.local_source_manage_pick_category), scroll,
                context.getString(R.string.dialog_negative), view -> holder[0].dismiss(), null, null);
        holder[0].show();
    }

    private void addCategory(LocalSource.FileEntry entry) {
        File parent;
        if (!TextUtils.isEmpty(entry.folder)) {
            parent = LocalSourceSingle.resolveCategoryDir(entry.folder);
        } else {
            ArrayList<String> enabled = LocalSource.enabledDirs(LocalSource.loadSettings());
            parent = enabled.isEmpty() ? null : new File(enabled.get(0));
        }
        if (parent == null || !parent.isDirectory()) {
            Notify.show(context.getString(R.string.local_source_dir_required));
            return;
        }
        File base = parent;
        inputDialog(context.getString(R.string.local_source_manage_add_category), "", input -> {
            String name = input.getText() == null ? "" : input.getText().toString().trim().replace("/", "_").replace("\\", "_");
            if (name.isEmpty()) return false;
            File created = new File(base, name);
            if (created.exists() && created.isDirectory()) {
                Notify.show(context.getString(R.string.local_source_manage_category_exists));
                return false;
            }
            if (!created.mkdir()) {
                Notify.show(context.getString(R.string.open_source_folder_create_failed, created.getAbsolutePath()));
                return false;
            }
            if (!LocalSourceSingle.isUnderEnabledDir(created, LocalSource.loadSettings())) {
                LocalSourceSingle.addScanDir(created.getAbsolutePath());
            }
            Notify.show(context.getString(R.string.local_source_manage_category_added, created.getAbsolutePath()));
            load();
            return true;
        });
    }

    private int countFiles(File dir) {
        int count = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File child : children) {
            if (child.isDirectory()) count += countFiles(child);
            else count++;
        }
        return count;
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        file.delete();
    }

    private void removeScanDirIfPresent(File dir) {
        String canon = LocalSource.canonPath(dir.getAbsolutePath());
        JsonObject cfg = LocalSource.loadSettings();
        ArrayList<String> dirs = LocalSource.stringList(cfg, "scan_dirs");
        ArrayList<String> enabled = LocalSource.stringList(cfg, "scan_enabled_dirs");
        boolean changed = false;
        ArrayList<String> keptDirs = new ArrayList<>();
        for (String path : dirs) {
            if (LocalSource.canonPath(path).equals(canon)) changed = true;
            else keptDirs.add(path);
        }
        ArrayList<String> keptEnabled = new ArrayList<>();
        for (String path : enabled) {
            if (LocalSource.canonPath(path).equals(canon)) changed = true;
            else keptEnabled.add(path);
        }
        if (!changed) return;
        cfg.add("scan_dirs", LocalSource.toJsonArray(keptDirs));
        cfg.add("scan_enabled_dirs", LocalSource.toJsonArray(LocalSource.intersect(keptDirs, keptEnabled)));
        LocalSource.saveSettings(cfg);
    }

    private void deleteCategory(LocalSource.FileEntry entry) {
        if (TextUtils.isEmpty(entry.folder)) {
            Notify.show(context.getString(R.string.local_source_manage_no_category));
            return;
        }
        File dir = LocalSourceSingle.resolveCategoryDir(entry.folder);
        if (dir == null || !dir.isDirectory()) {
            Notify.show(context.getString(R.string.local_source_manage_no_category));
            return;
        }
        int count = countFiles(dir);
        confirmDialog(context.getString(R.string.local_source_manage_delete_category),
                context.getString(R.string.local_source_manage_delete_category_confirm, entry.folder, count),
                context.getString(R.string.common_delete), () -> {
                    deleteRecursively(dir);
                    removeScanDirIfPresent(dir);
                    Notify.show(context.getString(R.string.local_source_manage_category_deleted, entry.folder));
                    LocalSourceDialog.scanAndLoad(context, () -> load());
                });
    }

    private void upLevel() {
        if ("all".equals(tab)) {
            Notify.show(context.getString(R.string.local_source_manage_top_level));
            return;
        }
        tab = "all";
        shown = 120;
        refreshTabs();
        fillList();
    }

    private boolean copyDir(File from, File to) {
        if (from.isDirectory()) {
            if (!to.exists() && !to.mkdirs()) return false;
            File[] children = from.listFiles();
            if (children == null) return true;
            for (File child : children) {
                if (!copyDir(child, new File(to, child.getName()))) return false;
            }
            return true;
        }
        try {
            FileInputStream in = new FileInputStream(from);
            try {
                FileOutputStream out = new FileOutputStream(to);
                try {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                    out.flush();
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            return to.length() == from.length();
        } catch (Throwable th) {
            return false;
        }
    }

    private void renameCategory(LocalSource.FileEntry entry) {
        if (TextUtils.isEmpty(entry.folder)) {
            Notify.show(context.getString(R.string.local_source_manage_no_category));
            return;
        }
        File dir = LocalSourceSingle.resolveCategoryDir(entry.folder);
        if (dir == null || !dir.isDirectory()) {
            Notify.show(context.getString(R.string.local_source_manage_no_category));
            return;
        }
        File oldDir = dir;
        inputDialog(context.getString(R.string.local_source_manage_rename_category), entry.folder, input -> {
            String name = input.getText() == null ? "" : input.getText().toString().trim().replace("/", "_").replace("\\", "_");
            if (name.isEmpty() || name.equals(entry.folder)) return false;
            File target = new File(oldDir.getParentFile(), name);
            if (target.exists()) {
                Notify.show(context.getString(R.string.local_source_manage_category_exists));
                return false;
            }
            boolean renamed = oldDir.renameTo(target);
            if (!renamed) {
                renamed = copyDir(oldDir, target) && target.exists();
                if (renamed) deleteRecursively(oldDir);
            }
            if (!renamed || !target.isDirectory()) {
                Notify.show(context.getString(R.string.local_source_manage_move_failed, ""));
                load();
                return true;
            }
            JsonObject cfg = LocalSource.loadSettings();
            ArrayList<String> dirs = LocalSource.stringList(cfg, "scan_dirs");
            ArrayList<String> enabled = LocalSource.stringList(cfg, "scan_enabled_dirs");
            String oldCanon = LocalSource.canonPath(oldDir.getAbsolutePath());
            boolean changed = false;
            for (int i = 0; i < dirs.size(); i++) {
                if (LocalSource.canonPath(dirs.get(i)).equals(oldCanon)) {
                    dirs.set(i, target.getAbsolutePath());
                    changed = true;
                }
            }
            for (int i = 0; i < enabled.size(); i++) {
                if (LocalSource.canonPath(enabled.get(i)).equals(oldCanon)) {
                    enabled.set(i, target.getAbsolutePath());
                    changed = true;
                }
            }
            if (changed) {
                cfg.add("scan_dirs", LocalSource.toJsonArray(dirs));
                cfg.add("scan_enabled_dirs", LocalSource.toJsonArray(LocalSource.intersect(dirs, enabled)));
                LocalSource.saveSettings(cfg);
            }
            Notify.show(context.getString(R.string.local_source_manage_category_renamed, target.getAbsolutePath()));
            LocalSourceDialog.scanAndLoad(context, () -> load());
            return true;
        });
    }

    private void shareFile(LocalSource.FileEntry entry) {
        File file = new File(entry.path);
        if (!file.isFile()) {
            Notify.show(context.getString(R.string.toast_local_source_missing));
            return;
        }
        try {
            android.net.Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
            String lower = file.getName().toLowerCase();
            String type = "application/octet-stream";
            if (lower.endsWith(".py")) type = "text/x-python";
            else if (lower.endsWith(".js")) type = "application/javascript";
            else if (lower.endsWith(".json")) type = "application/json";
            else if (lower.endsWith(".txt")) type = "text/plain";
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(type);
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(share, context.getString(R.string.local_source_manage_share_title)));
        } catch (Throwable th) {
            Notify.show(context.getString(R.string.local_source_manage_share_failed, th.getMessage() == null ? "" : th.getMessage()));
        }
    }

    private void deleteFile(LocalSource.FileEntry entry) {
        File file = new File(entry.path);
        if (!file.isFile()) {
            Notify.show(context.getString(R.string.toast_local_source_missing));
            return;
        }
        confirmDialog(context.getString(R.string.local_source_manage_delete),
                context.getString(R.string.local_source_manage_delete_confirm, entry.name),
                context.getString(R.string.common_delete), () -> {
                    if (file.delete()) {
                        Notify.show(context.getString(R.string.local_source_manage_deleted, entry.name));
                        afterFileChange(file, null);
                    } else {
                        Notify.show(context.getString(R.string.local_source_manage_move_failed, ""));
                        load();
                    }
                });
    }

    // Secondary-jar binding only makes sense for rule sources from the json
    // folder (they generate csp_XBPQ/csp_XYQHiker sites). py/cat sources and
    // everything else get no bind button: they never consume a site jar.
    private boolean bindable(LocalSource.FileEntry entry) {
        return "json".equals(entry.kind) && entry.rule > 0 && "json".equalsIgnoreCase(entry.folder);
    }

    private void toggleExcluded(String name, boolean excluded) {
        JsonObject current = LocalSource.loadSettings();
        ArrayList<String> list = new ArrayList<>(LocalSource.splitUserText(TextUtils.join(",", LocalSource.stringList(current, "excluded_files"))));
        if (excluded) {
            if (!list.contains(name)) list.add(name);
        } else {
            list.remove(name);
        }
        LocalSource.updateSetting(LocalSource.toJsonArray(new ArrayList<>(LocalSource.splitUserText(TextUtils.join(",", list)))), "excluded_files");
    }

    private void pickSecondaryJar(LocalSource.FileEntry entry) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        if (jarPaths.isEmpty()) box.addView(note(context.getString(R.string.local_source_list_jars_empty)));
        RadioGroup group = new RadioGroup(context);
        group.setOrientation(LinearLayout.VERTICAL);
        box.addView(group);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(box, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Dialog[] holder = new Dialog[1];
        RadioButton none = new RadioButton(context);
        none.setText(R.string.local_source_list_bind_none);
        none.setSingleLine(true);
        ViewTheme.INSTANCE.tintCompound(context, none);
        none.setLayoutParams(new RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        none.setOnClickListener(view -> {
            LocalSource.setJarBinding(entry.path, "");
            holder[0].dismiss();
            load();
        });
        group.addView(none);
        if (TextUtils.isEmpty(entry.boundJar)) group.check(none.getId());
        for (String jar : jarPaths) {
            RadioButton item = new RadioButton(context);
            item.setText(new File(jar).getName());
            item.setSingleLine(true);
            ViewTheme.INSTANCE.tintCompound(context, item);
            item.setLayoutParams(new RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            item.setOnClickListener(view -> {
                LocalSource.setJarBinding(entry.path, jar);
                holder[0].dismiss();
                load();
            });
            group.addView(item);
            if (jar.equals(entry.boundJar)) group.check(item.getId());
        }
        holder[0] = LightDialog.create(context, context.getString(R.string.local_source_list_bind_title), scroll,
                context.getString(R.string.dialog_negative), view -> holder[0].dismiss(), null, null);
        holder[0].show();
    }
}
