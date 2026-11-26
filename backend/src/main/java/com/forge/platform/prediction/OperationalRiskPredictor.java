package com.forge.platform.prediction;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic Baseline Operational Risk Predictor (Milestone 5).
 *
 * Strictly separates TWO distinct operational prediction categories:
 *   1. PRODUCTION_INCIDENT_RISK — whether the change touches critical services, contracts, DB schema,
 *      auth/authorization, external integrations, or modules with historical confirmed incidents.
 *      NEVER claims causation between a code change and an incident without evidence.
 *   2. CHANGE_FAILURE_RISK — whether a deployment containing this change is at elevated risk of
 *      deployment failure or rollback, explicitly distinguishing FAILED deployments vs ROLLED_BACK
 *      deployments, production incidents, and software defects.
 */
@Component
public class OperationalRiskPredictor {

    public static final String CATEGORY_INCIDENT = "PRODUCTION_INCIDENT_RISK";
    public static final String CATEGORY_CHANGE_FAILURE = "CHANGE_FAILURE_RISK";
    public static final String INCIDENT_RULE_VERSION = "forge-incident-baseline-v1.0";
    public static final String CHANGE_FAILURE_RULE_VERSION = "forge-change-failure-baseline-v1.0";

    public PredictionCandidate.CategoryPredictionDraft predictIncidentRisk(PrFeatureExtractor.PrFeatureVector fv) {
        List<PredictionCandidate.EvidenceDraft> evidence = new ArrayList<>();
        List<PredictionDtos.TargetRiskBreakdown> targets = new ArrayList<>();
        int score = 0;

        // 1. Historical confirmed incidents associated with overlapping service/modules
        if (!fv.matchingIncidents().isEmpty()) {
            long sev12 = fv.matchingIncidents().stream()
                .filter(i -> "SEV1".equalsIgnoreCase(i.getSeverity()) || "SEV2".equalsIgnoreCase(i.getSeverity()))
                .count();
            int incWeight = Math.min(30, fv.matchingIncidents().size() * 10 + (int) sev12 * 8);
            score += incWeight;
            Instant latestInc = fv.matchingIncidents().stream()
                .map(IncidentRecordEntity::getOccurredAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "HISTORICAL_INCIDENTS",
                "PREVIOUS_SERVICE_INCIDENTS",
                fv.serviceName(),
                fv.matchingIncidents().size() + " confirmed historical incident(s) (" + sev12
                    + " SEV1/SEV2) overlapped service/modules (" + fv.matchingIncidents().get(0).getTitle()
                    + "); historical association only — does not establish causation",
                incWeight,
                fv.matchingIncidents().size(),
                latestInc,
                "incident-overlap:" + fv.serviceName()
            ));
        }

        // 2. Database schema / migration risks
        if (fv.migrationRiskCount() > 0 || fv.touchesDatabaseMigration()) {
            int dbWeight = fv.migrationRiskCount() > 0 ? 28 : 14;
            score += dbWeight;
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "DATABASE_SCHEMA",
                "SCHEMA_MIGRATION_CHANGE",
                "migrationRisks=" + fv.migrationRiskCount(),
                fv.migrationRiskCount() > 0
                    ? fv.migrationRiskCount() + " destructive/risky SQL migration pattern(s) detected"
                    : "Modifies SQL database migration files requiring backward-compatible rollout validation",
                dbWeight,
                0,
                null,
                "db-migration:" + fv.reviewId()
            ));
        }

        // 3. API contract breaking changes
        if (fv.breakingApiCount() > 0) {
            int apiWeight = Math.min(26, fv.breakingApiCount() * 16);
            score += apiWeight;
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "API_COMPATIBILITY",
                "API_CONTRACT_CHANGE",
                "breakingApi=" + fv.breakingApiCount(),
                fv.breakingApiCount() + " breaking or incompatible API contract change(s) may disrupt downstream consumers",
                apiWeight,
                0,
                null,
                "api-compat:" + fv.reviewId()
            ));
        }

        // 4. Authentication & authorization surface changes
        if (fv.touchesAuthOrSecurity()) {
            int authWeight = fv.criticalSecurityCount() > 0 ? 24 : 14;
            score += authWeight;
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "AUTH_SECURITY_SURFACE",
                "AUTH_OR_ACCESS_CONTROL_CHANGE",
                "authSurface=true",
                "Modifies authentication, authorization, session, or cryptographic controls",
                authWeight,
                0,
                null,
                "auth-surface:" + fv.reviewId()
            ));
        }

        // 5. Critical request-path modules & external service integrations
        if (fv.touchesCriticalRequestPath()) {
            int critWeight = 10;
            score += critWeight;
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "CRITICAL_MODULES",
                "CRITICAL_REQUEST_PATH",
                String.join(", ", fv.affectedModules()),
                "Modifies critical request-handling/settlement/controller modules",
                critWeight,
                0,
                null,
                "critical-path:" + fv.reviewId()
            ));
        }
        if (fv.touchesExternalIntegration()) {
            int extWeight = 10;
            score += extWeight;
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "EXTERNAL_INTEGRATION",
                "EXTERNAL_SERVICE_DEPENDENCY",
                fv.serviceName(),
                "Touches external HTTP, webhook, broker, or third-party integration calls",
                extWeight,
                0,
                null,
                "external-int:" + fv.reviewId()
            ));
        }

        // 6. Dependency blast radius across modules/services
        if (fv.totalBlastRadius() >= 2) {
            int blastWeight = Math.min(18, fv.totalBlastRadius() * 3);
            score += blastWeight;
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "DEPENDENCY_GRAPH",
                "CROSS_MODULE_BLAST_RADIUS",
                fv.totalBlastRadius() + " impacted components",
                "Downstream dependency impact spans " + fv.totalBlastRadius() + " component(s) across "
                    + Math.max(1, fv.impactedModuleNames().size()) + " module(s)",
                blastWeight,
                0,
                null,
                "blast-radius:" + fv.reviewId()
            ));
        }

        int finalScore = Math.min(100, Math.max(0, score));
        boolean insufficientHistory = fv.totalRepoIncidents() == 0;
        String riskLevel = (insufficientHistory && evidence.isEmpty())
            ? "INSUFFICIENT_DATA"
            : BugRiskPredictor.toRiskLevel(finalScore);
        String confidence = assessConfidence(fv.totalRepoIncidents(), !fv.matchingIncidents().isEmpty(), !evidence.isEmpty());

        targets.add(new PredictionDtos.TargetRiskBreakdown(
            "SERVICE", fv.serviceName(), null, riskLevel, finalScore,
            "Service incident risk (" + fv.matchingIncidents().size() + " overlapping historical incidents)"
        ));
        for (String mod : fv.affectedModules()) {
            targets.add(new PredictionDtos.TargetRiskBreakdown(
                "MODULE", mod, null, riskLevel, finalScore,
                "Modified module evaluated for production incident exposure"
            ));
        }
        for (String impMod : fv.impactedModuleNames()) {
            if (!fv.affectedModules().contains(impMod)) {
                targets.add(new PredictionDtos.TargetRiskBreakdown(
                    "MODULE", impMod, null, BugRiskPredictor.toRiskLevel(Math.max(10, finalScore - 15)),
                    Math.max(10, finalScore - 15),
                    "Downstream impacted module via dependency graph"
                ));
            }
        }

        String explanation;
        if (insufficientHistory && evidence.isEmpty()) {
            explanation = PredictionDtos.INSUFFICIENT_DATA_MESSAGE
                + " No historical confirmed incidents recorded and no high-risk operational surface changes detected.";
        } else {
            String top = evidence.stream().limit(3).map(PredictionCandidate.EvidenceDraft::summary)
                .reduce((a, b) -> a + "; " + b).orElse("Low operational surface change");
            explanation = (insufficientHistory
                ? "Structural incident risk baseline (" + riskLevel + ", " + finalScore + "/100 — no prior incident history): "
                : riskLevel + " production incident risk (" + finalScore + "/100): ")
                + top + ". Note: FORGE never claims causal proof between a PR and an incident.";
        }

        String limitations = insufficientHistory
            ? PredictionDtos.INSUFFICIENT_DATA_MESSAGE
                + " Confirmed incident history is empty for this repository; score reflects static operational surface "
                + "signals only and cannot establish production incident probability or causation."
            : "Evaluated against " + fv.totalRepoIncidents() + " confirmed incident record(s). Overlapping module history "
                + "is correlational evidence only and never proves causation.";

        String action = switch (riskLevel) {
            case "CRITICAL", "HIGH" -> "Use staged/canary rollout, verify backward compatibility and rollback runbook, and monitor service SLOs immediately after deploy.";
            case "MEDIUM" -> "Verify contract/auth/integration behavior in staging and ensure alerting covers affected modules.";
            default -> "Standard deployment monitoring is appropriate.";
        };

        return new PredictionCandidate.CategoryPredictionDraft(
            CATEGORY_INCIDENT,
            finalScore,
            riskLevel,
            null,
            confidence,
            insufficientHistory,
            fv.totalRepoIncidents(),
            fv.dataFreshnessAt(),
            INCIDENT_RULE_VERSION,
            explanation,
            limitations,
            action,
            targets,
            evidence
        );
    }

    public PredictionCandidate.CategoryPredictionDraft predictChangeFailureRisk(PrFeatureExtractor.PrFeatureVector fv) {
        List<PredictionCandidate.EvidenceDraft> evidence = new ArrayList<>();
        List<PredictionDtos.TargetRiskBreakdown> targets = new ArrayList<>();
        int score = 0;
        int deploymentSamples = fv.serviceDeployments().size();

        // 1. Historical deployment failures (kept distinct from rollbacks)
        if (fv.serviceFailedDeploymentCount() > 0) {
            double failRate = deploymentSamples > 0 ? (double) fv.serviceFailedDeploymentCount() / deploymentSamples : 0.0;
            int failWeight = Math.min(28, (int) Math.round(failRate * 35) + (int) Math.min(12, fv.serviceFailedDeploymentCount() * 6));
            score += failWeight;
            Instant latestFailedAt = fv.serviceDeployments().stream()
                .filter(d -> "FAILED".equalsIgnoreCase(d.getOutcome()))
                .map(DeploymentRecordEntity::getDeployedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "HISTORICAL_DEPLOYMENTS",
                "PRIOR_DEPLOYMENT_FAILURES",
                fv.serviceName(),
                fv.serviceFailedDeploymentCount() + " failed deployment(s) out of " + deploymentSamples
                    + " recorded deployments for service '" + fv.serviceName() + "'",
                failWeight,
                (int) fv.serviceFailedDeploymentCount(),
                latestFailedAt,
                "deploy-fail:" + fv.serviceName()
            ));
        }

        // 2. Historical rollbacks (kept distinct from deployment failures)
        if (fv.serviceRollbackCount() > 0) {
            int rbWeight = Math.min(26, (int) fv.serviceRollbackCount() * 13);
            score += rbWeight;
            Instant latestRollbackAt = fv.serviceDeployments().stream()
                .filter(d -> "ROLLED_BACK".equalsIgnoreCase(d.getOutcome()))
                .map(DeploymentRecordEntity::getDeployedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "HISTORICAL_ROLLBACKS",
                "PRIOR_DEPLOYMENT_ROLLBACKS",
                fv.serviceName(),
                fv.serviceRollbackCount() + " deployment rollback(s) recorded for service '" + fv.serviceName() + "'",
                rbWeight,
                (int) fv.serviceRollbackCount(),
                latestRollbackAt,
                "deploy-rollback:" + fv.serviceName()
            ));
        }

        // 3. Critical configuration / deployment / infrastructure changes
        if (fv.touchesConfigOrInfra()) {
            int cfgWeight = 20;
            score += cfgWeight;
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "CONFIG_AND_INFRA",
                "CRITICAL_CONFIG_MODIFICATION",
                fv.serviceName(),
                "PR modifies runtime configuration, container, deployment, or CI workflow files",
                cfgWeight,
                0,
                null,
                "config-change:" + fv.reviewId()
            ));
        }

        // 4. Large change size increases deployment failure/rollback surface
        if (fv.changeSizeLines() >= 120) {
            int sizeWeight = fv.changeSizeLines() >= 300 ? 18 : 10;
            score += sizeWeight;
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "DIFF_SIZE",
                "LARGE_DEPLOYMENT_DELTA",
                fv.changeSizeLines() + " lines",
                "Large change delta (" + fv.changeSizeLines() + " lines across " + fv.parsedFiles().size()
                    + " files) complicates safe rollout and rollback",
                sizeWeight,
                0,
                null,
                "diff-size:" + fv.reviewId()
            ));
        }

        // 5. Recent CI failures or database migration changes
        if (!fv.failedCiBuildsTouchingPr().isEmpty()) {
            int ciWeight = Math.min(18, fv.failedCiBuildsTouchingPr().size() * 9);
            score += ciWeight;
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "CI_PIPELINE_HISTORY",
                "RECENT_PIPELINE_FAILURES",
                fv.failedCiBuildsTouchingPr().get(0).getPipelineName(),
                fv.failedCiBuildsTouchingPr().size() + " recent CI pipeline failure(s) associated with touched files/modules",
                ciWeight,
                fv.failedCiBuildsTouchingPr().size(),
                fv.failedCiBuildsTouchingPr().get(0).getTriggeredAt(),
                "ci-fail-history:" + fv.reviewId()
            ));
        }

        if (fv.migrationRiskCount() > 0) {
            int migWeight = 18;
            score += migWeight;
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "DATABASE_SCHEMA",
                "NON_REVERSIBLE_MIGRATION_ROLLBACK_RISK",
                "migrationRisks=" + fv.migrationRiskCount(),
                "Destructive schema changes make emergency deployment rollbacks harder or impossible without data loss",
                migWeight,
                0,
                null,
                "db-migration:" + fv.reviewId()
            ));
        }

        if (fv.totalBlastRadius() >= 3) {
            int depWeight = Math.min(14, fv.totalBlastRadius() * 3);
            score += depWeight;
            evidence.add(new PredictionCandidate.EvidenceDraft(
                "DEPENDENCY_GRAPH",
                "MULTI_COMPONENT_DEPLOY_IMPACT",
                fv.totalBlastRadius() + " components",
                "High dependency fan-out (" + fv.totalBlastRadius() + " impacted components) increases coordinated release risk",
                depWeight,
                0,
                null,
                "blast-radius:" + fv.reviewId()
            ));
        }

        int finalScore = Math.min(100, Math.max(0, score));
        boolean insufficientHistory = deploymentSamples == 0;
        String riskLevel = (insufficientHistory && evidence.isEmpty())
            ? "INSUFFICIENT_DATA"
            : BugRiskPredictor.toRiskLevel(finalScore);
        String confidence = assessConfidence(deploymentSamples,
            fv.serviceFailedDeploymentCount() > 0 || fv.serviceRollbackCount() > 0, !evidence.isEmpty());

        targets.add(new PredictionDtos.TargetRiskBreakdown(
            "SERVICE", fv.serviceName(), null, riskLevel, finalScore,
            "Deployments: " + deploymentSamples + " total, " + fv.serviceFailedDeploymentCount()
                + " failed, " + fv.serviceRollbackCount() + " rolled back"
        ));

        String explanation;
        if (insufficientHistory && evidence.isEmpty()) {
            explanation = PredictionDtos.INSUFFICIENT_DATA_MESSAGE
                + " No historical deployment or rollback records are available for service '" + fv.serviceName() + "'.";
        } else {
            String top = evidence.stream().limit(3).map(PredictionCandidate.EvidenceDraft::summary)
                .reduce((a, b) -> a + "; " + b).orElse("Low deployment delta");
            explanation = (insufficientHistory
                ? "Structural deployment baseline (" + riskLevel + ", " + finalScore + "/100 — no deployment history): "
                : riskLevel + " deployment change-failure/rollback risk (" + finalScore + "/100): ") + top + ".";
        }

        String limitations = insufficientHistory
            ? PredictionDtos.INSUFFICIENT_DATA_MESSAGE
                + " Connect GitHub deployment_status webhooks or ingest deployment records to calibrate change-failure and rollback predictions."
            : "Based on " + deploymentSamples + " deployment record(s) (" + fv.serviceFailedDeploymentCount()
                + " failed, " + fv.serviceRollbackCount() + " rolled back). Deployment failure, rollback, incident, "
                + "and defect outcomes are tracked strictly separately.";

        String action = switch (riskLevel) {
            case "CRITICAL", "HIGH" -> "Validate config/migrations in staging, deploy via progressive canary with automated rollback triggers, and verify pre-deploy CI checks.";
            case "MEDIUM" -> "Confirm rollback path and smoke-test staging deployment before promoting to production.";
            default -> "Standard automated release pipeline is appropriate.";
        };

        return new PredictionCandidate.CategoryPredictionDraft(
            CATEGORY_CHANGE_FAILURE,
            finalScore,
            riskLevel,
            null,
            confidence,
            insufficientHistory,
            deploymentSamples,
            fv.dataFreshnessAt(),
            CHANGE_FAILURE_RULE_VERSION,
            explanation,
            limitations,
            action,
            targets,
            evidence
        );
    }

    private String assessConfidence(int historicalSamples, boolean hasMatchingOutcome, boolean hasStructuralEvidence) {
        if (historicalSamples >= 8 && hasMatchingOutcome) return "HIGH";
        if (historicalSamples >= 3 || hasMatchingOutcome) return "MEDIUM";
        if (hasStructuralEvidence) return "LOW";
        return "INSUFFICIENT_DATA";
    }
}
