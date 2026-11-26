package com.forge.platform.security;

import com.forge.platform.common.SecretCryptoService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multi-layer deterministic Security Scanner Engine:
 * - Signature secret scanning with multi-match line redaction
 * - Shannon entropy secret detector with UUID/SHA/placeholder allowlists
 * - Static Application Security Testing (SAST) patterns
 * - IaC / Container / CI Workflow security checks (Dockerfile, Kubernetes YAML, GitHub Actions)
 * - Prompt injection firewall rules
 */
@Component
public class SecurityScannerEngine {

    private static final Pattern ENTROPY_ASSIGNMENT = Pattern.compile(
        "(?i)\\b(?:api[_-]?key|api[_-]?secret|auth[_-]?token|access[_-]?token|client[_-]?secret|signing[_-]?key|private[_-]?token|secret[_-]?key|bearer|webhook[_-]?secret)\\b\\s*[:=]\\s*['\"]([A-Za-z0-9+/=_.$!-]{24,120})['\"]"
    );
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern PURE_HEX_DIGEST = Pattern.compile("^[0-9a-fA-F]{32,128}$");

    public record DetectedSecurityIssue(
        String scanner,
        String ruleId,
        String cweId,
        String severity,
        String title,
        String filePath,
        int lineNumber,
        String maskedEvidence,
        String remediation
    ) {}

    private record RuleSpec(
        String scanner,
        String ruleId,
        String cweId,
        String severity,
        String title,
        Pattern pattern,
        boolean maskMatch,
        boolean redactEntireLine,
        String fileScope,
        String remediation
    ) {}

    private final SecretCryptoService cryptoService;
    private final List<RuleSpec> rules;

    public SecurityScannerEngine(SecretCryptoService cryptoService) {
        this.cryptoService = cryptoService;
        this.rules = List.of(
            // Secret Signatures
            new RuleSpec("SECRET_ENGINE", "SECRET-AWS-001", "CWE-798", "CRITICAL",
                "Hardcoded AWS Access Key ID detected",
                Pattern.compile("\\b(AKIA[0-9A-Z]{16})\\b"), true, false, "ALL",
                "Revoke the leaked AWS access key immediately in IAM, rotate credentials, and inject via environment/Vault."),
            new RuleSpec("SECRET_ENGINE", "SECRET-GH-002", "CWE-798", "CRITICAL",
                "Hardcoded GitHub Personal Access Token detected",
                Pattern.compile("\\b(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b"), true, false, "ALL",
                "Revoke the GitHub token immediately and switch to short-lived GitHub App installation tokens."),
            new RuleSpec("SECRET_ENGINE", "SECRET-STRIPE-003", "CWE-798", "CRITICAL",
                "Hardcoded Stripe Live API Secret detected",
                Pattern.compile("\\b(sk_live_[A-Za-z0-9]{16,})\\b"), true, false, "ALL",
                "Roll the Stripe secret key in the Stripe Dashboard and store secrets in AWS Secrets Manager / KMS."),
            new RuleSpec("SECRET_ENGINE", "SECRET-OPENAI-004", "CWE-798", "CRITICAL",
                "Hardcoded LLM / OpenAI API Key detected",
                Pattern.compile("\\b(sk-[A-Za-z0-9_-]{24,})\\b"), true, false, "ALL",
                "Rotate the LLM API key and reference it via ${FORGE_OPENAI_API_KEY} environment variable."),
            new RuleSpec("SECRET_ENGINE", "SECRET-PEM-005", "CWE-321", "CRITICAL",
                "Private cryptographic key block committed to source",
                Pattern.compile("-----BEGIN\\s+(?:RSA|EC|DSA|OPENSSH|ENCRYPTED|PRIVATE)\\s+PRIVATE KEY-----"), false, true, "ALL",
                "Remove the private key from Git history, rotate the certificate/keypair, and load from KMS/mounted secret volume."),
            new RuleSpec("SECRET_ENGINE", "SECRET-PASS-006", "CWE-798", "HIGH",
                "Hardcoded credential or password literal in code",
                Pattern.compile("(?i)(?:password|passwd|api_secret|db_pass|jwt_secret)\\s*[:=]\\s*['\"]([^'\"\\s]{8,})['\"]"), true, false, "ALL",
                "Externalize credentials via Spring externalized config or encrypted secret stores instead of literal strings."),
            new RuleSpec("SECRET_ENGINE", "SECRET-URI-008", "CWE-798", "CRITICAL",
                "Database or broker URI with embedded plaintext password",
                Pattern.compile("(?i)(?:postgres(?:ql)?|mysql|mongodb(?:\\+srv)?|redis|amqp)://[^:/\\s]+:([^@/\\s]{6,})@"), true, false, "ALL",
                "Remove inline credentials from connection URIs and supply username/password via environment secrets."),
            new RuleSpec("SECRET_ENGINE", "SECRET-GCP-009", "CWE-798", "CRITICAL",
                "Hardcoded Google Cloud API key detected",
                Pattern.compile("\\b(AIza[0-9A-Za-z_-]{35})\\b"), true, false, "ALL",
                "Restrict or revoke the API key in Google Cloud Console and inject via Secret Manager."),
            new RuleSpec("SECRET_ENGINE", "SECRET-SLACK-010", "CWE-798", "CRITICAL",
                "Hardcoded Slack API token detected",
                Pattern.compile("\\b(xox[baprs]-[0-9A-Za-z-]{16,})\\b"), true, false, "ALL",
                "Revoke the leaked Slack token immediately and store OAuth tokens in an encrypted vault."),

            // SAST Rules
            new RuleSpec("SAST_ENGINE", "SAST-SQLI-001", "CWE-89", "CRITICAL",
                "Potential SQL Injection via string concatenation in query",
                Pattern.compile("(?i)(?:SELECT|INSERT|UPDATE|DELETE)\\s+.{0,80}(?:\\+\\s*[a-zA-Z_][a-zA-Z0-9_.()]*|\\$\\{)"), false, false, "ALL",
                "Replace concatenated SQL strings with parameterized PreparedStatement / Spring Data JPA named parameters (:param)."),
            new RuleSpec("SAST_ENGINE", "SAST-SSRF-002", "CWE-918", "HIGH",
                "Potential Server-Side Request Forgery (SSRF) with dynamic URL",
                Pattern.compile("(?:new\\s+URL\\s*\\(|RestTemplate\\(\\)\\.(?:getForObject|exchange)\\s*\\(|HttpRequest\\.newBuilder\\s*\\(\\s*URI\\.create\\s*\\()"), false, false, "ALL",
                "Validate outbound URLs against a strict host allowlist, block RFC-1918/metadata IPs (169.254.169.254), and disable unsafe redirects."),
            new RuleSpec("SAST_ENGINE", "SAST-PATH-003", "CWE-22", "HIGH",
                "Potential Path Traversal in filesystem access",
                Pattern.compile("(?:Paths\\.get\\s*\\(|new\\s+File\\s*\\()[^;\\n]*(?:request|param|input|fileName|path)"), false, false, "ALL",
                "Canonicalize paths using target.normalize() and assert target.startsWith(allowedBaseDir) before reading or writing."),
            new RuleSpec("SAST_ENGINE", "SAST-DESER-004", "CWE-502", "CRITICAL",
                "Insecure Java deserialization via ObjectInputStream",
                Pattern.compile("new\\s+ObjectInputStream\\s*\\(|\\.readObject\\s*\\("), false, false, "ALL",
                "Avoid native Java serialization for untrusted payloads; use Jackson JSON with strict DTO classes and default typing disabled."),
            new RuleSpec("SAST_ENGINE", "SAST-CRYPTO-005", "CWE-328", "MEDIUM",
                "Weak cryptographic hash algorithm (MD5 / SHA-1)",
                Pattern.compile("MessageDigest\\.getInstance\\s*\\(\\s*\"(?:MD5|SHA-1|SHA1)\"\\s*\\)"), false, false, "ALL",
                "Use SHA-256/SHA-3 for integrity digests and BCrypt/Argon2 for password hashing."),
            new RuleSpec("SAST_ENGINE", "SAST-XSS-006", "CWE-79", "HIGH",
                "Unsafe raw HTML injection sink (dangerouslySetInnerHTML / innerHTML)",
                Pattern.compile("dangerouslySetInnerHTML|\\.innerHTML\\s*="), false, false, "ALL",
                "Render text via React JSX escaping or sanitize untrusted HTML strictly with DOMPurify before rendering."),

            // IaC / Container / CI Workflow Security Rules
            new RuleSpec("IAC_ENGINE", "IAC-DOCKER-001", "CWE-250", "HIGH",
                "Container runs as privileged root user or unpinned :latest image",
                Pattern.compile("^\\s*(?:USER\\s+root\\b|FROM\\s+[^\\s:]+:latest\\b)", Pattern.CASE_INSENSITIVE), false, false, "DOCKERFILE",
                "Pin base images to explicit digest/version tags and switch to a non-root USER in Dockerfile."),
            new RuleSpec("IAC_ENGINE", "IAC-DOCKER-002", "CWE-798", "CRITICAL",
                "Secret material embedded in Dockerfile ENV/ARG layer",
                Pattern.compile("^\\s*(?:ENV|ARG)\\s+[A-Z0-9_]*(?:SECRET|PASSWORD|TOKEN|API_KEY)\\s*=\\s*[^\\s$]{6,}", Pattern.CASE_INSENSITIVE), true, false, "DOCKERFILE",
                "Never bake secrets into image ENV/ARG layers; mount runtime secrets via BuildKit --mount=type=secret or orchestrator secrets."),
            new RuleSpec("IAC_ENGINE", "IAC-K8S-001", "CWE-250", "CRITICAL",
                "Kubernetes container configured with privileged escalation or root UID",
                Pattern.compile("^\\s*(?:privileged:\\s*true|allowPrivilegeEscalation:\\s*true|runAsUser:\\s*0)\\b"), false, false, "YAML",
                "Enforce restricted PodSecurityContext: set privileged=false, allowPrivilegeEscalation=false, and runAsNonRoot=true."),
            new RuleSpec("IAC_ENGINE", "IAC-GHA-001", "CWE-94", "CRITICAL",
                "GitHub Actions workflow uses untrusted PR context in shell script or pull_request_target",
                Pattern.compile("(?:pull_request_target\\s*:|\\$\\{\\{\\s*github\\.event\\.(?:pull_request|issue|comment)\\.(?:title|body))"), false, false, "WORKFLOW",
                "Avoid pull_request_target with untrusted checkouts and pass untrusted event fields through env variables instead of inline ${{ ... }} shell interpolation."),

            // Prompt Injection Firewall
            new RuleSpec("FORGE_GUARD", "FORGE-PROMPT-001", "CWE-77", "HIGH",
                "Prompt injection payload detected inside repository content or PR diff",
                Pattern.compile("(?i)(?:ignore\\s+(?:all\\s+)?previous\\s+instructions|disregard\\s+system\\s+prompt|auto[- ]?approve\\s+this\\s+pr|override\\s+security\\s+checks)"), false, false, "ALL",
                "Treat repository text and comments as untrusted input. Strip or reject prompt-injection directives before LLM evaluation.")
        );
    }

    public List<DetectedSecurityIssue> scanLines(String filePath, List<LineEntry> lines) {
        List<DetectedSecurityIssue> findings = new ArrayList<>();
        String normalizedPath = filePath == null ? "" : filePath.replace('\\', '/').toLowerCase(Locale.ROOT);

        for (LineEntry entry : lines) {
            String text = entry.content();
            if (text == null || text.isBlank()) continue;

            boolean matchedSecretSignature = false;

            for (RuleSpec rule : rules) {
                if (!matchesScope(rule.fileScope(), normalizedPath)) {
                    continue;
                }
                Matcher matcher = rule.pattern().matcher(text);
                if (!matcher.find()) {
                    continue;
                }

                String evidence;
                if (rule.redactEntireLine()) {
                    evidence = "[REDACTED PRIVATE KEY MATERIAL — CWE-321]";
                    matchedSecretSignature = true;
                } else if (rule.maskMatch()) {
                    // Redact ALL secret occurrences on the same line, never just the first!
                    String redacted = text;
                    boolean anyNonPlaceholder = false;
                    matcher.reset();
                    while (matcher.find()) {
                        String matched = matcher.groupCount() >= 1 && matcher.group(1) != null
                            ? matcher.group(1) : matcher.group(0);
                        if (isPlaceholder(matched)) {
                            continue;
                        }
                        anyNonPlaceholder = true;
                        redacted = redacted.replace(matched, cryptoService.maskSecret(matched));
                    }
                    if (!anyNonPlaceholder) {
                        continue;
                    }
                    evidence = redacted.trim();
                    matchedSecretSignature = true;
                } else {
                    evidence = text.trim();
                }

                if (evidence.length() > 260) {
                    evidence = evidence.substring(0, 260) + "...";
                }
                findings.add(new DetectedSecurityIssue(
                    rule.scanner(),
                    rule.ruleId(),
                    rule.cweId(),
                    rule.severity(),
                    rule.title(),
                    filePath,
                    entry.lineNumber(),
                    evidence,
                    rule.remediation()
                ));
            }

            // Shannon Entropy Secret Detector for unlabeled high-entropy credentials
            if (!matchedSecretSignature) {
                Matcher em = ENTROPY_ASSIGNMENT.matcher(text);
                while (em.find()) {
                    String candidate = em.group(1);
                    if (isPlaceholder(candidate)
                        || UUID_PATTERN.matcher(candidate).matches()
                        || PURE_HEX_DIGEST.matcher(candidate).matches()) {
                        continue;
                    }
                    double entropy = shannonEntropy(candidate);
                    if (entropy >= 4.15) {
                        String maskedLine = text.replace(candidate, cryptoService.maskSecret(candidate)).trim();
                        if (maskedLine.length() > 260) maskedLine = maskedLine.substring(0, 260) + "...";
                        findings.add(new DetectedSecurityIssue(
                            "ENTROPY_ENGINE",
                            "SECRET-ENTROPY-007",
                            "CWE-798",
                            "HIGH",
                            "High-entropy secret literal detected (Shannon entropy " + String.format(Locale.ROOT, "%.2f", entropy) + " bits/char)",
                            filePath,
                            entry.lineNumber(),
                            maskedLine,
                            "Move high-entropy secret tokens out of source code into an environment variable or managed secret vault."
                        ));
                        break;
                    }
                }
            }
        }
        return findings;
    }

    public List<DetectedSecurityIssue> scanFullFile(String filePath, String content) {
        if (content == null || content.isBlank()) return List.of();
        String[] raw = content.split("\\R");
        List<LineEntry> entries = new ArrayList<>(raw.length);
        for (int i = 0; i < raw.length; i++) {
            entries.add(new LineEntry(i + 1, raw[i]));
        }
        return scanLines(filePath, entries);
    }

    public static double shannonEntropy(String input) {
        if (input == null || input.isEmpty()) return 0.0;
        Map<Character, Integer> freq = new HashMap<>();
        for (char c : input.toCharArray()) {
            freq.merge(c, 1, Integer::sum);
        }
        double entropy = 0.0;
        double len = input.length();
        for (int count : freq.values()) {
            double p = count / len;
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    private boolean matchesScope(String scope, String lowerPath) {
        return switch (scope) {
            case "DOCKERFILE" -> lowerPath.endsWith("dockerfile") || lowerPath.contains("/dockerfile");
            case "YAML" -> lowerPath.endsWith(".yml") || lowerPath.endsWith(".yaml");
            case "WORKFLOW" -> lowerPath.contains(".github/workflows/") && (lowerPath.endsWith(".yml") || lowerPath.endsWith(".yaml"));
            default -> true;
        };
    }

    private boolean isPlaceholder(String value) {
        if (value == null || value.isBlank()) return true;
        String lower = value.toLowerCase(Locale.ROOT);
        return value.startsWith("${")
            || lower.contains("placeholder")
            || lower.contains("replace_with")
            || lower.contains("example_only")
            || lower.equals("changeme")
            || lower.startsWith("****");
    }

    public record LineEntry(int lineNumber, String content) {}
}
