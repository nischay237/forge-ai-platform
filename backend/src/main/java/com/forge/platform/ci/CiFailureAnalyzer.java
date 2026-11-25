package com.forge.platform.ci;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class CiFailureAnalyzer {

    public record Diagnosis(
        String status,
        String failureCategory,
        String rootCauseSummary,
        String remediationSteps
    ) {}

    public Diagnosis diagnose(String declaredStatus, String rawLog) {
        String log = rawLog == null ? "" : rawLog;
        String lower = log.toLowerCase(Locale.ROOT);

        if ("PASSED".equalsIgnoreCase(declaredStatus) && !lower.contains("build failure") && !lower.contains("error:")) {
            return new Diagnosis(
                "PASSED",
                "NONE",
                "All build, static analysis, and automated test stages succeeded without regressions.",
                "No action required. Ready for deployment gate."
            );
        }

        if (lower.contains("outofmemoryerror") || lower.contains("heap space") || lower.contains("exit code 137")) {
            return new Diagnosis(
                "FAILED",
                "OUT_OF_MEMORY",
                "JVM or container worker exhausted heap memory during test execution or compilation (OOM / SIGKILL 137).",
                "1. Increase Maven/Surefire heap via MAVEN_OPTS=\"-Xmx2048m\".\n2. Check Testcontainers or large fixture caches for memory retention.\n3. Split heavy integration suites into parallel CI matrix shards."
            );
        }

        if (lower.contains("flyway") || lower.contains("checksum mismatch") || lower.contains("migration") && lower.contains("failed")) {
            return new Diagnosis(
                "FAILED",
                "MIGRATION_DRIFT",
                "Flyway detected a schema migration checksum mismatch or invalid DDL against PostgreSQL.",
                "1. Never edit an already-applied Flyway migration file.\n2. Revert changes to the historical V*__*.sql script and create a new forward migration version.\n3. Verify migration locally against Testcontainers PostgreSQL before pushing."
            );
        }

        if (lower.contains("cannot find symbol") || lower.contains("compilation error") || lower.contains("ts23") || lower.contains("tsc")) {
            return new Diagnosis(
                "FAILED",
                "COMPILATION_ERROR",
                "Type-checking or compiler failed due to unresolved symbol, breaking interface signature change, or missing import.",
                "1. Run FORGE Change Impact Analysis on recently modified interfaces/DTOs.\n2. Update all downstream callers to match the new method/type contract.\n3. Run `./mvnw test-compile` or `npm run typecheck` locally before pushing."
            );
        }

        if (lower.contains("assertionfailederror") || lower.contains("tests run:") && lower.contains("failures:")
            || lower.contains("expected:") || lower.contains("fail ") && lower.contains("vitest")) {
            return new Diagnosis(
                "FAILED",
                "TEST_FAILURE",
                "Automated unit or integration test assertion failed, indicating a behavioral regression or mismatched test fixture.",
                "1. Inspect the failing stack trace and compare expected vs actual values.\n2. Check recent PR diff hunks modifying business logic or transaction boundaries.\n3. Reproduce deterministically with `./mvnw test -Dtest=<FailingTestClass>`."
            );
        }

        if (lower.contains("timed out") || lower.contains("timeoutexception") || lower.contains("connection refused")) {
            return new Diagnosis(
                "FAILED",
                "INFRA_TIMEOUT",
                "Pipeline timed out waiting on an external service, Kafka broker, Redis socket, or flaky network dependency.",
                "1. Verify Docker/Testcontainers readiness probes and wait strategies.\n2. Add Resilience4j timeouts and avoid calling live external APIs in unit tests.\n3. Re-run job and monitor p99 container startup latency."
            );
        }

        if (lower.contains("gitleaks") || lower.contains("semgrep") || lower.contains("cve-") || lower.contains("critical vulnerability")) {
            return new Diagnosis(
                "FAILED",
                "SECURITY_GATE",
                "CI security gate blocked the pipeline due to a detected secret, high-severity SAST violation, or vulnerable dependency.",
                "1. Review findings in the FORGE Security Center.\n2. Rotate any exposed credential and apply parameterized/sanitized code fixes.\n3. Upgrade vulnerable library coordinates in pom.xml / package.json."
            );
        }

        if ("FAILED".equalsIgnoreCase(declaredStatus) || lower.contains("error") || lower.contains("exception") || lower.contains("failed")) {
            return new Diagnosis(
                "FAILED",
                "PIPELINE_ERROR",
                "Pipeline exited with a non-zero status. Error markers detected in build output.",
                "1. Inspect the highlighted error lines in the build log.\n2. Validate environment variables and pinned dependency versions.\n3. Re-run cleanly after applying the fix."
            );
        }

        return new Diagnosis(
            "PASSED",
            "NONE",
            "Pipeline completed cleanly with zero failing stages.",
            "No action required."
        );
    }
}
