package com.github.catvod.crawler.diagnostics;

// AVBox 适配垫片：诊断采集会话在 AVBox 不存在，current 恒返回 null
// （调用方均已按可空处理），stop 为空操作。
public final class DiagnosticCapture {

    private DiagnosticCapture() {
    }

    public static Session current(String trace, long generation, long attempt) {
        return null;
    }

    public static void stop(String reason) {
    }

    public static final class Session {

        private final String instance;

        public Session(String instance) {
            this.instance = instance;
        }

        public String instance() {
            return instance;
        }
    }
}
