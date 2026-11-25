package com.forge.platform.security;

import com.forge.platform.common.SecretCryptoService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SecurityScannerEngine {

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
        String remediation
    ) {}

    private final SecretCryptoService cryptoService;
    private final List<RuleSpec> rules;

    public SecurityScannerEngine(SecretCryptoService cryptoService) {
        this.cryptoService = cryptoService;
        this.rules = List.of(
            // Gitleaks-grade Secret Signatures
            new RuleSpec("GITLEAKS_ENGINE", "SECRET-AWS-001", "CWE-798", "CRITICAL",
                "Hardcoded AWS Access Key ID detected",
                Pattern.compile("\\b(AKIA[0-9A-Z]{16})\\b"), true,
                "Revoke the leaked AWS access key immediately in IAM, rotate credentials, and inject via environment/Vault."),
            new RuleSpec("GITLEAKS_ENGINE", "SECRET-GH-002", "CWE-798", "CRITICAL",
                "Hardcoded GitHub Personal Access Token detected",
                Pattern.compile("\\b(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b"), true,
                "Revoke the GitHub token immediately and switch to short-lived GitHub App installation tokens."),
            new RuleSpec("GITLEAKS_ENGINE", "SECRET-STRIPE-003", "CWE-798", "CRITICAL",
                "Hardcoded Stripe Live API Secret detected",
                Pattern.compile("\\b(sk_live_[A-Za-z0-9]{16,})\\b"), true,
                "Roll the Stripe secret key in the Stripe Dashboard and store secrets in AWS Secrets Manager / KMS."),
            new RuleSpec("GITLEAKS_ENGINE", "SECRET-OPENAI-004", "CWE-798", "CRITICAL",
                "Hardcoded LLM / OpenAI API Key detected",
                Pattern.compile("\\b(sk-[A-Za-z0-9_-]{24,})\\b"), true,
                "Rotate the LLM API key and reference it via ${FORGE_OPENAI_API_KEY} environment variable."),
            new RuleSpec("GITLEAKS_ENGINE", "SECRET-PEM-005", "CWE-321", "CRITICAL",
                "Private cryptographic key block committed to source",
                Pattern.compile("-----BEGIN\\s+(?:RSA|EC|OPENSSH|PRIVATE)\\s+PRIVATE KEY-----"), false,
                "Remove the private key from Git history, rotate the certificate/keypair, and load from KMS/mounted secret volume."),
            new RuleSpec("GITLEAKS_ENGINE", "SECRET-PASS-006", "CWE-798", "HIGH",
                "Hardcoded credential or password literal in code",
                Pattern.compile("(?i)(?:password|passwd|api_secret|db_pass|jwt_secret)\\s*[:=]\\s*\"([^\"]{8,})\""), true,
                "Externalize credentials via Spring externalized config or encrypted secret stores instead of literal strings."),

            // Semgrep-grade SAST Rules
            new RuleSpec("SEMGREP_ENGINE", "SAST-SQLI-001", "CWE-89", "CRITICAL",
                "Potential SQL Injection via string concatenation in query",
                Pattern.compile("(?i)(?:SELECT|INSERT|UPDATE|DELETE)\\s+.{0,80}(?:\\+\\s*[a-zA-Z_][a-zA-Z0-9_.()]*|\\$\\{)"), false,
                "Replace concatenated SQL strings with parameterized PreparedStatement / Spring Data JPA named parameters (:param)."),
            new RuleSpec("SEMGREP_ENGINE", "SAST-SSRF-002", "CWE-918", "HIGH",
                "Potential Server-Side Request Forgery (SSRF) with dynamic URL",
                Pattern.compile("(?:new\\s+URL\\s*\\(|RestTemplate\\(\\)\\.(?:getForObject|exchange)\\s*\\(|HttpRequest\\.newBuilder\\s*\\(\\s*URI\\.create\\s*\\()"), false,
                "Validate outbound URLs against a strict host allowlist, block RFC-1918/metadata IPs (169.254.169.254), and disable unsafe redirects."),
            new RuleSpec("SEMGREP_ENGINE", "SAST-PATH-003", "CWE-22", "HIGH",
                "Potential Path Traversal in filesystem access",
                Pattern.compile("(?:Paths\\.get\\s*\\(|new\\s+File\\s*\\()[^;\\n]*(?:request|param|input|fileName|path)"), false,
                "Canonicalize paths using target.normalize() and assert target.startsWith(allowedBaseDir) before reading or writing."),
            new RuleSpec("SEMGREP_ENGINE", "SAST-DESER-004", "CWE-502", "CRITICAL",
                "Insecure Java deserialization via ObjectInputStream",
                Pattern.compile("new\\s+ObjectInputStream\\s*\\(|\\.readObject\\s*\\("), false,
                "Avoid native Java serialization for untrusted payloads; use Jackson JSON with strict DTO classes and default typing disabled."),
            new RuleSpec("SEMGREP_ENGINE", "SAST-CRYPTO-005", "CWE-328", "MEDIUM",
                "Weak cryptographic hash algorithm (MD5 / SHA-1)",
                Pattern.compile("MessageDigest\\.getInstance\\s*\\(\\s*\"(?:MD5|SHA-1|SHA1)\"\\s*\\)"), false,
                "Use SHA-256/SHA-3 for integrity digests and BCrypt/Argon2 for password hashing."),
            new RuleSpec("SEMGREP_ENGINE", "SAST-XSS-006", "CWE-79", "HIGH",
                "Unsafe raw HTML injection sink (dangerouslySetInnerHTML / innerHTML)",
                Pattern.compile("dangerouslySetInnerHTML|\\.innerHTML\\s*="), false,
                "Render text via React JSX escaping or sanitize untrusted HTML strictly with DOMPurify before rendering."),
            new RuleSpec("FORGE_GUARD", "FORGE-PROMPT-001", "CWE-77", "HIGH",
                "Prompt injection payload detected inside repository content or PR diff",
                Pattern.compile("(?i)(?:ignore\\s+(?:all\\s+)?previous\\s+instructions|disregard\\s+system\\s+prompt|auto[- ]?approve\\s+this\\s+pr|override\\s+security\\s+checks)"), false,
                "Treat repository text and comments as untrusted input. Strip or reject prompt-injection directives before LLM evaluation.")
        );
    }

    public List<DetectedSecurityIssue> scanLines(String filePath, List<LineEntry> lines) {
        List<DetectedSecurityIssue> findings = new ArrayList<>();
        for (LineEntry entry : lines) {
            String text = entry.content();
            if (text == null || text.isBlank()) continue;

            for (RuleSpec rule : rules) {
                Matcher matcher = rule.pattern().matcher(text);
                if (matcher.find()) {
                    String evidence;
                    if (rule.maskMatch()) {
                        String matched = matcher.groupCount() >= 1 && matcher.group(1) != null
                            ? matcher.group(1) : matcher.group(0);
                        // Skip obvious placeholder/env vars
                        if (matched.startsWith("${") || matched.equalsIgnoreCase("changeme") || matched.contains("placeholder")) {
                            continue;
                        }
                        evidence = text.replace(matched, cryptoService.maskSecret(matched)).trim();
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

    public record LineEntry(int lineNumber, String content) {}
}
