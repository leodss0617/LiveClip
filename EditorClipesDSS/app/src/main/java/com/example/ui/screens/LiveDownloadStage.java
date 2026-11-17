package com.example.ui.screens;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

enum LiveDownloadStage {
    DOWNLOADING,
    ASSEMBLING,
    FINALIZING;

    private static final Pattern COMPLETE = Pattern.compile(
            "(?:^|\\s)100(?:\\.0+)?%\\s+of\\s+\\S+\\s+in\\s+\\d+(?::\\d+)+");
    private static final Pattern PROGRESS = Pattern.compile(
            "(?:^|\\s)(\\d+(?:\\.\\d+)?)%.*\\bETA\\b");

    static LiveDownloadStage fromLog(String log) {
        String[] lines = log.split("\\r\\n|\\n|\\r");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index];
            if (line.contains("Baixando a live (")) {
                break;
            }
            if (COMPLETE.matcher(line).find()) {
                return FINALIZING;
            }
            Matcher progress = PROGRESS.matcher(line);
            if (progress.find()) {
                return Double.parseDouble(progress.group(1)) >= 100
                        ? ASSEMBLING : DOWNLOADING;
            }
        }
        return DOWNLOADING;
    }
}
