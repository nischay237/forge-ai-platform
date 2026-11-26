package com.forge.platform.review;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic API Compatibility Analyzer (Part 4C).
 * Inspects PR diffs across OpenAPI/Swagger specifications and Spring MVC controllers/DTOs
 * to identify breaking contract removals, HTTP method changes, removed response fields,
 * and newly required request parameters/properties.
 */
@Component
public class ApiCompatibilityAnalyzer {

    private static final Pattern OPENAPI_PATH = Pattern.compile("^\\s{0,4}(/[A-Za-z0-9_./{}-]+)\\s*:");
    private static final Pattern OPENAPI_METHOD = Pattern.compile("^\\s{2,8}(get|post|put|patch|delete|head|options)\\s*:");
    private static final Pattern SPRING_MAPPING = Pattern.compile(
        "@(GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping|RequestMapping)\\s*(?:\\(([^)]*)\\))?"
    );
    private static final Pattern SPRING_REQUIRED_PARAM = Pattern.compile(
        "@(RequestParam|RequestHeader)\\s*(?:\\(([^)]*)\\))?"
    );
    private static final Pattern JAVA_FIELD_OR_RECORD_COMP = Pattern.compile(
        "(?:private|public|protected)?\\s*(?:final\\s+)?([A-Za-z0-9_<>\\[\\],\\s]+?)\\s+([a-z][A-Za-z0-9_]*)\\s*[;,)]"
    );

    public List<AiReviewEngine.FindingProposal> analyze(List<UnifiedDiffParser.ParsedDiffFile> files) {
        List<AiReviewEngine.FindingProposal> findings = new ArrayList<>();
        for (UnifiedDiffParser.ParsedDiffFile file : files) {
            String lower = file.filePath().toLowerCase(Locale.ROOT);
            if (isOpenApiSpec(lower, file)) {
                analyzeOpenApiDiff(file, findings);
            } else if (lower.endsWith(".java")) {
                analyzeSpringContractDiff(file, findings);
            }
        }
        return findings;
    }

    private boolean isOpenApiSpec(String lowerPath, UnifiedDiffParser.ParsedDiffFile file) {
        boolean nameMatches = lowerPath.contains("openapi") || lowerPath.contains("swagger")
            || lowerPath.endsWith(".oas.yml") || lowerPath.endsWith(".oas.yaml");
        if (nameMatches && (lowerPath.endsWith(".yml") || lowerPath.endsWith(".yaml") || lowerPath.endsWith(".json"))) {
            return true;
        }
        String combined = file.originalContent() + "\n" + file.modifiedContent();
        return (lowerPath.endsWith(".yml") || lowerPath.endsWith(".yaml"))
            && (combined.contains("openapi:") || combined.contains("swagger:"))
            && combined.contains("paths:");
    }

    private void analyzeOpenApiDiff(UnifiedDiffParser.ParsedDiffFile file, List<AiReviewEngine.FindingProposal> findings) {
        Set<String> addedPaths = new LinkedHashSet<>();
        Set<String> addedMethods = new LinkedHashSet<>();
        for (var line : file.addedLines()) {
            Matcher pm = OPENAPI_PATH.matcher(line.content());
            if (pm.find()) addedPaths.add(pm.group(1).trim());
            Matcher mm = OPENAPI_METHOD.matcher(line.content());
            if (mm.find()) addedMethods.add(mm.group(1).trim().toLowerCase(Locale.ROOT));
        }

        for (var line : file.removedLines()) {
            String content = line.content();
            Matcher pm = OPENAPI_PATH.matcher(content);
            if (pm.find()) {
                String removedPath = pm.group(1).trim();
                if (!addedPaths.contains(removedPath)) {
                    findings.add(new AiReviewEngine.FindingProposal(
                        file.filePath(), line.lineNumber(), "API_COMPATIBILITY", "CRITICAL", 0.95,
                        "API-BREAKING-001",
                        "Breaking OpenAPI change: endpoint path removed (" + removedPath + ")",
                        "Removing an existing OpenAPI path breaks existing external API consumers and generated SDK clients. Deprecate the endpoint with `deprecated: true` or introduce a new versioned path instead.",
                        "- " + content.trim(),
                        "# Mark deprecated instead of removing in-place:\n  " + removedPath + ":\n    deprecated: true"
                    ));
                }
                continue;
            }

            Matcher mm = OPENAPI_METHOD.matcher(content);
            if (mm.find()) {
                String method = mm.group(1).trim().toLowerCase(Locale.ROOT);
                if (!addedMethods.contains(method)) {
                    findings.add(new AiReviewEngine.FindingProposal(
                        file.filePath(), line.lineNumber(), "API_COMPATIBILITY", "CRITICAL", 0.92,
                        "API-BREAKING-002",
                        "Breaking OpenAPI change: HTTP operation `" + method.toUpperCase(Locale.ROOT) + "` removed",
                        "Removing or renaming an HTTP verb on an active endpoint breaks backward compatibility for callers.",
                        "- " + content.trim(),
                        "# Keep existing HTTP method deprecated while migrating consumers to the replacement endpoint."
                    ));
                }
            }
        }

        // Detect newly added required fields in OpenAPI request schemas
        boolean inRequiredBlock = false;
        for (var line : file.addedLines()) {
            String trimmed = line.content().trim();
            if (trimmed.startsWith("required:")) {
                inRequiredBlock = true;
                continue;
            }
            if (inRequiredBlock && trimmed.startsWith("- ")) {
                String reqField = trimmed.substring(2).trim();
                boolean existedBefore = file.originalContent().contains("- " + reqField);
                if (!existedBefore && !reqField.isBlank()) {
                    findings.add(new AiReviewEngine.FindingProposal(
                        file.filePath(), line.lineNumber(), "API_COMPATIBILITY", "HIGH", 0.87,
                        "API-BREAKING-004",
                        "Potentially breaking OpenAPI change: newly required schema field `" + reqField + "`",
                        "Making a new field mandatory breaks existing clients sending payloads without `" + reqField + "` unless a server-side default is applied.",
                        "+ " + trimmed,
                        "# Make `" + reqField + "` optional initially with a default value before enforcing required status."
                    ));
                }
            } else if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                inRequiredBlock = false;
            }
        }
    }

    private void analyzeSpringContractDiff(UnifiedDiffParser.ParsedDiffFile file, List<AiReviewEngine.FindingProposal> findings) {
        String lowerPath = file.filePath().toLowerCase(Locale.ROOT);
        boolean isController = lowerPath.contains("controller")
            || file.originalContent().contains("@RestController")
            || file.modifiedContent().contains("@RestController")
            || file.originalContent().contains("Mapping(");

        if (isController) {
            Set<String> addedMappings = new LinkedHashSet<>();
            for (var line : file.addedLines()) {
                Matcher m = SPRING_MAPPING.matcher(line.content());
                if (m.find()) {
                    addedMappings.add(normalizeMappingSignature(m.group(1), m.group(2)));
                }
            }

            for (var line : file.removedLines()) {
                Matcher m = SPRING_MAPPING.matcher(line.content());
                if (m.find()) {
                    String sig = normalizeMappingSignature(m.group(1), m.group(2));
                    if (!addedMappings.contains(sig)) {
                        findings.add(new AiReviewEngine.FindingProposal(
                            file.filePath(), line.lineNumber(), "API_COMPATIBILITY", "CRITICAL", 0.93,
                            "API-BREAKING-005",
                            "Breaking REST endpoint change: removed or altered " + sig,
                            "Removing or changing a Spring REST controller mapping path or HTTP verb breaks deployed frontend/mobile clients and external integrations.",
                            "- " + line.content().trim(),
                            "// Keep the previous endpoint mapping with @Deprecated while introducing the new versioned route."
                        ));
                    }
                }
            }

            // Newly added required @RequestParam or @RequestHeader without defaultValue / required = false
            for (var line : file.addedLines()) {
                String text = line.content();
                Matcher pm = SPRING_REQUIRED_PARAM.matcher(text);
                if (pm.find()) {
                    String args = pm.group(2) != null ? pm.group(2) : "";
                    boolean optional = args.contains("required = false") || args.contains("required=false")
                        || args.contains("defaultValue");
                    boolean existedBefore = file.originalContent().contains(text.trim());
                    if (!optional && !existedBefore && !"ADDED".equals(file.changeType())) {
                        findings.add(new AiReviewEngine.FindingProposal(
                            file.filePath(), line.lineNumber(), "API_COMPATIBILITY", "HIGH", 0.85,
                            "API-BREAKING-007",
                            "Breaking request contract: newly required @" + pm.group(1) + " parameter",
                            "Adding a mandatory @" + pm.group(1) + " without `required = false` or `defaultValue` rejects requests from existing clients with HTTP 400.",
                            "+ " + text.trim(),
                            "@" + pm.group(1) + "(required = false, defaultValue = \"...\")"
                        ));
                    }
                }
            }
        }

        // Detect removed DTO / Response fields in *Dto.java / *Dtos.java / *Response.java / *Request.java
        boolean isDtoFile = lowerPath.contains("dto") || lowerPath.contains("response") || lowerPath.contains("request");
        if (isDtoFile && !"ADDED".equals(file.changeType())) {
            Set<String> addedFields = new LinkedHashSet<>();
            for (var line : file.addedLines()) {
                Matcher fm = JAVA_FIELD_OR_RECORD_COMP.matcher(line.content().trim());
                if (fm.find()) {
                    addedFields.add(fm.group(2));
                }
            }
            for (var line : file.removedLines()) {
                String trimmed = line.content().trim();
                if (trimmed.startsWith("//") || trimmed.startsWith("import ") || trimmed.contains("(") && !trimmed.contains(",")) {
                    continue;
                }
                Matcher fm = JAVA_FIELD_OR_RECORD_COMP.matcher(trimmed);
                if (fm.find()) {
                    String fieldName = fm.group(2);
                    if (!addedFields.contains(fieldName) && !Set.of("return", "throw", "new", "var").contains(fieldName)) {
                        findings.add(new AiReviewEngine.FindingProposal(
                            file.filePath(), line.lineNumber(), "API_COMPATIBILITY", "HIGH", 0.86,
                            "API-BREAKING-006",
                            "Potentially breaking DTO contract: field `" + fieldName + "` removed or renamed",
                            "Removing or renaming JSON contract field `" + fieldName + "` breaks deserialization for clients relying on this schema.",
                            "- " + trimmed,
                            "// Retain `" + fieldName + "` as deprecated during a transition release before removal."
                        ));
                    }
                }
            }
        }
    }

    private String normalizeMappingSignature(String annotation, String args) {
        String cleanArgs = args == null ? "" : args.replaceAll("\\s+", "");
        return "@" + annotation + "(" + cleanArgs + ")";
    }
}
