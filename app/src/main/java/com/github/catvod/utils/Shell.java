package com.github.catvod.utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class Shell {

    public static String exec(String command) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            StringBuilder builder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) builder.append(line).append("\n");
            }
            process.waitFor();
            return builder.toString();
        } catch (Throwable e) {
            return "";
        }
    }
}
