package com.forge.platform.review;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class UnifiedDiffParser {

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@(?:\\s*(.*))?$");
    private static final Pattern SYMBOL_DECL = Pattern.compile(
        "(?:public|private|protected|export|async|static|final|abstract|default)?\\s*"
            + "(?:class|interface|record|enum|function|type|void|String|int|long|boolean|double|BigDecimal|ResponseEntity|List|Map|Optional|Mono|Flux)\\s+"
            + "([A-Za-z_][A-Za-z0-9_]*)\\s*[({<]"
    );

    public record DiffLine(int lineNumber, String content) {}

    public record ParsedDiffFile(
        String filePath,
        String previousFilePath,
        String changeType,
        String originalContent,
        String modifiedContent,
        List<DiffLine> addedLines,
        List<DiffLine> removedLines,
        List<String> changedSymbols
    ) {
        public ParsedDiffFile(
            String filePath,
            String originalContent,
            String modifiedContent,
            List<DiffLine> addedLines,
            List<DiffLine> removedLines
        ) {
            this(filePath, null, "MODIFIED", originalContent, modifiedContent, addedLines, removedLines, List.of());
        }
    }

    public List<ParsedDiffFile> parse(String rawDiff) {
        if (rawDiff == null || rawDiff.isBlank()) {
            return List.of();
        }
        List<ParsedDiffFile> files = new ArrayList<>();
        String[] lines = rawDiff.split("\\R");

        String currentFile = null;
        String previousFile = null;
        String changeType = "MODIFIED";
        boolean sawOldDevNull = false;
        boolean sawNewDevNull = false;
        StringBuilder original = new StringBuilder();
        StringBuilder modified = new StringBuilder();
        List<DiffLine> added = new ArrayList<>();
        List<DiffLine> removed = new ArrayList<>();
        Set<String> symbols = new LinkedHashSet<>();
        int newLine = 1;
        int oldLine = 1;
        boolean inHunk = false;

        for (String line : lines) {
            if (line.startsWith("diff --git ")) {
                if (currentFile != null || previousFile != null) {
                    files.add(buildParsedFile(currentFile, previousFile, changeType, sawOldDevNull, sawNewDevNull,
                        original, modified, added, removed, symbols));
                }
                currentFile = null;
                previousFile = null;
                changeType = "MODIFIED";
                sawOldDevNull = false;
                sawNewDevNull = false;
                original = new StringBuilder();
                modified = new StringBuilder();
                added = new ArrayList<>();
                removed = new ArrayList<>();
                symbols = new LinkedHashSet<>();
                inHunk = false;

                // Fallback path extraction from `diff --git a/foo b/bar`
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    String aPath = stripDiffPrefix(parts[2], "a/");
                    String bPath = stripDiffPrefix(parts[3], "b/");
                    previousFile = aPath;
                    currentFile = bPath;
                }
                continue;
            }

            if (line.startsWith("new file mode")) {
                changeType = "ADDED";
                continue;
            }
            if (line.startsWith("deleted file mode")) {
                changeType = "DELETED";
                continue;
            }
            if (line.startsWith("rename from ")) {
                previousFile = line.substring("rename from ".length()).trim();
                changeType = "RENAMED";
                continue;
            }
            if (line.startsWith("rename to ")) {
                currentFile = line.substring("rename to ".length()).trim();
                changeType = "RENAMED";
                continue;
            }
            if (line.startsWith("old mode ") || line.startsWith("new mode ")
                || line.startsWith("similarity index ") || line.startsWith("dissimilarity index ")
                || line.startsWith("copy from ") || line.startsWith("copy to ")
                || line.startsWith("index ") || line.startsWith("Binary files ")
                || line.startsWith("\\ No newline at end of file")) {
                continue;
            }

            if (line.startsWith("--- ")) {
                String path = line.substring(4).trim();
                if ("/dev/null".equals(path)) {
                    sawOldDevNull = true;
                    changeType = "ADDED";
                } else {
                    previousFile = stripDiffPrefix(path, "a/");
                }
                continue;
            }

            if (line.startsWith("+++ ")) {
                String path = line.substring(4).trim();
                if ("/dev/null".equals(path)) {
                    sawNewDevNull = true;
                    changeType = "DELETED";
                } else {
                    currentFile = stripDiffPrefix(path, "b/");
                }
                continue;
            }

            Matcher hunk = HUNK_HEADER.matcher(line);
            if (hunk.find()) {
                oldLine = Integer.parseInt(hunk.group(1));
                newLine = Integer.parseInt(hunk.group(2));
                inHunk = true;
                String context = hunk.group(3);
                if (context != null && !context.isBlank()) {
                    extractSymbolsFromLine(context, symbols);
                }
                continue;
            }

            if (!inHunk && currentFile == null && !line.startsWith("+") && !line.startsWith("-")) {
                continue;
            }
            if (currentFile == null) {
                currentFile = previousFile != null ? previousFile : "src/main/java/ChangePatch.java";
            }

            if (line.startsWith("+") && !line.startsWith("+++")) {
                String code = line.substring(1);
                modified.append(code).append("\n");
                added.add(new DiffLine(newLine, code));
                extractSymbolsFromLine(code, symbols);
                newLine++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                String code = line.substring(1);
                original.append(code).append("\n");
                removed.add(new DiffLine(oldLine, code));
                extractSymbolsFromLine(code, symbols);
                oldLine++;
            } else {
                String code = line.startsWith(" ") ? line.substring(1) : line;
                original.append(code).append("\n");
                modified.append(code).append("\n");
                oldLine++;
                newLine++;
            }
        }

        if (currentFile != null || previousFile != null) {
            files.add(buildParsedFile(currentFile, previousFile, changeType, sawOldDevNull, sawNewDevNull,
                original, modified, added, removed, symbols));
        }

        return files;
    }

    private ParsedDiffFile buildParsedFile(
        String currentFile,
        String previousFile,
        String changeType,
        boolean sawOldDevNull,
        boolean sawNewDevNull,
        StringBuilder original,
        StringBuilder modified,
        List<DiffLine> added,
        List<DiffLine> removed,
        Set<String> symbols
    ) {
        String effectivePath = (currentFile != null && !currentFile.isBlank()) ? currentFile : previousFile;
        String effectiveType = changeType;
        if (sawNewDevNull) {
            effectiveType = "DELETED";
        } else if (sawOldDevNull) {
            effectiveType = "ADDED";
        } else if (previousFile != null && effectivePath != null && !previousFile.equals(effectivePath)) {
            effectiveType = "RENAMED";
        }
        return new ParsedDiffFile(
            effectivePath != null ? effectivePath : "unknown",
            previousFile,
            effectiveType,
            original.toString(),
            modified.toString(),
            added,
            removed,
            new ArrayList<>(symbols)
        );
    }

    private void extractSymbolsFromLine(String line, Set<String> symbols) {
        Matcher m = SYMBOL_DECL.matcher(line);
        while (m.find()) {
            String candidate = m.group(1);
            if (candidate != null && candidate.length() >= 2
                && !Set.of("if", "for", "while", "switch", "catch", "return", "new", "throw").contains(candidate)) {
                symbols.add(candidate);
            }
        }
    }

    private String stripDiffPrefix(String path, String prefix) {
        String cleaned = path.trim();
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        return cleaned.startsWith(prefix) ? cleaned.substring(prefix.length()) : cleaned;
    }
}
