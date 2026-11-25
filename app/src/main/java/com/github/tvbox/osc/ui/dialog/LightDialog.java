package com.github.tvbox.osc.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.tvbox.osc.ui.theme.ViewTheme;

// 对齐昏黄 MaterialAlertDialog：内容 wrap_content，窗口高度随内容，不写死屏高百分比。
// 只约束宽度（约 92% 屏宽、上限 560dp），居中显示。
public class LightDialog {

    public static Dialog create(Context context, CharSequence title, View content, String positive, View.OnClickListener onPositive, String negative, View.OnClickListener onNegative) {
        return create(context, title, content, positive, onPositive, negative, onNegative, null, null);
    }

    // 整体下移一点用：yOffsetDp 为窗口相对居中的下移量（dp），默认 0；写源技能包弹窗传 40，其它弹窗不动。
    public static Dialog create(Context context, CharSequence title, View content, String positive, View.OnClickListener onPositive, String negative, View.OnClickListener onNegative, int yOffsetDp) {
        return create(context, title, content, positive, onPositive, negative, onNegative, null, null, yOffsetDp);
    }

    public static Dialog create(Context context, CharSequence title, View content, String positive, View.OnClickListener onPositive, String negative, View.OnClickListener onNegative, String neutral, View.OnClickListener onNeutral) {
        return create(context, title, content, positive, onPositive, negative, onNegative, neutral, onNeutral, 0);
    }

    public static Dialog create(Context context, CharSequence title, View content, String positive, View.OnClickListener onPositive, String negative, View.OnClickListener onNegative, String neutral, View.OnClickListener onNeutral, int yOffsetDp) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(ViewTheme.INSTANCE.dialogBackground(context));
        int pad = dp(context, 20);
        root.setPadding(pad, dp(context, 16), pad, dp(context, 12));

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(ViewTheme.TITLE_SP);
        titleView.setTextColor(ViewTheme.INSTANCE.onSurfaceArgb(context));
        titleView.setTypeface(ViewTheme.INSTANCE.mediumTypeface());
        root.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (content != null) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(context, 12);
            root.addView(content, params);
        }

        if (positive != null || negative != null || neutral != null) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.END);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = dp(context, 8);
            row.setLayoutParams(rowParams);

            if (neutral != null) {
                Button neutralButton = new Button(context);
                neutralButton.setText(neutral);
                ViewTheme.INSTANCE.styleTextButton(context, neutralButton);
                neutralButton.setOnClickListener(view -> {
                    if (onNeutral != null) onNeutral.onClick(view);
                });
                row.addView(neutralButton);
                View spacer = new View(context);
                row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
            }
            if (negative != null) {
                Button negativeButton = new Button(context);
                negativeButton.setText(negative);
                ViewTheme.INSTANCE.styleTextButton(context, negativeButton);
                negativeButton.setOnClickListener(view -> {
                    if (onNegative != null) onNegative.onClick(view);
                    else dialog.dismiss();
                });
                row.addView(negativeButton);
            }
            if (positive != null) {
                Button positiveButton = new Button(context);
                positiveButton.setText(positive);
                ViewTheme.INSTANCE.styleTextButton(context, positiveButton);
                positiveButton.setOnClickListener(view -> {
                    if (onPositive != null) onPositive.onClick(view);
                });
                row.addView(positiveButton);
            }
            root.addView(row);
        }

        dialog.setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ViewTheme.INSTANCE.clearWindowBackground(dialog);
        dialog.setCanceledOnTouchOutside(true);

        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window == null) return;
            WindowManager.LayoutParams lp = window.getAttributes();
            int screenW = context.getResources().getDisplayMetrics().widthPixels;
            int maxW = dp(context, 560);
            // 与昏黄 BaseAlertDialog.setWidth 思路一致：只控宽，高度不写死百分比
            lp.width = Math.min((int) (screenW * 0.92f), maxW);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            lp.y = dp(context, yOffsetDp);
            window.setAttributes(lp);
            window.setLayout(lp.width, WindowManager.LayoutParams.WRAP_CONTENT);
        });
        return dialog;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
