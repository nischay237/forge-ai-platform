package com.forge.platform.ci;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ci/builds")
public class CiController {

    private final CiService ciService;

    public CiController(CiService ciService) {
        this.ciService = ciService;
    }

    @GetMapping
    public List<CiService.CiBuildDto> list(@RequestParam(required = false) String repositoryId) {
        return ciService.listBuilds(repositoryId);
    }

    @GetMapping("/{buildId}")
    public CiService.CiBuildDto get(@PathVariable String buildId) {
        return ciService.getBuild(buildId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'REVIEWER')")
    public CiService.CiBuildDto submit(@Valid @RequestBody CiService.SubmitBuildRequest request) {
        return ciService.submitAndDiagnose(request);
    }
}
