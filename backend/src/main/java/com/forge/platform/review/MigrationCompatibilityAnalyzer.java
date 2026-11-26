package com.forge.platform.review;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic Database Migration Compatibility Analyzer (Part 4D).
 * Audits SQL migrations for destructive schema changes, Flyway history tampering,
 * table-locking DDL operations, and missing expand-and-contract safety patterns.
 */
@Component
public class MigrationCompatibilityAnalyzer {

    private static final Pattern VERSIONED_FLYWAY_FILE = Pattern.compile("(^|/)v\\d+__.*\\.sql$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_TABLE_OR_TRUNCATE = Pattern.compile("\\b(DROP\\s+TABLE|TRUNCATE\\s+(?:TABLE\\s+)?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_COLUMN = Pattern.compile("\\bDROP\\s+COLUMN\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_COLUMN_TYPE = Pattern.compile("\\bALTER\\s+COLUMN\\s+[A-Za-z0-9_\"`]+\\s+(?:SET\\s+DATA\\s+)?TYPE\\b|\\bMODIFY\\s+COLUMN\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADD_NOT_NULL_COL = Pattern.compile("\\bADD\\s+(?:COLUMN\\s+)?[A-Za-z0-9_\"`]+\\s+[^;]*\\bNOT\\s+NULL\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_INDEX = Pattern.compile("\\bCREATE\\s+(?:UNIQUE\\s+)?INDEX\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RENAME_TABLE_OR_COL = Pattern.compile("\\bRENAME\\s+(?:TO|COLUMN)\\b", Pattern.CASE_INSENSITIVE);

    public List<AiReviewEngine.FindingProposal> analyze(List<UnifiedDiffParser.ParsedDiffFile> files) {
        List<AiReviewEngine.FindingProposal> findings = new ArrayList<>();
        for (UnifiedDiffParser.ParsedDiffFile file : files) {
            String path = file.filePath().replace('\\', '/');
            String lower = path.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".sql")) {
                continue;
            }

            // 1. Modifying an existing versioned Flyway migration breaks checksum validation across environments
            if ("MODIFIED".equals(file.changeType()) && VERSIONED_FLYWAY_FILE.matcher(path).find()
                && !file.removedLines().isEmpty()) {
                findings.add(new AiReviewEngine.FindingProposal(
                    file.filePath(),
                    file.addedLines().isEmpty() ? 1 : file.addedLines().get(0).lineNumber(),
                    "DB_MIGRATION",
                    "CRITICAL",
                    0.97,
                    "DB-MIGRATION-001",
                    "Immutable Flyway migration modified in-place",
                    "Editing an already-applied versioned migration (`V*__*.sql`) causes FlywayValidateException checksum mismatches on staging and production deployments. Always create a new forward migration version.",
                    "Modified existing versioned migration: " + file.filePath(),
                    "-- Revert edits to " + file.filePath() + " and create a new V<next>__<description>.sql migration instead."
                ));
            }

            // 2. Line-by-line DDL hazard detection on added SQL lines
            for (var line : file.addedLines()) {
                String sql = stripSqlLineComment(line.content()).trim();
                if (sql.isEmpty()) continue;

                if (DROP_TABLE_OR_TRUNCATE.matcher(sql).find()) {
                    findings.add(new AiReviewEngine.FindingProposal(
                        file.filePath(), line.lineNumber(), "DB_MIGRATION", "CRITICAL", 0.96,
                        "DB-MIGRATION-002",
                        "Destructive SQL operation: DROP TABLE / TRUNCATE detected",
                        "Dropping or truncating a table causes irreversible data loss and immediately crashes running application pods still referencing the relation. Use a multi-step expand-and-contract deprecation window.",
                        sql,
                        "-- Step 1: Stop reading/writing table in app release N.\n-- Step 2: Archive/backup data.\n-- Step 3: Drop table only in release N+2 after verifying zero queries."
                    ));
                }

                if (DROP_COLUMN.matcher(sql).find()) {
                    findings.add(new AiReviewEngine.FindingProposal(
                        file.filePath(), line.lineNumber(), "DB_MIGRATION", "HIGH", 0.94,
                        "DB-MIGRATION-003",
                        "Backward-incompatible schema change: DROP COLUMN",
                        "Dropping a column breaks rolling zero-downtime deployments where older application replicas still SELECT/INSERT that column. Follow expand-and-contract: remove application reads first, then drop column in a follow-up release.",
                        sql,
                        "-- Expand-and-contract:\n-- Release 1: Make column nullable & stop referencing it in JPA/SQL.\n-- Release 2: Execute ALTER TABLE ... DROP COLUMN after rollout completes."
                    ));
                }

                if (ALTER_COLUMN_TYPE.matcher(sql).find()) {
                    findings.add(new AiReviewEngine.FindingProposal(
                        file.filePath(), line.lineNumber(), "DB_MIGRATION", "HIGH", 0.91,
                        "DB-MIGRATION-004",
                        "High-risk column type change (table rewrite & lock hazard)",
                        "Changing column data types in PostgreSQL often acquires an ACCESS EXCLUSIVE lock for a full table rewrite and may fail on existing rows. Add a new column, dual-write/backfill, swap reads, then retire the old column.",
                        sql,
                        "-- Expand-and-contract: ADD COLUMN new_col <new_type>, backfill in batches, switch application reads, then drop legacy column."
                    ));
                }

                if (ADD_NOT_NULL_COL.matcher(sql).find() && !sql.toUpperCase(Locale.ROOT).contains("DEFAULT")) {
                    findings.add(new AiReviewEngine.FindingProposal(
                        file.filePath(), line.lineNumber(), "DB_MIGRATION", "HIGH", 0.93,
                        "DB-MIGRATION-005",
                        "ADD COLUMN NOT NULL without DEFAULT fails on non-empty tables",
                        "Adding a NOT NULL column without a DEFAULT value fails immediately if the table already contains rows and breaks older replicas inserting without the new column.",
                        sql,
                        "-- Safe pattern: ADD COLUMN ... DEFAULT <safe_value> NOT NULL, or add nullable -> backfill -> ADD CONSTRAINT NOT VALID -> VALIDATE CONSTRAINT."
                    ));
                }

                if (CREATE_INDEX.matcher(sql).find() && !sql.toUpperCase(Locale.ROOT).contains("CONCURRENTLY")) {
                    findings.add(new AiReviewEngine.FindingProposal(
                        file.filePath(), line.lineNumber(), "DB_MIGRATION", "MEDIUM", 0.88,
                        "DB-MIGRATION-006",
                        "Blocking CREATE INDEX without CONCURRENTLY",
                        "Standard CREATE INDEX acquires a SHARE lock blocking concurrent INSERT/UPDATE/DELETE operations for the entire index build duration on large PostgreSQL tables.",
                        sql,
                        sql.replaceFirst("(?i)CREATE\\s+(UNIQUE\\s+)?INDEX", "CREATE $1INDEX CONCURRENTLY")
                    ));
                }

                if (RENAME_TABLE_OR_COL.matcher(sql).find()) {
                    findings.add(new AiReviewEngine.FindingProposal(
                        file.filePath(), line.lineNumber(), "DB_MIGRATION", "HIGH", 0.90,
                        "DB-MIGRATION-007",
                        "Zero-downtime hazard: in-place RENAME TABLE/COLUMN",
                        "Renaming a table or column breaks live application instances during rolling upgrades. Prefer expand-and-contract (dual-write new name, migrate readers, drop old name).",
                        sql,
                        "-- Prefer adding new column/view alias + dual-write before retiring the old identifier."
                    ));
                }
            }
        }
        return findings;
    }

    private String stripSqlLineComment(String line) {
        int idx = line.indexOf("--");
        return idx >= 0 ? line.substring(0, idx) : line;
    }
}
