package com.forge.platform.repository;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/repositories")
public class RepositoryController {

    private final RepositoryService repositoryService;

    public RepositoryController(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    @GetMapping
    public List<RepositoryDtos.RepositoryDto> list() {
        return repositoryService.listRepositories();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'REVIEWER')")
    public RepositoryDtos.RepositoryDto connect(@Valid @RequestBody RepositoryDtos.ConnectRepositoryRequest request) {
        return repositoryService.connectRepository(request);
    }

    @PostMapping("/{repositoryId}/reindex")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'REVIEWER')")
    public RepositoryDtos.RepositoryDto reindex(@PathVariable String repositoryId) {
        return repositoryService.triggerReindex(repositoryId);
    }

    @GetMapping("/{repositoryId}/files")
    public List<RepositoryDtos.SourceFileSummaryDto> listFiles(@PathVariable String repositoryId) {
        return repositoryService.listFiles(repositoryId);
    }

    @GetMapping("/{repositoryId}/files/{fileId}")
    public RepositoryDtos.SourceFileDetailDto getFile(
        @PathVariable String repositoryId,
        @PathVariable String fileId
    ) {
        return repositoryService.getFileContent(repositoryId, fileId);
    }

    @DeleteMapping("/{repositoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public void delete(@PathVariable String repositoryId) {
        repositoryService.deleteRepository(repositoryId);
    }
}
