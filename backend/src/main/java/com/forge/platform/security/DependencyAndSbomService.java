package com.forge.platform.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.repository.RepositoryEntity;
import com.forge.platform.repository.SourceFileEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Software Composition Analysis (SCA) & CycloneDX 1.5 SBOM Generator.
 * Parses Maven pom.xml and npm package.json manifests from indexed repository files,
 * builds a CycloneDX 1.5 JSON SBOM with Package URLs (PURLs), and evaluates dependencies
 * against a deterministic offline CVE advisory catalog.
 */
@Service
public class DependencyAndSbomService {

    private static final Pattern MAVEN_DEP_BLOCK = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern XML_GROUP = Pattern.compile("<groupId>\\s*([^<\\s]+)\\s*</groupId>");
    private static final Pattern XML_ARTIFACT = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
    private static final Pattern XML_VERSION = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>");

    public record ParsedDependency(
        String ecosystem,
        String group,
        String name,
        String version,
        String purl,
        String manifestPath,
        int lineNumber
    ) {}

    private record AdvisoryRule(
        String ecosystem,
        String packageName,
        String fixedVersion,
        String cveId,
        String cweId,
        String severity,
        String title,
        String remediation
    ) {}

    private static final List<AdvisoryRule> KNOWN_ADVISORIES = List.of(
        new AdvisoryRule("maven", "log4j-core", "2.17.1", "CVE-2021-44228", "CWE-502", "CRITICAL",
            "Log4Shell remote code execution in Apache Log4j (CVE-2021-44228)",
            "Upgrade org.apache.logging.log4j:log4j-core to 2.17.1 or newer immediately."),
        new AdvisoryRule("maven", "spring-webmvc", "5.3.18", "CVE-2022-22965", "CWE-94", "CRITICAL",
            "Spring4Shell RCE vulnerability in spring-webmvc (CVE-2022-22965)",
            "Upgrade Spring Framework to >= 5.3.18 / 6.x."),
        new AdvisoryRule("maven", "jackson-databind", "2.13.4.2", "CVE-2022-42003", "CWE-502", "HIGH",
            "Resource exhaustion / unsafe deserialization in jackson-databind (CVE-2022-42003)",
            "Upgrade com.fasterxml.jackson.core:jackson-databind to >= 2.13.4.2 or 2.15+."),
        new AdvisoryRule("maven", "snakeyaml", "2.0", "CVE-2022-1471", "CWE-502", "CRITICAL",
            "Arbitrary constructor deserialization in SnakeYAML < 2.0 (CVE-2022-1471)",
            "Upgrade org.yaml:snakeyaml to >= 2.0 and use SafeConstructor."),
        new AdvisoryRule("npm", "lodash", "4.17.21", "CVE-2021-23337", "CWE-94", "HIGH",
            "Command injection & prototype pollution in lodash < 4.17.21 (CVE-2021-23337)",
            "Upgrade lodash to >= 4.17.21."),
        new AdvisoryRule("npm", "axios", "1.6.0", "CVE-2023-45857", "CWE-352", "MEDIUM",
            "Cross-site XSRF token exposure in axios < 1.6.0 (CVE-2023-45857)",
            "Upgrade axios to >= 1.6.0."),
        new AdvisoryRule("npm", "jsonwebtoken", "9.0.0", "CVE-2022-23529", "CWE-20", "HIGH",
            "Improper secret validation in jsonwebtoken < 9.0.0 (CVE-2022-23529)",
            "Upgrade jsonwebtoken to >= 9.0.0."),
        new AdvisoryRule("npm", "minimist", "1.2.6", "CVE-2021-44906", "CWE-1321", "CRITICAL",
            "Prototype pollution in minimist < 1.2.6 (CVE-2021-44906)",
            "Upgrade minimist to >= 1.2.6.")
    );

    private final ObjectMapper objectMapper;

    public DependencyAndSbomService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ParsedDependency> extractDependencies(List<SourceFileEntity> files) {
        List<ParsedDependency> deps = new ArrayList<>();
        for (SourceFileEntity sf : files) {
            String lower = sf.getFilePath().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (lower.endsWith("pom.xml")) {
                deps.addAll(parseMavenPom(sf.getFilePath(), sf.getContent()));
            } else if (lower.endsWith("package.json")) {
                deps.addAll(parsePackageJson(sf.getFilePath(), sf.getContent()));
            }
        }
        return deps;
    }

    public List<SecurityScannerEngine.DetectedSecurityIssue> scanDependencies(List<SourceFileEntity> files) {
        List<ParsedDependency> deps = extractDependencies(files);
        List<SecurityScannerEngine.DetectedSecurityIssue> issues = new ArrayList<>();

        for (ParsedDependency dep : deps) {
            String cleanedVer = cleanSemver(dep.version());
            if (cleanedVer.isBlank()) continue;

            for (AdvisoryRule rule : KNOWN_ADVISORIES) {
                if (rule.ecosystem().equalsIgnoreCase(dep.ecosystem())
                    && rule.packageName().equalsIgnoreCase(dep.name())
                    && compareVersions(cleanedVer, rule.fixedVersion()) < 0) {
                    issues.add(new SecurityScannerEngine.DetectedSecurityIssue(
                        "SCA_ENGINE",
                        "SCA-" + rule.cveId(),
                        rule.cweId(),
                        rule.severity(),
                        rule.title(),
                        dep.manifestPath(),
                        dep.lineNumber(),
                        dep.purl() + " (fixed in >= " + rule.fixedVersion() + ")",
                        rule.remediation()
                    ));
                }
            }
        }
        return issues;
    }

    /**
     * Generates a valid CycloneDX 1.5 JSON Software Bill of Materials (SBOM).
     */
    public Map<String, Object> generateCycloneDxSbom(RepositoryEntity repo, List<SourceFileEntity> files) {
        List<ParsedDependency> deps = extractDependencies(files);
        List<Map<String, Object>> components = new ArrayList<>();
        for (ParsedDependency d : deps) {
            Map<String, Object> comp = new LinkedHashMap<>();
            comp.put("type", "library");
            if (d.group() != null && !d.group().isBlank()) {
                comp.put("group", d.group());
            }
            comp.put("name", d.name());
            comp.put("version", d.version());
            comp.put("purl", d.purl());
            comp.put("properties", List.of(
                Map.of("name", "forge:manifest", "value", d.manifestPath()),
                Map.of("name", "forge:ecosystem", "value", d.ecosystem())
            ));
            components.add(comp);
        }

        Map<String, Object> sbom = new LinkedHashMap<>();
        sbom.put("bomFormat", "CycloneDX");
        sbom.put("specVersion", "1.5");
        sbom.put("serialNumber", "urn:uuid:" + UUID.randomUUID());
        sbom.put("version", 1);
        sbom.put("metadata", Map.of(
            "timestamp", Instant.now().toString(),
            "tools", List.of(Map.of("vendor", "FORGE", "name", "forge-sca-sbom-engine", "version", "2.0.0")),
            "component", Map.of(
                "type", "application",
                "name", repo.getFullName(),
                "version", repo.getDefaultBranch()
            )
        ));
        sbom.put("components", components);
        return sbom;
    }

    List<ParsedDependency> parseMavenPom(String filePath, String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<ParsedDependency> out = new ArrayList<>();
        Matcher block = MAVEN_DEP_BLOCK.matcher(xml);
        while (block.find()) {
            String snippet = block.group(1);
            Matcher gm = XML_GROUP.matcher(snippet);
            Matcher am = XML_ARTIFACT.matcher(snippet);
            Matcher vm = XML_VERSION.matcher(snippet);
            if (gm.find() && am.find()) {
                String group = gm.group(1).trim();
                String artifact = am.group(1).trim();
                String version = vm.find() ? vm.group(1).trim() : "managed";
                int line = countNewlinesBefore(xml, block.start()) + 1;
                String purl = "pkg:maven/" + group + "/" + artifact + "@" + version;
                out.add(new ParsedDependency("maven", group, artifact, version, purl, filePath, line));
            }
        }
        return out;
    }

    List<ParsedDependency> parsePackageJson(String filePath, String json) {
        if (json == null || json.isBlank()) return List.of();
        List<ParsedDependency> out = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            for (String section : List.of("dependencies", "devDependencies")) {
                JsonNode depsNode = root.path(section);
                if (depsNode.isObject()) {
                    for (var entry : depsNode.properties()) {
                        String pkg = entry.getKey();
                        String ver = entry.getValue().asText("");
                        String purl = "pkg:npm/" + pkg.replace("@", "%40") + "@" + cleanSemver(ver);
                        out.add(new ParsedDependency("npm", "", pkg, ver, purl, filePath, 1));
                    }
                }
            }
        } catch (Exception ignored) {
            // malformed package.json ignored gracefully
        }
        return out;
    }

    static String cleanSemver(String raw) {
        if (raw == null) return "";
        String cleaned = raw.trim().replaceFirst("^[\\^~>=<v\\s]+", "");
        if (cleaned.startsWith("${") || cleaned.equals("managed")) return "";
        int dash = cleaned.indexOf('-');
        return dash > 0 ? cleaned.substring(0, dash) : cleaned;
    }

    static int compareVersions(String v1, String v2) {
        String[] p1 = v1.split("\\.");
        String[] p2 = v2.split("\\.");
        int len = Math.max(p1.length, p2.length);
        for (int i = 0; i < len; i++) {
            int n1 = i < p1.length ? parseIntSafe(p1[i]) : 0;
            int n2 = i < p2.length ? parseIntSafe(p2[i]) : 0;
            if (n1 != n2) return Integer.compare(n1, n2);
        }
        return 0;
    }

    private static int parseIntSafe(String part) {
        try {
            return Integer.parseInt(part.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private int countNewlinesBefore(String text, int offset) {
        int count = 0;
        for (int i = 0; i < Math.min(offset, text.length()); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }
}
