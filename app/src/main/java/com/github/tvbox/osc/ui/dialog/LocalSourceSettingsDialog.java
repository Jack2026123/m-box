package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.LocalSource;
import com.github.tvbox.osc.ui.theme.ViewTheme;
import com.github.tvbox.osc.util.Notify;
import com.github.catvod.utils.Prefers;
import android.widget.Button;
import android.widget.TextView;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

public class LocalSourceSettingsDialog {

    private final Context context;
    private final Runnable onClose;
    private final Runnable onLoaded;
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout dirsBox;
    private LinearLayout blockedBox;
    private LinearLayout excludedBox;
    private LinearLayout excludedChipRow;
    private EditText excludedSearchInput;
    private LinearLayout extBox;
    private TextView candidatesText;
    private TextView extHeader;
    private TextView spiderText;
    private Button parsesButton;
    private EditText outputInput;
    private EditText liveInput;
    private RadioGroup modeGroup;
    private RadioButton modeFile;
    private RadioButton modeSource;
    private CheckBox replaceBox;
    private CheckBox mergeBox;
    private CheckBox base64Box;

    private static final int REQUEST_PICK_DIR = 0x5A01;
    private static LocalSourceSettingsDialog current;

    private final ArrayList<String> dirNames = new ArrayList<>();
    private final ArrayList<String> fileNames = new ArrayList<>();
    private final ArrayList<String> extNames = new ArrayList<>();
    // 2.17 忽略文件名分组显示（同 AI 源列表）：文件名 → 文件夹、已展开的文件夹、搜索词、当前 chip。
    private final LinkedHashMap<String, String> excludedFolderOf = new LinkedHashMap<>();
    private final HashSet<String> excludedExpandedFolders = new HashSet<>();
    private String excludedSearchQuery = "";
    private String excludedActiveChip = "全部";
    private static final int FOLD_PREVIEW = 5;

    private String spiderDisplay = "";
    private boolean spiderTouched = false;
    private boolean blockedExpanded = false;

    private LocalSourceSettingsDialog(Context context, Runnable onClose, Runnable onLoaded) {
        this.context = context;
        this.onClose = onClose;
        this.onLoaded = onLoaded;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static void show(Context context, Runnable onClose) {
        show(context, onClose, null);
    }

    public static void show(Context context, Runnable onClose, Runnable onLoaded) {
        new LocalSourceSettingsDialog(context, onClose, onLoaded).build();
    }

    private TextView label(int resId) {
        TextView view = new TextView(context);
        view.setText(resId);
        view.setTextSize(ViewTheme.BODY_SP);
        view.setTextColor(ViewTheme.INSTANCE.onSurfaceArgb(context));
        view.setTypeface(ViewTheme.INSTANCE.mediumTypeface());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = 16;
        view.setLayoutParams(params);
        return view;
    }

    private TextView note(String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(ViewTheme.SECONDARY_SP);
        view.setTextColor(ViewTheme.INSTANCE.onSurfaceVariantArgb(context));
        return view;
    }

    private CheckBox check(String text, boolean checked) {
        CheckBox box = new CheckBox(context);
        box.setText(text);
        box.setChecked(checked);
        ViewTheme.INSTANCE.tintCompound(context, box);
        box.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private void build() {
        current = this;
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(4, 0, 4, 0);

        root.addView(note(context.getString(R.string.local_source_settings_hint)));
        root.addView(label(R.string.local_source_settings_dirs));
        dirsBox = new LinearLayout(context);
        dirsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(dirsBox);
        LinearLayout addRow = new LinearLayout(context);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams addRowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addRowParams.topMargin = dp(12);
        addRow.setLayoutParams(addRowParams);
        EditText dirInput = new EditText(context);
        dirInput.setHint(R.string.local_source_dir_hint);
        dirInput.setSingleLine(true);
        ViewTheme.INSTANCE.styleInput(context, dirInput);
        LinearLayout.LayoutParams dirInputParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dirInputParams.rightMargin = dp(8);
        dirInput.setLayoutParams(dirInputParams);
        Button addButton = new Button(context);
        addButton.setText(R.string.local_source_settings_dir_add);
        ViewTheme.INSTANCE.styleTonalButton(context, addButton);
        addRow.addView(dirInput);
        addRow.addView(addButton);
        root.addView(addRow);
        addButton.setOnClickListener(view -> {
            String trim = dirInput.getText() == null ? "" : dirInput.getText().toString().trim();
            if (TextUtils.isEmpty(trim)) {
                Notify.show(context.getString(R.string.local_source_dir_required));
                return;
            }
            JsonObject cfg = LocalSource.loadSettings();
            ArrayList<String> dirs = LocalSource.stringList(cfg, "scan_dirs");
            if (!dirs.contains(trim)) dirs.add(trim);
            ArrayList<String> enabled = LocalSource.stringList(cfg, "scan_enabled_dirs");
            if (!enabled.contains(trim)) enabled.add(trim);
            cfg.add("scan_dirs", LocalSource.toJsonArray(dirs));
            cfg.add("scan_enabled_dirs", LocalSource.toJsonArray(enabled));
            LocalSource.saveSettings(cfg);
            dirInput.setText("");
            fillDirs();
            refreshCandidates();
        });
        Button pickDirButton = new Button(context);
        pickDirButton.setText(R.string.local_source_settings_dir_pick);
        ViewTheme.INSTANCE.styleTonalButton(context, pickDirButton);
        LinearLayout.LayoutParams pickDirParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pickDirParams.topMargin = dp(12);
        pickDirButton.setLayoutParams(pickDirParams);
        pickDirButton.setOnClickListener(view -> pickDirWithSaf());
        root.addView(pickDirButton);

        candidatesText = new TextView(context);
        candidatesText.setTextSize(ViewTheme.SECONDARY_SP);
        candidatesText.setTextColor(ViewTheme.INSTANCE.onSurfaceVariantArgb(context));
        root.addView(candidatesText);
        Button refreshButton = new Button(context);
        refreshButton.setText(R.string.local_source_settings_list_refresh);
        ViewTheme.INSTANCE.styleTonalButton(context, refreshButton);
        refreshButton.setOnClickListener(view -> refreshCandidates());
        root.addView(refreshButton);

        root.addView(label(R.string.local_source_settings_blocked));
        blockedBox = new LinearLayout(context);
        blockedBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(blockedBox);

        root.addView(label(R.string.local_source_settings_excluded));
        // 2.17 忽略文件名分组显示：搜索框 + 文件夹快捷 chip（同 AI 源列表）。
        excludedSearchInput = new EditText(context);
        excludedSearchInput.setHint("搜索文件名...");
        excludedSearchInput.setSingleLine(true);
        ViewTheme.INSTANCE.styleInput(context, excludedSearchInput);
        LinearLayout.LayoutParams excludedSearchParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        excludedSearchParams.topMargin = dp(8);
        excludedSearchInput.setLayoutParams(excludedSearchParams);
        excludedSearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                excludedSearchQuery = s == null ? "" : s.toString();
                fillExcluded();
            }
        });
        root.addView(excludedSearchInput);
        excludedChipRow = new LinearLayout(context);
        excludedChipRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView excludedChipScroll = new HorizontalScrollView(context);
        excludedChipScroll.setHorizontalScrollBarEnabled(false);
        excludedChipScroll.addView(excludedChipRow);
        LinearLayout.LayoutParams excludedChipScrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        excludedChipScrollParams.topMargin = dp(10);
        excludedChipScroll.setLayoutParams(excludedChipScrollParams);
        root.addView(excludedChipScroll);
        excludedBox = new LinearLayout(context);
        excludedBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(excludedBox);

        root.addView(label(R.string.local_source_settings_extensions));
        extHeader = new TextView(context);
        extHeader.setTextSize(ViewTheme.BODY_SP);
        extHeader.setTextColor(ViewTheme.INSTANCE.onSurfaceArgb(context));
        extHeader.setTypeface(ViewTheme.INSTANCE.mediumTypeface());
        root.addView(extHeader);
        extBox = new LinearLayout(context);
        extBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(extBox);
        extHeader.setOnClickListener(view -> {
            ArrayList<String> all = extNames.isEmpty() ? new ArrayList<>(Arrays.asList(LocalSource.EXTENSIONS)) : new ArrayList<>(extNames);
            LocalSource.saveExtensions(all);
            fillExtensions();
        });

        root.addView(label(R.string.local_source_settings_mode));
        modeGroup = new RadioGroup(context);
        modeGroup.setOrientation(LinearLayout.VERTICAL);
        modeFile = new RadioButton(context);
        modeFile.setText(R.string.local_source_settings_mode_file);
        modeFile.setSingleLine(true);
        ViewTheme.INSTANCE.tintCompound(context, modeFile);
        modeFile.setLayoutParams(new RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        modeSource = new RadioButton(context);
        modeSource.setText(R.string.local_source_settings_mode_source);
        modeSource.setSingleLine(true);
        ViewTheme.INSTANCE.tintCompound(context, modeSource);
        modeSource.setLayoutParams(new RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        modeGroup.addView(modeFile);
        modeGroup.addView(modeSource);
        root.addView(modeGroup);
        root.addView(note(context.getString(R.string.local_source_settings_mode_hint)));

        root.addView(label(R.string.local_source_settings_output));
        outputInput = new EditText(context);
        outputInput.setSingleLine(true);
        ViewTheme.INSTANCE.styleInput(context, outputInput);
        root.addView(outputInput);

        JsonObject cfg = LocalSource.loadSettings();
        replaceBox = check(context.getString(R.string.local_source_settings_replace), LocalSource.getBool(cfg, "replace_sites", true));
        replaceBox.setVisibility(View.GONE);
        root.addView(replaceBox);
        mergeBox = check(context.getString(R.string.local_source_settings_merge), LocalSource.getBool(cfg, "merge_same_name", false));
        mergeBox.setOnCheckedChangeListener((button, checked) -> LocalSource.updateSetting(new JsonPrimitive(checked), "merge_same_name"));
        root.addView(mergeBox);
        base64Box = check(context.getString(R.string.local_source_settings_base64), LocalSource.getBool(cfg, "decode_base64", true));
        root.addView(base64Box);

        root.addView(label(R.string.local_source_settings_extra));
        parsesButton = new Button(context);
        ViewTheme.INSTANCE.styleTonalButton(context, parsesButton);
        parsesButton.setOnClickListener(view -> editParses());
        root.addView(parsesButton);

        root.addView(label(R.string.local_source_settings_spider));
        spiderText = new TextView(context);
        spiderText.setTextSize(ViewTheme.BODY_SP);
        spiderText.setTextColor(ViewTheme.INSTANCE.onSurfaceArgb(context));
        root.addView(spiderText);
        LinearLayout spiderRow = new LinearLayout(context);
        spiderRow.setOrientation(LinearLayout.HORIZONTAL);
        Button pickButton = new Button(context);
        pickButton.setText(R.string.local_source_settings_spider_pick);
        ViewTheme.INSTANCE.styleTonalButton(context, pickButton);
        // 2.17 选择.jar 与清除之间加 8dp 间距（此前两钮直接并排、挨在一起），与目录输入、直播地址两行对齐。
        LinearLayout.LayoutParams pickParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pickParams.rightMargin = dp(8);
        pickButton.setLayoutParams(pickParams);
        pickButton.setOnClickListener(view -> pickJar());
        Button clearSpider = new Button(context);
        clearSpider.setText(R.string.local_source_settings_clear);
        ViewTheme.INSTANCE.styleTonalButton(context, clearSpider);
        clearSpider.setOnClickListener(view -> {
            spiderTouched = true;
            setSpider("");
        });
        spiderRow.addView(pickButton);
        spiderRow.addView(clearSpider);
        root.addView(spiderRow);

        root.addView(label(R.string.local_source_settings_live_url));
        LinearLayout liveRow = new LinearLayout(context);
        liveRow.setOrientation(LinearLayout.HORIZONTAL);
        liveInput = new EditText(context);
        liveInput.setSingleLine(true);
        ViewTheme.INSTANCE.styleInput(context, liveInput);
        liveInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button clearLive = new Button(context);
        clearLive.setText(R.string.local_source_settings_clear);
        ViewTheme.INSTANCE.styleTonalButton(context, clearLive);
        clearLive.setOnClickListener(view -> liveInput.setText(""));
        liveRow.addView(liveInput);
        liveRow.addView(clearLive);
        root.addView(liveRow);

        initValues(cfg);
        fillDirs();
        fillExtensions();
        refreshCandidates();

        // 2.17 底栏钉底：内容区限高（屏高 62%），内容在框内滚，取消/保存常驻底部。
        // ScrollView wrap_content 会按整条内容量高度，把弹窗撑出屏幕、底栏顶出去；
        // 这里用 AT_MOST 封顶，内容短时仍按内容高、内容长时内部滚动。
        ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int maxHeight = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.62f);
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
            }
        };
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Dialog[] holder = new Dialog[1];
        holder[0] = LightDialog.create(context, context.getString(R.string.local_source_settings_title), scroll,
                context.getString(R.string.local_source_settings_save_load), view -> {
                    JsonObject current = LocalSource.loadSettings();
                    LocalSource.updateSetting(new JsonPrimitive(outputInput.getText() == null ? "" : outputInput.getText().toString().trim()), "output_path");
                    LocalSource.updateSetting(new JsonPrimitive(modeFile.isChecked() ? "file" : "source"), "scan_mode");
                    LocalSource.updateSetting(new JsonPrimitive(replaceBox.isChecked()), "replace_sites");
                    LocalSource.updateSetting(new JsonPrimitive(mergeBox.isChecked()), "merge_same_name");
                    if (spiderTouched) {
                        LocalSource.applySpiderJar(spiderDisplay);
                    }
                    LocalSource.updateSetting(new JsonPrimitive(liveInput.getText() == null ? "" : liveInput.getText().toString().trim()), "live_url");
                    LocalSource.updateSetting(new JsonPrimitive(base64Box.isChecked()), "decode_base64");
                    if (LocalSource.stringList(current, "scan_dirs").isEmpty()) {
                        Notify.show(context.getString(R.string.local_source_dir_required));
                        return;
                    }
                    holder[0].dismiss();
                    LocalSourceDialog.scanAndLoad(context, () -> {
                        if (onClose != null) onClose.run();
                        if (onLoaded != null) onLoaded.run();
                    });
                },
                context.getString(R.string.dialog_negative), view -> holder[0].dismiss());
        holder[0].setOnDismissListener(dialog -> {
            if (current == this) current = null;
            if (onClose != null) onClose.run();
        });
        holder[0].show();
    }

    private void initValues(JsonObject cfg) {
        outputInput.setText(LocalSource.getString(cfg, "output_path"));
        modeGroup.check("file".equals(LocalSource.getString(cfg, "scan_mode")) ? modeFile.getId() : modeSource.getId());
        String spider = LocalSource.spiderJarPath();
        setSpider(spider);
        if (LocalSource.isSpiderAuto(spider)) {
            spiderText.setText(spider + context.getString(R.string.local_source_settings_spider_auto));
        }
        liveInput.setText(LocalSource.liveUrl());
        refreshParsesButton(cfg);
    }

    private void refreshParsesButton(JsonObject cfg) {
        JsonArray entries = cfg.has("parse_entries") && cfg.get("parse_entries").isJsonArray() ? cfg.getAsJsonArray("parse_entries") : LocalSource.defaultParses();
        parsesButton.setText(context.getString(R.string.local_source_settings_parses, entries.size()));
    }

    private void setSpider(String path) {
        spiderDisplay = path == null ? "" : path.trim();
        spiderText.setText(TextUtils.isEmpty(spiderDisplay) ? context.getString(R.string.local_source_settings_spider_none) : spiderDisplay);
    }

    private void fillDirs() {
        dirsBox.removeAllViews();
        JsonObject cfg = LocalSource.loadSettings();
        ArrayList<String> dirs = LocalSource.stringList(cfg, "scan_dirs");
        ArrayList<String> enabled = LocalSource.stringList(cfg, "scan_enabled_dirs");
        if (dirs.isEmpty()) {
            dirsBox.addView(note(context.getString(R.string.local_source_settings_dirs_empty)));
            return;
        }
        for (String dir : dirs) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBaselineAligned(false);
            row.setGravity(android.view.Gravity.TOP);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = dp(8);
            row.setLayoutParams(rowParams);
            CheckBox box = new CheckBox(context);
            box.setChecked(LocalSource.isDirEnabled(dir, enabled));
            ViewTheme.INSTANCE.tintCompound(context, box);
            box.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView pathView = new TextView(context);
            pathView.setText(dir);
            pathView.setTextSize(ViewTheme.BODY_SP);
            pathView.setTextColor(ViewTheme.INSTANCE.onSurfaceArgb(context));
            LinearLayout.LayoutParams pathParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            pathParams.rightMargin = dp(8);
            pathView.setLayoutParams(pathParams);
            pathView.setOnClickListener(v -> box.toggle());
            box.setOnCheckedChangeListener((button, checked) -> {
                JsonObject current = LocalSource.loadSettings();
                ArrayList<String> all = LocalSource.stringList(current, "scan_dirs");
                ArrayList<String> en = LocalSource.stringList(current, "scan_enabled_dirs");
                if (checked) {
                    if (!LocalSource.isDirEnabled(dir, en)) en.add(dir);
                } else {
                    String canon = LocalSource.canonPath(dir);
                    Iterator<String> it = en.iterator();
                    while (it.hasNext()) {
                        if (canon.equals(LocalSource.canonPath(it.next()))) it.remove();
                    }
                }
                current.add("scan_enabled_dirs", LocalSource.toJsonArray(LocalSource.intersect(all, en)));
                LocalSource.saveSettings(current);
            });
            Button remove = new Button(context);
            remove.setText(R.string.local_source_settings_dir_remove);
            ViewTheme.INSTANCE.styleTonalButton(context, remove);
            remove.setOnClickListener(view -> {
                JsonObject current = LocalSource.loadSettings();
                ArrayList<String> all = LocalSource.stringList(current, "scan_dirs");
                ArrayList<String> en = LocalSource.stringList(current, "scan_enabled_dirs");
                String canon = LocalSource.canonPath(dir);
                Iterator<String> itAll = all.iterator();
                while (itAll.hasNext()) {
                    if (canon.equals(LocalSource.canonPath(itAll.next()))) itAll.remove();
                }
                Iterator<String> itEn = en.iterator();
                while (itEn.hasNext()) {
                    if (canon.equals(LocalSource.canonPath(itEn.next()))) itEn.remove();
                }
                current.add("scan_dirs", LocalSource.toJsonArray(all));
                current.add("scan_enabled_dirs", LocalSource.toJsonArray(en));
                LocalSource.saveSettings(current);
                fillDirs();
                refreshCandidates();
            });
            row.addView(box);
            row.addView(pathView);
            row.addView(remove);
            dirsBox.addView(row);
        }
    }

    private void fillBlocked() {
        blockedBox.removeAllViews();
        if (dirNames.isEmpty()) {
            blockedBox.addView(note(context.getString(R.string.local_source_settings_list_empty)));
            return;
        }
        ArrayList<String> blocked = LocalSource.stringList(LocalSource.loadSettings(), "blocked_dirs");
        int limit = blockedExpanded ? dirNames.size() : Math.min(FOLD_PREVIEW, dirNames.size());
        for (int i = 0; i < limit; i++) {
            String name = dirNames.get(i);
            CheckBox box = check(name, blocked.contains(name));
            box.setOnCheckedChangeListener((button, checked) -> toggleListSetting("blocked_dirs", name, checked));
            blockedBox.addView(box);
        }
        addFoldToggle(blockedBox, dirNames, blocked);
    }

    // 2.17 忽略文件名分组显示（同 AI 源列表）：有搜索词或 chip 不是“全部”时平铺过滤结果；
    // 否则按文件夹分组折叠，点文件夹标题展开/收起该组。勾选状态读设置、即时保存，跨筛选保留。
    private void fillExcluded() {
        excludedBox.removeAllViews();
        if (fileNames.isEmpty()) {
            excludedBox.addView(note(context.getString(R.string.local_source_settings_list_empty)));
            return;
        }
        String query = excludedSearchQuery == null ? "" : excludedSearchQuery.trim();
        boolean filtering = !query.isEmpty() || !"全部".equals(excludedActiveChip);
        if (filtering) {
            String lowerQuery = query.toLowerCase();
            boolean any = false;
            for (String name : fileNames) {
                if (!excludedChipMatches(name)) continue;
                if (!query.isEmpty() && !name.toLowerCase().contains(lowerQuery)) continue;
                excludedBox.addView(excludedFileCheckBox(name));
                any = true;
            }
            if (!any) excludedBox.addView(note("没有匹配的文件"));
            return;
        }
        for (String folder : excludedFolderList()) {
            ArrayList<String> list = new ArrayList<>();
            for (String name : fileNames) {
                if (folder.equals(excludedFolderOfName(name))) list.add(name);
            }
            boolean expanded = excludedExpandedFolders.contains(folder);
            TextView header = new TextView(context);
            header.setText((expanded ? "▾ " : "▸ ") + folder + "（" + list.size() + " 个文件）");
            header.setTextSize(ViewTheme.BODY_SP);
            header.setTextColor(ViewTheme.INSTANCE.onSurfaceArgb(context));
            header.setTypeface(ViewTheme.INSTANCE.mediumTypeface());
            LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            headerParams.topMargin = dp(8);
            header.setLayoutParams(headerParams);
            header.setOnClickListener(view -> {
                if (expanded) excludedExpandedFolders.remove(folder);
                else excludedExpandedFolders.add(folder);
                fillExcluded();
            });
            excludedBox.addView(header);
            if (expanded) {
                for (String name : list) {
                    excludedBox.addView(excludedFileCheckBox(name));
                }
            }
        }
    }

    private CheckBox excludedFileCheckBox(String name) {
        ArrayList<String> excluded = LocalSource.stringList(LocalSource.loadSettings(), "excluded_files");
        CheckBox box = check(name, excluded.contains(name));
        box.setOnCheckedChangeListener((button, checked) -> toggleListSetting("excluded_files", name, checked));
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) box.getLayoutParams();
        params.leftMargin = dp(16);
        box.setLayoutParams(params);
        return box;
    }

    private void refreshExcludedChips() {
        excludedChipRow.removeAllViews();
        ArrayList<String> chips = new ArrayList<>();
        chips.add("全部");
        chips.addAll(excludedFolderList());
        for (String chip : chips) {
            Button button = new Button(context);
            button.setText("全部".equals(chip) ? "全部" : "[" + chip + "]");
            button.setMinWidth(0);
            button.setPadding(dp(12), dp(6), dp(12), dp(6));
            ViewTheme.INSTANCE.styleTabButton(context, button, chip.equals(excludedActiveChip));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = dp(8);
            button.setLayoutParams(params);
            button.setOnClickListener(view -> {
                excludedActiveChip = chip;
                refreshExcludedChips();
                fillExcluded();
            });
            excludedChipRow.addView(button);
        }
    }

    private ArrayList<String> excludedFolderList() {
        ArrayList<String> folders = new ArrayList<>();
        for (String name : fileNames) {
            String folder = excludedFolderOfName(name);
            if (!folders.contains(folder)) folders.add(folder);
        }
        Collections.sort(folders);
        if (folders.remove("未分类")) folders.add(0, "未分类");
        return folders;
    }

    private String excludedFolderOfName(String name) {
        String folder = excludedFolderOf.get(name);
        return folder == null ? "未分类" : folder;
    }

    private boolean excludedChipMatches(String name) {
        if ("全部".equals(excludedActiveChip)) return true;
        return excludedActiveChip.equals(excludedFolderOfName(name));
    }

    // 忽略目录名清单的折叠钮（2.17 起仅目录清单用；忽略文件名已改按文件夹分组折叠，不再走这里）。
    // Exactly one fold toggle per list, always appended after the rendered
    // items: in the folded state it sits right below the 5th preview item
    // ("expand all"); in the expanded state it lands after the very last
    // item ("collapse all"), so it is reachable at the bottom of the list.
    private void addFoldToggle(LinearLayout box, ArrayList<String> names, ArrayList<String> checkedNames) {
        if (names.size() <= FOLD_PREVIEW) return;
        Button toggle = new Button(context);
        ViewTheme.INSTANCE.styleTonalButton(context, toggle);
        if (blockedExpanded) {
            toggle.setText(R.string.local_source_settings_list_collapse);
        } else {
            int hiddenChecked = 0;
            for (int i = FOLD_PREVIEW; i < names.size(); i++) {
                if (checkedNames.contains(names.get(i))) hiddenChecked++;
            }
            if (hiddenChecked > 0) {
                toggle.setText(context.getString(R.string.local_source_settings_list_expand_count, hiddenChecked));
            } else {
                toggle.setText(R.string.local_source_settings_list_expand);
            }
        }
        toggle.setOnClickListener(view -> {
            blockedExpanded = !blockedExpanded;
            fillBlocked();
        });
        box.addView(toggle);
    }

    private void toggleListSetting(String key, String name, boolean add) {
        ArrayList<String> list = new ArrayList<>(LocalSource.splitUserText(TextUtils.join(",", LocalSource.stringList(LocalSource.loadSettings(), key))));
        if (add) {
            if (!list.contains(name)) list.add(name);
        } else {
            list.remove(name);
        }
        LocalSource.updateSetting(LocalSource.toJsonArray(new ArrayList<>(LocalSource.splitUserText(TextUtils.join(",", list)))), key);
    }

    private void fillExtensions() {
        extBox.removeAllViews();
        ArrayList<String> list = extNames.isEmpty() ? new ArrayList<>(Arrays.asList(LocalSource.EXTENSIONS)) : extNames;
        ArrayList<String> selected = LocalSource.stringList(LocalSource.loadSettings(), "scan_extensions");
        boolean empty = selected.isEmpty();
        for (String ext : list) {
            CheckBox box = check(ext, empty || selected.contains(ext));
            box.setOnCheckedChangeListener((button, checked) -> {
                ArrayList<String> current = new ArrayList<>(LocalSource.stringList(LocalSource.loadSettings(), "scan_extensions"));
                if (current.isEmpty()) current.addAll(list);
                if (checked) {
                    if (!current.contains(ext)) current.add(ext);
                } else {
                    current.remove(ext);
                }
                LocalSource.saveExtensions(current);
                fillExtensions();
            });
            extBox.addView(box);
        }
        extHeader.setText(context.getString(R.string.local_source_settings_ext_all, empty ? list.size() : selected.size(), list.size()));
    }

    private void refreshCandidates() {
        candidatesText.setText(R.string.local_source_settings_list_loading);
        blockedBox.removeAllViews();
        excludedBox.removeAllViews();
        new Thread(() -> {
            ArrayList<String> dirs = LocalSource.collectNames(true);
            ArrayList<String> files = LocalSource.collectNames(false);
            LinkedHashMap<String, String> folders = LocalSource.collectFileFolders();
            JsonObject cfg = LocalSource.loadSettings();
            LinkedHashSet<String> found = new LinkedHashSet<>();
            for (String dir : LocalSource.enabledDirs(cfg)) {
                File file = new File(dir);
                if (file.isDirectory()) LocalSource.collectExtensions(file, 0, found, cfg);
            }
            ArrayList<String> exts = new ArrayList<>();
            for (String ext : LocalSource.EXTENSIONS) if (found.contains(ext)) exts.add(ext);
            if (exts.isEmpty()) exts.addAll(Arrays.asList(LocalSource.EXTENSIONS));
            ArrayList<String> finalExts = exts;
            main.post(() -> {
                dirNames.clear();
                dirNames.addAll(dirs);
                fileNames.clear();
                fileNames.addAll(files);
                excludedFolderOf.clear();
                excludedFolderOf.putAll(folders);
                extNames.clear();
                extNames.addAll(finalExts);
                candidatesText.setText(context.getString(R.string.local_source_settings_list_count, extNames.size(), dirNames.size(), fileNames.size()));
                fillBlocked();
                fillExcluded();
                refreshExcludedChips();
                fillExtensions();
            });
        }, "local-source-candidates").start();
    }

    private void editParses() {
        JsonObject cfg = LocalSource.loadSettings();
        JsonArray entries = cfg.has("parse_entries") && cfg.get("parse_entries").isJsonArray() ? cfg.getAsJsonArray("parse_entries") : LocalSource.defaultParses();
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(24, 8, 24, 8);
        ArrayList<CheckBox> boxes = new ArrayList<>();
        ArrayList<EditText> inputs = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (!entries.get(i).isJsonObject()) continue;
            JsonObject entry = entries.get(i).getAsJsonObject();
            CheckBox check = check(LocalSource.getString(entry, "name"), LocalSource.getBool(entry, "enabled", true));
            EditText input = new EditText(context);
            input.setText(LocalSource.getString(entry, "url"));
            input.setSingleLine(true);
            ViewTheme.INSTANCE.styleInput(context, input);
            box.addView(check);
            box.addView(input);
            boxes.add(check);
            inputs.add(input);
        }
        if (boxes.isEmpty()) box.addView(note(context.getString(R.string.local_source_settings_parses_empty)));
        ScrollView scroll = new ScrollView(context);
        scroll.addView(box, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Dialog[] holder = new Dialog[1];
        holder[0] = LightDialog.create(context, context.getString(R.string.local_source_settings_parses, entries.size()), scroll,
                context.getString(R.string.dialog_positive), view -> {
                    JsonArray out = new JsonArray();
                    int index = 0;
                    for (int i = 0; i < entries.size(); i++) {
                        if (!entries.get(i).isJsonObject()) continue;
                        JsonObject copy = entries.get(i).getAsJsonObject().deepCopy();
                        copy.addProperty("enabled", boxes.get(index).isChecked());
                        String url = inputs.get(index).getText() == null ? "" : inputs.get(index).getText().toString().trim();
                        if (!TextUtils.isEmpty(url)) copy.addProperty("url", url);
                        out.add(copy);
                        index++;
                    }
                    LocalSource.updateSetting(out, "parse_entries");
                    refreshParsesButton(LocalSource.loadSettings());
                    holder[0].dismiss();
                },
                context.getString(R.string.dialog_negative), view -> holder[0].dismiss());
        holder[0].show();
    }

    private void pickJar() {
        new Thread(() -> {
            JsonObject cfg = LocalSource.loadSettings();
            ArrayList<String> dirs = LocalSource.stringList(cfg, "scan_dirs");
            if (dirs.isEmpty()) dirs = LocalSource.enabledDirs(cfg);
            ArrayList<String> jars = new ArrayList<>();
            for (String dir : dirs) {
                File file = new File(dir);
                if (file.isDirectory()) LocalSource.collectJars(file, 0, LocalSource.maxDepth(cfg), jars);
            }
            jars.sort(LocalSource.BY_NAME_STR);
            if (jars.size() > 3000) jars = new ArrayList<>(jars.subList(0, 3000));
            ArrayList<String> result = jars;
            main.post(() -> showJarPicker(result));
        }, "local-source-jars").start();
    }

    private void showJarPicker(ArrayList<String> jars) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        if (jars.isEmpty()) box.addView(note(context.getString(R.string.local_source_settings_spider_empty)));
        RadioGroup group = new RadioGroup(context);
        group.setOrientation(LinearLayout.VERTICAL);
        box.addView(group);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(box, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Dialog[] holder = new Dialog[1];
        for (String jar : jars) {
            RadioButton item = new RadioButton(context);
            item.setText(new File(jar).getName());
            item.setSingleLine(true);
            ViewTheme.INSTANCE.tintCompound(context, item);
            item.setLayoutParams(new RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            item.setOnClickListener(view -> {
                spiderTouched = true;
                setSpider(jar);
                holder[0].dismiss();
            });
            group.addView(item);
            if (jar.equals(spiderDisplay)) {
                group.check(item.getId());
            }
        }
        holder[0] = LightDialog.create(context, context.getString(R.string.local_source_settings_spider_pick_title), scroll,
                context.getString(R.string.dialog_negative), view -> holder[0].dismiss(), null, null);
        holder[0].show();
    }

    // 1.45 SAF 系统文件夹选择（目录区下面加选择文件夹，对齐昏黄）：
    // 系统选框 → tree Uri → 文件路径 → 加入扫描目录（与手动输入同一套保存逻辑）。
    // View 对话框无法在显示时注册 ActivityResultLauncher，改走经典
    // startActivityForResult + MainActivity 转发 onActivityResult。
    private void pickDirWithSaf() {
        if (!(context instanceof Activity)) {
            Notify.show("当前界面不支持系统文件夹选择，请手动输入路径");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            ((Activity) context).startActivityForResult(intent, REQUEST_PICK_DIR);
        } catch (Throwable e) {
            Notify.show("系统文件夹选择不可用，请手动输入路径");
        }
    }

    public static void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK_DIR) return;
        LocalSourceSettingsDialog dialog = current;
        if (dialog == null) return;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        dialog.handlePickedTree(data.getData());
    }

    private void handlePickedTree(Uri treeUri) {
        try {
            try {
                context.getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
            String path = treePathToFilePath(treeUri);
            if (TextUtils.isEmpty(path)) {
                Notify.show("无法识别该文件夹，请手动输入路径");
                return;
            }
            JsonObject cfg = LocalSource.loadSettings();
            ArrayList<String> dirs = LocalSource.stringList(cfg, "scan_dirs");
            if (!dirs.contains(path)) dirs.add(path);
            ArrayList<String> enabled = LocalSource.stringList(cfg, "scan_enabled_dirs");
            if (!enabled.contains(path)) enabled.add(path);
            cfg.add("scan_dirs", LocalSource.toJsonArray(dirs));
            cfg.add("scan_enabled_dirs", LocalSource.toJsonArray(enabled));
            LocalSource.saveSettings(cfg);
            fillDirs();
            refreshCandidates();
            Notify.show("已添加：" + path);
        } catch (Throwable e) {
            Notify.show("添加文件夹失败，请手动输入路径");
        }
    }

    private String treePathToFilePath(Uri treeUri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            int split = docId.indexOf(':');
            if (split < 0) return null;
            String volume = docId.substring(0, split);
            String rel = docId.substring(split + 1);
            if ("primary".equals(volume)) {
                return new File(Environment.getExternalStorageDirectory(), rel).getAbsolutePath();
            }
            return new File("/storage/" + volume, rel).getAbsolutePath();
        } catch (Throwable e) {
            return null;
        }
    }
}
