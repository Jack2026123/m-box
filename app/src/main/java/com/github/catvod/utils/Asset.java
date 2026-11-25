package com.github.catvod.utils;

import android.content.Context;

import com.github.tvbox.osc.base.App;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

// AVBox 适配垫片：同名同签名实现 webhtv 的 Asset.read（读 assets 文本文件）。
public class Asset {

    public static String read(String name) {
        try {
            Context context = App.getInstance();
            if (context == null) return "";
            InputStream input = context.getAssets().open(name);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
            input.close();
            return output.toString("UTF-8");
        } catch (Throwable ignored) {
            return "";
        }
    }
}
