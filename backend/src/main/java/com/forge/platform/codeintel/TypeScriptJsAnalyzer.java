package com.forge.platform.codeintel;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lexical + structural TypeScript/JavaScript analyzer.
 * Strips single-line comments, block comments, and string/template literals while preserving line numbers
 * BEFORE extracting ES/CommonJS imports, classes, interfaces, type aliases, enums, functions, arrow exports,
 * inheritance relationships, and cyclomatic complexity.
 *
 * Scope & limitation: performs structural lexical parsing without running the TypeScript type-checker (tsc),
 * so inferred cross-file generic types and tsconfig path aliases are resolved heuristically by module path.
 */
@Component
public class TypeScriptJsAnalyzer {

    private static final Pattern ES_IMPORT = Pattern.compile(
        "(?:import|export)\\s+(?:type\\s+)?(?:[^;]*?\\s+from\\s+)?['\"]([^'\"]+)['\"]"
    );
    private static final Pattern CJS_REQUIRE = Pattern.compile(
        "require\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)"
    );
    private static final Pattern CLASS_DECL = Pattern.compile(
        "^(?:export\\s+)?(?:default\\s+)?(?:abstract\\s+)?class\\s+([A-Za-z_$][A-Za-z0-9_$]*)(?:\\s+extends\\s+([A-Za-z_$][A-Za-z0-9_$.]*))?(?:\\s+implements\\s+([A-Za-z0-9_$,\\s]+))?"
    );
    private static final Pattern INTERFACE_DECL = Pattern.compile(
        "^(?:export\\s+)?interface\\s+([A-Za-z_$][A-Za-z0-9_$]*)(?:\\s+extends\\s+([A-Za-z0-9_$,\\s]+))?"
    );
    private static final Pattern TYPE_ALIAS_DECL = Pattern.compile(
        "^(?:export\\s+)?type\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:<[^>]+>)?\\s*="
    );
    private static final Pattern ENUM_DECL = Pattern.compile(
        "^(?:export\\s+)?(?:const\\s+)?enum\\s+([A-Za-z_$][A-Za-z0-9_$]*)"
    );
    private static final Pattern FUNC_DECL = Pattern.compile(
        "^(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*[<(]"
    );
    private static final Pattern CONST_ARROW_DECL = Pattern.compile(
        "^(?:export\\s+)?const\\s+([A-Za-z_$][A-Za-z0-9_$]*)(?:\\s*:\\s*[^=]+)?\\s*=\\s*(?:async\\s+)?(?:\\([^)]*\\)|[A-Za-z_$][A-Za-z0-9_$]*)\\s*=>"
    );
    private static final Pattern BRANCH_TOKEN = Pattern.compile(
        "\\b(?:if|for|while|catch|case)\\b|&&|\\|\\||\\?\\?"
    );

    public JavaAstAnalyzer.AstResult analyze(String filePath, String content) {
        String normalizedPath = filePath.replace('\\', '/');
        String primaryNode = JavaAstAnalyzer.extractSimpleName(normalizedPath);
        boolean isTestFile = normalizedPath.contains(".test.") || normalizedPath.contains(".spec.")
            || normalizedPath.contains("/__tests__/");

        List<JavaAstAnalyzer.ParsedSymbol> symbols = new ArrayList<>();
        Set<JavaAstAnalyzer.RawDependency> deps = new LinkedHashSet<>();

        // Strip comments but keep strings for import extraction, then strip strings for declaration/complexity analysis
        String noComments = stripCommentsPreservingLines(content);
        String codeOnly = stripStringsPreservingLines(noComments);

        // 1. Extract ES imports / re-exports and CommonJS requires from comment-free source
        Matcher esm = ES_IMPORT.matcher(noComments);
        while (esm.find()) {
            addImportDependency(deps, primaryNode, normalizedPath, esm.group(1), isTestFile);
        }
        Matcher cjs = CJS_REQUIRE.matcher(noComments);
        while (cjs.find()) {
            addImportDependency(deps, primaryNode, normalizedPath, cjs.group(1), isTestFile);
        }

        // 2. Extract declarations and complexity from comment-free & string-free code lines
        String[] rawLines = content.split("\\R", -1);
        String[] cleanLines = codeOnly.split("\\R", -1);
        int complexity = 1;

        for (int i = 0; i < cleanLines.length; i++) {
            String trimmed = cleanLines[i].trim();
            if (trimmed.isEmpty()) continue;

            Matcher branch = BRANCH_TOKEN.matcher(trimmed);
            while (branch.find()) {
                complexity++;
            }

            int lineNum = i + 1;
            String rawSig = i < rawLines.length ? rawLines[i].trim() : trimmed;

            Matcher cls = CLASS_DECL.matcher(trimmed);
            if (cls.find()) {
                String name = cls.group(1);
                symbols.add(new JavaAstAnalyzer.ParsedSymbol(name, normalizedPath + "#" + name, "CLASS", lineNum, lineNum, rawSig, 1));
                if (cls.group(2) != null && !cls.group(2).isBlank()) {
                    deps.add(new JavaAstAnalyzer.RawDependency(primaryNode, simpleIdent(cls.group(2)), "EXTENDS"));
                }
                if (cls.group(3) != null) {
                    for (String impl : cls.group(3).split(",")) {
                        String id = simpleIdent(impl);
                        if (!id.isBlank()) deps.add(new JavaAstAnalyzer.RawDependency(primaryNode, id, "IMPLEMENTS"));
                    }
                }
                continue;
            }

            Matcher iface = INTERFACE_DECL.matcher(trimmed);
            if (iface.find()) {
                String name = iface.group(1);
                symbols.add(new JavaAstAnalyzer.ParsedSymbol(name, normalizedPath + "#" + name, "INTERFACE", lineNum, lineNum, rawSig, 1));
                if (iface.group(2) != null) {
                    for (String ext : iface.group(2).split(",")) {
                        String id = simpleIdent(ext);
                        if (!id.isBlank()) deps.add(new JavaAstAnalyzer.RawDependency(primaryNode, id, "EXTENDS"));
                    }
                }
                continue;
            }

            Matcher typeAlias = TYPE_ALIAS_DECL.matcher(trimmed);
            if (typeAlias.find()) {
                String name = typeAlias.group(1);
                symbols.add(new JavaAstAnalyzer.ParsedSymbol(name, normalizedPath + "#" + name, "TYPE_ALIAS", lineNum, lineNum, rawSig, 1));
                continue;
            }

            Matcher enumDecl = ENUM_DECL.matcher(trimmed);
            if (enumDecl.find()) {
                String name = enumDecl.group(1);
                symbols.add(new JavaAstAnalyzer.ParsedSymbol(name, normalizedPath + "#" + name, "ENUM", lineNum, lineNum, rawSig, 1));
                continue;
            }

            Matcher fn = FUNC_DECL.matcher(trimmed);
            if (fn.find()) {
                String name = fn.group(1);
                symbols.add(new JavaAstAnalyzer.ParsedSymbol(name, normalizedPath + "#" + name, "FUNCTION", lineNum, lineNum, rawSig, 1));
                continue;
            }

            Matcher arrow = CONST_ARROW_DECL.matcher(trimmed);
            if (arrow.find()) {
                String name = arrow.group(1);
                symbols.add(new JavaAstAnalyzer.ParsedSymbol(name, normalizedPath + "#" + name, "FUNCTION", lineNum, lineNum, rawSig, 1));
            }
        }

        if (symbols.isEmpty()) {
            symbols.add(new JavaAstAnalyzer.ParsedSymbol(
                primaryNode, normalizedPath, "MODULE", 1, Math.max(1, rawLines.length), normalizedPath, complexity
            ));
        }

        return new JavaAstAnalyzer.AstResult(primaryNode, complexity, symbols, new ArrayList<>(deps));
    }

    private void addImportDependency(
        Set<JavaAstAnalyzer.RawDependency> deps,
        String sourceNode,
        String sourceFilePath,
        String importSpec,
        boolean isTestFile
    ) {
        if (importSpec == null || importSpec.isBlank()) return;
        // Skip external npm packages; track relative module edges cleanly
        if (!importSpec.startsWith(".") && !importSpec.startsWith("/")) {
            return;
        }
        String resolvedTarget = resolveRelativeImportName(sourceFilePath, importSpec);
        if (!resolvedTarget.isBlank() && !resolvedTarget.equals(sourceNode)) {
            deps.add(new JavaAstAnalyzer.RawDependency(sourceNode, resolvedTarget, isTestFile ? "TESTS" : "IMPORTS"));
        }
    }

    private String resolveRelativeImportName(String sourceFilePath, String importSpec) {
        String cleaned = importSpec.replace('\\', '/');
        if (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        String base = cleaned.contains("/") ? cleaned.substring(cleaned.lastIndexOf('/') + 1) : cleaned;
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    private String simpleIdent(String raw) {
        String cleaned = raw.trim().replaceAll("<.*>$", "").replaceAll("\\{.*", "").trim();
        if (cleaned.contains(".")) {
            cleaned = cleaned.substring(cleaned.lastIndexOf('.') + 1);
        }
        return cleaned;
    }

    /**
     * Lexical pass removing // and /* *\/ comments while respecting string/template literals and preserving newlines.
     */
    String stripCommentsPreservingLines(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int n = src.length();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inTemplate = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < n; i++) {
            char c = src.charAt(i);
            char next = (i + 1 < n) ? src.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    out.append('\n');
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                } else if (c == '\n') {
                    out.append('\n');
                }
                continue;
            }
            if (inSingle) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    out.append(src.charAt(++i));
                } else if (c == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    out.append(src.charAt(++i));
                } else if (c == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (inTemplate) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    out.append(src.charAt(++i));
                } else if (c == '`') {
                    inTemplate = false;
                }
                continue;
            }

            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                out.append(c);
            } else if (c == '"') {
                inDouble = true;
                out.append(c);
            } else if (c == '`') {
                inTemplate = true;
                out.append(c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Replaces string/template literal interiors with spaces while keeping newlines so declarations and
     * control-flow keywords inside strings never produce false positives.
     */
    String stripStringsPreservingLines(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int n = src.length();
        char quote = 0;

        for (int i = 0; i < n; i++) {
            char c = src.charAt(i);
            if (quote != 0) {
                if (c == '\\' && i + 1 < n) {
                    i++;
                    continue;
                }
                if (c == quote) {
                    quote = 0;
                    out.append(' ');
                } else if (c == '\n') {
                    out.append('\n');
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
