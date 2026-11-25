package com.forge.platform.codeintel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "code_symbols")
public class CodeSymbolEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "repository_id", nullable = false)
    private String repositoryId;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "symbol_name", nullable = false)
    private String symbolName;

    @Column(name = "qualified_name", nullable = false)
    private String qualifiedName;

    @Column(name = "symbol_kind", nullable = false)
    private String symbolKind;

    @Column(name = "start_line", nullable = false)
    private int startLine;

    @Column(name = "end_line", nullable = false)
    private int endLine;

    @Column
    private String signature;

    @Column(nullable = false)
    private int complexity;

    public CodeSymbolEntity() {}

    public static CodeSymbolEntity create(String tenantId, String repositoryId, String filePath,
                                          String symbolName, String qualifiedName, String symbolKind,
                                          int startLine, int endLine, String signature, int complexity) {
        CodeSymbolEntity s = new CodeSymbolEntity();
        s.id = UUID.randomUUID().toString();
        s.tenantId = tenantId;
        s.repositoryId = repositoryId;
        s.filePath = filePath;
        s.symbolName = symbolName;
        s.qualifiedName = qualifiedName;
        s.symbolKind = symbolKind;
        s.startLine = startLine;
        s.endLine = endLine;
        s.signature = signature != null && signature.length() > 490 ? signature.substring(0, 490) : signature;
        s.complexity = Math.max(1, complexity);
        return s;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getRepositoryId() { return repositoryId; }
    public String getFilePath() { return filePath; }
    public String getSymbolName() { return symbolName; }
    public String getQualifiedName() { return qualifiedName; }
    public String getSymbolKind() { return symbolKind; }
    public int getStartLine() { return startLine; }
    public int getEndLine() { return endLine; }
    public String getSignature() { return signature; }
    public int getComplexity() { return complexity; }
}
