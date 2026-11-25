package com.forge.platform.review;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class UnifiedDiffParser {

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    public record DiffLine(int lineNumber, String content) {}

    public record ParsedDiffFile(
        String filePath,
        String originalContent,
        String modifiedContent,
        List<DiffLine> addedLines,
        List<DiffLine> removedLines
    ) {}

    public List<ParsedDiffFile> parse(String rawDiff) {
        if (rawDiff == null || rawDiff.isBlank()) {
            return List.of();
        }
        List<ParsedDiffFile> files = new ArrayList<>();
        String[] lines = rawDiff.split("\\R");

        String currentFile = null;
        StringBuilder original = new StringBuilder();
        StringBuilder modified = new StringBuilder();
        List<DiffLine> added = new ArrayList<>();
        List<DiffLine> removed = new ArrayList<>();
        int newLine = 1;
        int oldLine = 1;

        for (String line : lines) {
            if (line.startsWith("diff --git ")) {
                if (currentFile != null) {
                    files.add(new ParsedDiffFile(currentFile, original.toString(), modified.toString(), added, removed));
                }
                currentFile = null;
                original = new StringBuilder();
                modified = new StringBuilder();
                added = new ArrayList<>();
                removed = new ArrayList<>();
                continue;
            }
            if (line.startsWith("+++ b/")) {
                currentFile = line.substring(6).trim();
                continue;
            }
            if (line.startsWith("+++ ")) {
                String path = line.substring(4).trim();
                if (!"/dev/null".equals(path)) {
                    currentFile = path.startsWith("b/") ? path.substring(2) : path;
                }
                continue;
            }
            if (line.startsWith("--- ") || line.startsWith("index ") || line.startsWith("new file mode")) {
                continue;
            }
            Matcher hunk = HUNK_HEADER.matcher(line);
            if (hunk.find()) {
                oldLine = Integer.parseInt(hunk.group(1));
                newLine = Integer.parseInt(hunk.group(2));
                continue;
            }
            if (currentFile == null) {
                currentFile = "src/main/java/ChangePatch.java";
            }

            if (line.startsWith("+") && !line.startsWith("+++")) {
                String code = line.substring(1);
                modified.append(code).append("\n");
                added.add(new DiffLine(newLine, code));
                newLine++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                String code = line.substring(1);
                original.append(code).append("\n");
                removed.add(new DiffLine(oldLine, code));
                oldLine++;
            } else {
                String code = line.startsWith(" ") ? line.substring(1) : line;
                original.append(code).append("\n");
                modified.append(code).append("\n");
                oldLine++;
                newLine++;
            }
        }

        if (currentFile != null) {
            files.add(new ParsedDiffFile(currentFile, original.toString(), modified.toString(), added, removed));
        }

        return files;
    }
}
