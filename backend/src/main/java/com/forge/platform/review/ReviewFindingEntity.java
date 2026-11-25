package com.forge.platform.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "review_findings")
public class ReviewFindingEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "review_id", nullable = false)
    private String reviewId;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String severity;

    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal confidence;

    @Column(name = "rule_id", nullable = false)
    private String ruleId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String explanation;

    @Column(columnDefinition = "TEXT")
    private String evidence;

    @Column(name = "suggested_patch", columnDefinition = "TEXT")
    private String suggestedPatch;

    @Column(nullable = false)
    private String status;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ReviewFindingEntity() {}

    public static ReviewFindingEntity create(
        String tenantId, String reviewId, String filePath, int lineNumber,
        String category, String severity, double confidence, String ruleId,
        String title, String explanation, String evidence, String suggestedPatch
    ) {
        ReviewFindingEntity f = new ReviewFindingEntity();
        f.id = UUID.randomUUID().toString();
        f.tenantId = tenantId;
        f.reviewId = reviewId;
        f.filePath = filePath;
        f.lineNumber = Math.max(1, lineNumber);
        f.category = category;
        f.severity = severity;
        f.confidence = BigDecimal.valueOf(Math.min(0.99, Math.max(0.10, confidence)));
        f.ruleId = ruleId;
        f.title = title;
        f.explanation = explanation;
        f.evidence = evidence;
        f.suggestedPatch = suggestedPatch;
        f.status = "PENDING";
        f.createdAt = Instant.now();
        return f;
    }

    public void decide(String newStatus, String reviewerEmail) {
        this.status = newStatus;
        this.reviewedBy = reviewerEmail;
        this.reviewedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getReviewId() { return reviewId; }
    public String getFilePath() { return filePath; }
    public int getLineNumber() { return lineNumber; }
    public String getCategory() { return category; }
    public String getSeverity() { return severity; }
    public BigDecimal getConfidence() { return confidence; }
    public String getRuleId() { return ruleId; }
    public String getTitle() { return title; }
    public String getExplanation() { return explanation; }
    public String getEvidence() { return evidence; }
    public String getSuggestedPatch() { return suggestedPatch; }
    public String getStatus() { return status; }
    public String getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
