package com.forge.platform.repository;

import com.forge.platform.audit.AuditService;
import com.forge.platform.common.ForgeException;
import com.forge.platform.common.ResourceNotFoundException;
import com.forge.platform.common.SecretCryptoService;
import com.forge.platform.common.TenantContext;
import com.forge.platform.messaging.ForgeTopics;
import com.forge.platform.messaging.OutboxService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RepositoryService {

    private final RepositoryJpaRepository repositoryJpaRepository;
    private final SourceFileJpaRepository sourceFileJpaRepository;
    private final RepositoryMapper repositoryMapper;
    private final GithubIngestionClient githubIngestionClient;
    private final SecretCryptoService cryptoService;
    private final OutboxService outboxService;
    private final AuditService auditService;

    public RepositoryService(
        RepositoryJpaRepository repositoryJpaRepository,
        SourceFileJpaRepository sourceFileJpaRepository,
        RepositoryMapper repositoryMapper,
        GithubIngestionClient githubIngestionClient,
        SecretCryptoService cryptoService,
        OutboxService outboxService,
        AuditService auditService
    ) {
        this.repositoryJpaRepository = repositoryJpaRepository;
        this.sourceFileJpaRepository = sourceFileJpaRepository;
        this.repositoryMapper = repositoryMapper;
        this.githubIngestionClient = githubIngestionClient;
        this.cryptoService = cryptoService;
        this.outboxService = outboxService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<RepositoryDtos.RepositoryDto> listRepositories() {
        String tenantId = TenantContext.requireTenantId();
        return repositoryJpaRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
            .stream()
            .map(repositoryMapper::toDto)
            .toList();
    }

    @Transactional(readOnly = true)
    public RepositoryEntity requireRepository(String repositoryId, String tenantId) {
        return repositoryJpaRepository.findByIdAndTenantId(repositoryId, tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Repository not found: " + repositoryId));
    }

    @Transactional
    public RepositoryDtos.RepositoryDto connectRepository(RepositoryDtos.ConnectRepositoryRequest request) {
        String tenantId = TenantContext.requireTenantId();
        String fullName = request.owner().trim() + "/" + request.name().trim();

        if (repositoryJpaRepository.findByTenantIdAndFullNameIgnoreCase(tenantId, fullName).isPresent()) {
            throw new ForgeException(HttpStatus.CONFLICT, "REPO_ALREADY_CONNECTED",
                "Repository " + fullName + " is already connected to this workspace");
        }

        String branch = (request.defaultBranch() == null || request.defaultBranch().isBlank())
            ? "main" : request.defaultBranch().trim();
        String githubUrl = "https://github.com/" + fullName;

        RepositoryEntity repo = RepositoryEntity.create(
            tenantId,
            request.owner(),
            request.name(),
            branch,
            request.primaryLanguage(),
            githubUrl,
            request.description() != null && !request.description().isBlank()
                ? request.description().trim()
                : "Connected GitHub repository analyzed by FORGE"
        );
        repositoryJpaRepository.save(repo);

        Map<String, String> filesToStore = new LinkedHashMap<>();
        if (request.initialFiles() != null && !request.initialFiles().isEmpty()) {
            filesToStore.putAll(request.initialFiles());
        } else if (request.fetchFromGithub()) {
            filesToStore.putAll(githubIngestionClient.fetchPublicRepositoryFiles(repo.getOwner(), repo.getName(), branch));
        }

        if (filesToStore.isEmpty()) {
            filesToStore.putAll(defaultSpringStarterFiles(repo.getName()));
        }

        saveSourceFiles(tenantId, repo, filesToStore);

        outboxService.enqueue(
            tenantId,
            "REPOSITORY",
            repo.getId(),
            "REPO_INDEX_REQUESTED",
            ForgeTopics.REPO_INDEX_REQUESTED,
            Map.of("tenantId", tenantId, "repositoryId", repo.getId())
        );

        auditService.recordCurrent("REPOSITORY_CONNECTED", "REPOSITORY", repo.getId(),
            "Connected " + repo.getFullName() + " (" + filesToStore.size() + " files queued for AST indexing)");

        return repositoryMapper.toDto(repo);
    }

    @Transactional
    public RepositoryDtos.RepositoryDto triggerReindex(String repositoryId) {
        String tenantId = TenantContext.requireTenantId();
        RepositoryEntity repo = requireRepository(repositoryId, tenantId);
        repo.setIndexStatus("INDEXING");
        repositoryJpaRepository.save(repo);

        outboxService.enqueue(
            tenantId,
            "REPOSITORY",
            repo.getId(),
            "REPO_INDEX_REQUESTED",
            ForgeTopics.REPO_INDEX_REQUESTED,
            Map.of("tenantId", tenantId, "repositoryId", repo.getId())
        );

        auditService.recordCurrent("REPOSITORY_REINDEX_TRIGGERED", "REPOSITORY", repo.getId(),
            "Queued AST and dependency re-indexing for " + repo.getFullName());

        return repositoryMapper.toDto(repo);
    }

    @Transactional
    public void saveSourceFiles(String tenantId, RepositoryEntity repo, Map<String, String> files) {
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String path = entry.getKey();
            String content = entry.getValue();
            String lang = detectLanguage(path);
            SourceFileEntity existing = sourceFileJpaRepository
                .findByRepositoryIdAndTenantIdAndFilePath(repo.getId(), tenantId, path)
                .orElse(null);
            if (existing != null) {
                existing.setContent(content);
                sourceFileJpaRepository.save(existing);
            } else {
                sourceFileJpaRepository.save(SourceFileEntity.create(
                    tenantId, repo.getId(), path, lang, 1, cryptoService.sha256Hex(content), content
                ));
            }
        }
        repo.setFileCount((int) sourceFileJpaRepository.countByRepositoryIdAndTenantId(repo.getId(), tenantId));
        repositoryJpaRepository.save(repo);
    }

    @Transactional(readOnly = true)
    public List<RepositoryDtos.SourceFileSummaryDto> listFiles(String repositoryId) {
        String tenantId = TenantContext.requireTenantId();
        requireRepository(repositoryId, tenantId);
        return sourceFileJpaRepository.findByRepositoryIdAndTenantIdOrderByFilePathAsc(repositoryId, tenantId)
            .stream()
            .map(repositoryMapper::toSummaryDto)
            .toList();
    }

    @Transactional(readOnly = true)
    public RepositoryDtos.SourceFileDetailDto getFileContent(String repositoryId, String fileId) {
        String tenantId = TenantContext.requireTenantId();
        requireRepository(repositoryId, tenantId);
        SourceFileEntity file = sourceFileJpaRepository.findById(fileId)
            .filter(f -> f.getTenantId().equals(tenantId) && f.getRepositoryId().equals(repositoryId))
            .orElseThrow(() -> new ResourceNotFoundException("Source file not found"));
        return repositoryMapper.toDetailDto(file);
    }

    @Transactional
    public void deleteRepository(String repositoryId) {
        String tenantId = TenantContext.requireTenantId();
        RepositoryEntity repo = requireRepository(repositoryId, tenantId);
        String fullName = repo.getFullName();
        repositoryJpaRepository.delete(repo);
        auditService.recordCurrent("REPOSITORY_DELETED", "REPOSITORY", repositoryId,
            "Disconnected repository " + fullName);
    }

    public static String detectLanguage(String filePath) {
        String lower = filePath.toLowerCase();
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript";
        if (lower.endsWith(".js") || lower.endsWith(".jsx")) return "javascript";
        if (lower.endsWith(".sql")) return "sql";
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "yaml";
        if (lower.endsWith(".xml")) return "xml";
        return "plaintext";
    }

    private Map<String, String> defaultSpringStarterFiles(String repoName) {
        String pkg = repoName.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (pkg.isBlank()) pkg = "service";
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main/java/com/acme/" + pkg + "/OrderController.java", """
            package com.acme.%s;

            import org.springframework.web.bind.annotation.*;

            @RestController
            @RequestMapping("/api/orders")
            public class OrderController {
                private final OrderService orderService;

                public OrderController(OrderService orderService) {
                    this.orderService = orderService;
                }

                @PostMapping
                public String createOrder(@RequestBody String payload) {
                    return orderService.processOrder(payload);
                }
            }
            """.formatted(pkg));
        files.put("src/main/java/com/acme/" + pkg + "/OrderService.java", """
            package com.acme.%s;

            import org.springframework.stereotype.Service;

            @Service
            public class OrderService {
                private final LedgerRepository ledgerRepository;
                private final RiskEvaluator riskEvaluator;

                public OrderService(LedgerRepository ledgerRepository, RiskEvaluator riskEvaluator) {
                    this.ledgerRepository = ledgerRepository;
                    this.riskEvaluator = riskEvaluator;
                }

                public String processOrder(String payload) {
                    if (payload == null || payload.isBlank()) {
                        throw new IllegalArgumentException("Payload required");
                    }
                    if (!riskEvaluator.isSafe(payload)) {
                        return "REJECTED";
                    }
                    return ledgerRepository.saveEntry(payload);
                }
            }
            """.formatted(pkg));
        files.put("src/main/java/com/acme/" + pkg + "/RiskEvaluator.java", """
            package com.acme.%s;

            import org.springframework.stereotype.Component;

            @Component
            public class RiskEvaluator {
                public boolean isSafe(String payload) {
                    return payload != null && payload.length() < 10000;
                }
            }
            """.formatted(pkg));
        files.put("src/main/java/com/acme/" + pkg + "/LedgerRepository.java", """
            package com.acme.%s;

            import org.springframework.stereotype.Repository;

            @Repository
            public class LedgerRepository {
                public String saveEntry(String payload) {
                    return "LEDGER_OK:" + payload.hashCode();
                }
            }
            """.formatted(pkg));
        files.put("src/test/java/com/acme/" + pkg + "/OrderServiceTest.java", """
            package com.acme.%s;

            public class OrderServiceTest {
                public void shouldProcessSafeOrder() {
                    OrderService service = new OrderService(new LedgerRepository(), new RiskEvaluator());
                    String result = service.processOrder("order-1");
                    if (!result.startsWith("LEDGER_OK")) {
                        throw new AssertionError("Expected LEDGER_OK");
                    }
                }
            }
            """.formatted(pkg));
        return files;
    }
}
