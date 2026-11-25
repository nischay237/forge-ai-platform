package com.forge.platform.review;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {

    private final ReviewPipelineService reviewPipelineService;
    private final ReviewEvaluationService evaluationService;

    public ReviewController(
        ReviewPipelineService reviewPipelineService,
        ReviewEvaluationService evaluationService
    ) {
        this.reviewPipelineService = reviewPipelineService;
        this.evaluationService = evaluationService;
    }

    @GetMapping
    public List<ReviewDtos.ReviewSummaryDto> list(@RequestParam(required = false) String repositoryId) {
        return reviewPipelineService.listReviews(repositoryId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'REVIEWER')")
    public ReviewDtos.ReviewSummaryDto triggerReview(@Valid @RequestBody ReviewDtos.CreateReviewRequest request) {
        return reviewPipelineService.enqueueReview(request);
    }

    @PostMapping("/import-github-pr")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'REVIEWER')")
    public ReviewDtos.ReviewSummaryDto importGithubPr(@Valid @RequestBody ReviewDtos.ImportGithubPrRequest request) {
        return reviewPipelineService.importGithubPullRequest(request.prUrl());
    }

    @GetMapping("/{reviewId}")
    public ReviewDtos.ReviewDetailDto detail(@PathVariable String reviewId) {
        return reviewPipelineService.getReviewDetail(reviewId);
    }

    @PatchMapping("/{reviewId}/findings/{findingId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'REVIEWER')")
    public ReviewFindingEntity decideFinding(
        @PathVariable String reviewId,
        @PathVariable String findingId,
        @Valid @RequestBody ReviewDtos.FindingDecisionRequest request
    ) {
        return reviewPipelineService.decideFinding(reviewId, findingId, request.decision());
    }

    @PostMapping("/{reviewId}/publish")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'REVIEWER')")
    public ReviewDtos.ReviewSummaryDto publish(@PathVariable String reviewId) {
        return reviewPipelineService.publishApprovedComments(reviewId);
    }

    @GetMapping("/evaluations")
    public List<ReviewDtos.EvaluationRunDto> listEvaluations() {
        return evaluationService.listEvaluations();
    }

    @PostMapping("/evaluations/run")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'REVIEWER')")
    public ReviewDtos.EvaluationRunDto runEvaluation() {
        return evaluationService.runBenchmark();
    }
}
