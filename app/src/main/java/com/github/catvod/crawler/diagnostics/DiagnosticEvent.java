package com.github.catvod.crawler.diagnostics;

// AVBox 适配垫片：诊断事件的链式构造接口与 webhtv 同构，但事件不会被真正收集
// （DebugLogStore 恒关闭），所有方法均为无副作用的空实现。
public final class DiagnosticEvent {

    public enum Status {
        OK,
        KNOWN,
        UNKNOWN,
        NOT_OBSERVABLE,
        NOT_SUPPORTED,
        NOT_COLLECTED,
        UNAVAILABLE,
        STALE,
        PENDING_CALLBACK
    }

    public DiagnosticEvent(String name, String trace, String instance, long generation, long attempt) {
    }

    public DiagnosticEvent source(String engine, String version, String role, String source, String association, String mediaId, String currentMediaId, long sequence, long elapsedNanos) {
        return this;
    }

    public DiagnosticEvent severity(String severity) {
        return this;
    }

    public DiagnosticEvent observed(String key, Object value) {
        return this;
    }

    public DiagnosticEvent requested(String key, Object value) {
        return this;
    }

    public DiagnosticEvent unknown(String key, Object value) {
        return this;
    }

    public DiagnosticEvent coverage(String key, Status status) {
        return this;
    }

    public DiagnosticEvent inferred() {
        return this;
    }

    public DiagnosticEvent pin(String pin) {
        return this;
    }

    public DiagnosticEvent message(String message) {
        return this;
    }
}
