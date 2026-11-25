package com.fongmi.android.tv.setting;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.KV;

public class DanmakuSetting {

    public static boolean isLoad() {
        return false;
    }

    public static boolean isShow() {
        return KV.get(HawkConfig.DANMU_OPEN, false);
    }
}
