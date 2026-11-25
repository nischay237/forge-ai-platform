package com.forge.platform.codeintel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "code_dependencies")
public class CodeDependencyEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "repository_id", nullable = false)
    private String repositoryId;

    @Column(name = "source_node", nullable = false)
    private String sourceNode;

    @Column(name = "source_file", nullable = false)
    private String sourceFile;

    @Column(name = "target_node", nullable = false)
    private String targetNode;

    @Column(name = "target_file")
    private String targetFile;

    @Column(name = "dependency_type", nullable = false)
    private String dependencyType;

    public CodeDependencyEntity() {}

    public static CodeDependencyEntity create(String tenantId, String repositoryId, String sourceNode,
                                              String sourceFile, String targetNode, String targetFile,
                                              String dependencyType) {
        CodeDependencyEntity d = new CodeDependencyEntity();
        d.id = UUID.randomUUID().toString();
        d.tenantId = tenantId;
        d.repositoryId = repositoryId;
        d.sourceNode = sourceNode;
        d.sourceFile = sourceFile;
        d.targetNode = targetNode;
        d.targetFile = targetFile;
        d.dependencyType = dependencyType;
        return d;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getRepositoryId() { return repositoryId; }
    public String getSourceNode() { return sourceNode; }
    public String getSourceFile() { return sourceFile; }
    public String getTargetNode() { return targetNode; }
    public String getTargetFile() { return targetFile; }
    public String getDependencyType() { return dependencyType; }
}
