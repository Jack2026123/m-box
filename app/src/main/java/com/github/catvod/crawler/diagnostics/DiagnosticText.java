package com.github.catvod.crawler.diagnostics;

import java.io.PrintWriter;
import java.io.StringWriter;

// AVBox 适配垫片：诊断文本清洗的最小等价实现（原样返回/异常堆栈转字符串）。
public final class DiagnosticText {

    private DiagnosticText() {
    }

    public static Cleaned clean(String text) {
        return new Cleaned(text == null ? "" : text);
    }

    public static String throwable(Throwable error) {
        if (error == null) return "";
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    public record Cleaned(String text) {
    }
}
