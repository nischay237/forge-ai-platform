package com.forge.platform.review;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    @GetMapping
    public PolicyService.PolicyDto getPolicy(@RequestParam(required = false) String repositoryId) {
        return policyService.getPolicy(repositoryId);
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public PolicyService.PolicyDto updatePolicy(@RequestBody PolicyService.UpdatePolicyRequest request) {
        return policyService.updatePolicy(request);
    }
}
