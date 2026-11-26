package com.forge.platform.review;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiAndMigrationCompatibilityTest {

    private final UnifiedDiffParser diffParser = new UnifiedDiffParser();
    private final ApiCompatibilityAnalyzer apiAnalyzer = new ApiCompatibilityAnalyzer();
    private final MigrationCompatibilityAnalyzer migrationAnalyzer = new MigrationCompatibilityAnalyzer();

    @Test
    void detectsBreakingOpenApiAndSpringControllerChanges() {
        String diff = """
            diff --git a/openapi.yaml b/openapi.yaml
            --- a/openapi.yaml
            +++ b/openapi.yaml
            @@ -10,8 +10,6 @@ paths:
            -  /api/v1/legacy-payouts:
            -    post:
            -      summary: legacy payout
            +  /api/v2/payouts:
            +    post:
            +      required:
            +        - idempotencyKey
            diff --git a/src/main/java/com/acme/TransferController.java b/src/main/java/com/acme/TransferController.java
            --- a/src/main/java/com/acme/TransferController.java
            +++ b/src/main/java/com/acme/TransferController.java
            @@ -12,4 +12,5 @@ public class TransferController {
            -    @GetMapping("/v1/transfers/{id}")
            +    @GetMapping("/v2/transfers/{id}")
            +    public String get(@RequestParam String region) { return ""; }
            diff --git a/src/main/java/com/acme/TransferResponseDto.java b/src/main/java/com/acme/TransferResponseDto.java
            --- a/src/main/java/com/acme/TransferResponseDto.java
            +++ b/src/main/java/com/acme/TransferResponseDto.java
            @@ -3,3 +3,2 @@ public record TransferResponseDto(
            -    String legacyReferenceId,
                 String status
             ) {}
            """;

        var files = diffParser.parse(diff);
        var findings = apiAnalyzer.analyze(files);

        assertThat(findings)
            .extracting(AiReviewEngine.FindingProposal::ruleId)
            .contains("API-BREAKING-001", "API-BREAKING-004", "API-BREAKING-005", "API-BREAKING-006", "API-BREAKING-007");
    }

    @Test
    void detectsDestructiveSqlMigrationsAndFlywayHistoryTampering() {
        String diff = """
            diff --git a/backend/src/main/resources/db/migration/V1__init.sql b/backend/src/main/resources/db/migration/V1__init.sql
            --- a/backend/src/main/resources/db/migration/V1__init.sql
            +++ b/backend/src/main/resources/db/migration/V1__init.sql
            @@ -1,3 +1,3 @@
            -CREATE TABLE old_ledger (id VARCHAR(36));
            +CREATE TABLE new_ledger (id VARCHAR(36));
            diff --git a/backend/src/main/resources/db/migration/V6__cleanup.sql b/backend/src/main/resources/db/migration/V6__cleanup.sql
            new file mode 100644
            --- /dev/null
            +++ b/backend/src/main/resources/db/migration/V6__cleanup.sql
            @@ -0,0 +1,5 @@
            +DROP TABLE legacy_audit;
            +ALTER TABLE users DROP COLUMN middle_name;
            +ALTER TABLE invoices ALTER COLUMN amount TYPE INTEGER;
            +ALTER TABLE invoices ADD COLUMN tax_code VARCHAR(16) NOT NULL;
            +CREATE INDEX idx_invoices_status ON invoices(status);
            """;

        var files = diffParser.parse(diff);
        var findings = migrationAnalyzer.analyze(files);

        assertThat(findings)
            .extracting(AiReviewEngine.FindingProposal::ruleId)
            .contains(
                "DB-MIGRATION-001",
                "DB-MIGRATION-002",
                "DB-MIGRATION-003",
                "DB-MIGRATION-004",
                "DB-MIGRATION-005",
                "DB-MIGRATION-006"
            );
    }

    @Test
    void unifiedDiffParserIgnoresGitExtendedHeadersAndDetectsRenamesAndDeletes() {
        String diff = """
            diff --git a/src/OldName.java b/src/NewName.java
            similarity index 92%
            rename from src/OldName.java
            rename to src/NewName.java
            --- a/src/OldName.java
            +++ b/src/NewName.java
            @@ -1,2 +1,2 @@ public class NewName {
            -public class OldName {}
            +public class NewName {}
            \\ No newline at end of file
            diff --git a/src/Removed.java b/src/Removed.java
            deleted file mode 100644
            index 1234567..0000000
            --- a/src/Removed.java
            +++ /dev/null
            @@ -1,1 +0,0 @@
            -public class Removed {}
            """;

        List<UnifiedDiffParser.ParsedDiffFile> parsed = diffParser.parse(diff);
        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).changeType()).isEqualTo("RENAMED");
        assertThat(parsed.get(0).previousFilePath()).isEqualTo("src/OldName.java");
        assertThat(parsed.get(0).filePath()).isEqualTo("src/NewName.java");
        assertThat(parsed.get(0).modifiedContent()).doesNotContain("similarity index").doesNotContain("No newline");
        assertThat(parsed.get(1).changeType()).isEqualTo("DELETED");
        assertThat(parsed.get(1).filePath()).isEqualTo("src/Removed.java");
    }
}
