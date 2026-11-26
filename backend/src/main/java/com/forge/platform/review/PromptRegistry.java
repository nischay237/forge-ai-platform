package com.forge.platform.review;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned Prompt Registry ensuring prompt changes are explicit, auditable,
 * and decoupled from deterministic merge risk policy logic.
 */
@Component
public class PromptRegistry {

    public record PromptTemplate(
        String version,
        String description,
        String systemPreamble
    ) {}

    private final Map<String, PromptTemplate> templates = new LinkedHashMap<>();

    public PromptRegistry() {
        register(new PromptTemplate(
            "forge-review-v2.4",
            "Strict evidence-grounded code defect review with randomized untrusted-data boundaries",
            "You are FORGE [forge-review-v2.4], a principal software engineer reviewing a pull request. "
                + "Deterministic analyzers own merge risk verdicts (PASS/WARN/BLOCK); your job is strictly "
                + "to identify genuine additional defects with concrete file/line evidence."
        ));
        register(new PromptTemplate(
            "forge-review-v2.5",
            "Change-impact focused review emphasizing API contracts, concurrency, and transactional safety",
            "You are FORGE [forge-review-v2.5], a principal distributed systems and security reviewer. "
                + "Focus on concurrency hazards, partial failure modes, API backward compatibility, "
                + "and data-integrity regressions grounded strictly in provided diff hunks."
        ));
    }

    private void register(PromptTemplate template) {
        templates.put(template.version(), template);
    }

    public PromptTemplate resolve(String version) {
        if (version != null && templates.containsKey(version.trim())) {
            return templates.get(version.trim());
        }
        return templates.get("forge-review-v2.4");
    }

    public List<PromptTemplate> listAll() {
        return List.copyOf(templates.values());
    }
}
