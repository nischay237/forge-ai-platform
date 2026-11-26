package com.forge.platform.codeintel;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Thread-safe Java 21 AST analyzer backed by per-invocation JavaParser instances.
 * Extracts classes, interfaces, enums, records, nested types, constructors, overloaded method signatures,
 * inheritance, DI/field/parameter/generic type references, object instantiations, and cyclomatic complexity.
 *
 * Delegates TypeScript/JavaScript files to {@link TypeScriptJsAnalyzer}.
 */
@Component
public class JavaAstAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(JavaAstAnalyzer.class);

    public record ParsedSymbol(
        String name,
        String qualifiedName,
        String kind,
        int startLine,
        int endLine,
        String signature,
        int complexity
    ) {}

    public record RawDependency(
        String sourceNode,
        String targetNode,
        String dependencyType
    ) {}

    public record AstResult(
        String primaryNode,
        int fileComplexity,
        List<ParsedSymbol> symbols,
        List<RawDependency> dependencies
    ) {}

    private final TypeScriptJsAnalyzer tsJsAnalyzer;

    public JavaAstAnalyzer() {
        this(new TypeScriptJsAnalyzer());
    }

    public JavaAstAnalyzer(TypeScriptJsAnalyzer tsJsAnalyzer) {
        this.tsJsAnalyzer = tsJsAnalyzer;
    }

    public AstResult analyze(String filePath, String language, String content) {
        String safeContent = content == null ? "" : content;
        if ("java".equalsIgnoreCase(language) || filePath.toLowerCase().endsWith(".java")) {
            return analyzeJava(filePath, safeContent);
        }
        if ("typescript".equalsIgnoreCase(language) || "javascript".equalsIgnoreCase(language)
            || filePath.endsWith(".ts") || filePath.endsWith(".tsx")
            || filePath.endsWith(".js") || filePath.endsWith(".jsx")) {
            return tsJsAnalyzer.analyze(filePath, safeContent);
        }
        return analyzeFallback(filePath, safeContent);
    }

    private AstResult analyzeJava(String filePath, String content) {
        List<ParsedSymbol> symbols = new ArrayList<>();
        Set<RawDependency> deps = new LinkedHashSet<>();
        String fallbackNode = extractSimpleName(filePath);
        int totalComplexity = 1;

        try {
            ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setAttributeComments(false);
            JavaParser parser = new JavaParser(config);
            ParseResult<CompilationUnit> result = parser.parse(content);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                log.debug("JavaParser reported syntax errors for {}: {}", filePath, result.getProblems());
                return analyzeFallback(filePath, content);
            }

            CompilationUnit cu = result.getResult().get();
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
            String primaryNode = cu.getPrimaryTypeName().orElse(fallbackNode);
            boolean isTestFile = primaryNode.endsWith("Test") || primaryNode.endsWith("Tests")
                || primaryNode.endsWith("IT") || filePath.replace('\\', '/').contains("/test/");

            // 1. Classes & Interfaces (including nested types with full enclosing hierarchy)
            for (ClassOrInterfaceDeclaration type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String simpleName = type.getNameAsString();
                String qualified = resolveNestedQualifiedName(pkg, type);
                int start = type.getBegin().map(p -> p.line).orElse(1);
                int end = type.getEnd().map(p -> p.line).orElse(start);
                String kind = type.isInterface() ? "INTERFACE" : (isTestFile ? "TEST_CLASS" : "CLASS");
                int classComplexity = calculateComplexity(type);
                totalComplexity = Math.max(totalComplexity, classComplexity);

                symbols.add(new ParsedSymbol(
                    simpleName,
                    qualified,
                    kind,
                    start,
                    end,
                    (type.isInterface() ? "interface " : "class ") + simpleName,
                    classComplexity
                ));

                String ownerNode = primaryNode;
                type.getExtendedTypes().forEach(ext ->
                    addTypeDep(deps, ownerNode, ext.getNameAsString(), "EXTENDS"));
                type.getImplementedTypes().forEach(impl ->
                    addTypeDep(deps, ownerNode, impl.getNameAsString(), "IMPLEMENTS"));

                for (FieldDeclaration field : type.getFields()) {
                    extractTypeReferences(field.getElementType(), ownerNode, isTestFile ? "TESTS" : "INJECTS", deps);
                }

                for (ConstructorDeclaration ctor : type.getConstructors()) {
                    int cStart = ctor.getBegin().map(p -> p.line).orElse(start);
                    int cEnd = ctor.getEnd().map(p -> p.line).orElse(cStart);
                    String paramTypes = ctor.getParameters().stream()
                        .map(p -> p.getType().asString())
                        .collect(Collectors.joining(","));
                    symbols.add(new ParsedSymbol(
                        ctor.getNameAsString(),
                        qualified + "#<init>(" + paramTypes + ")",
                        "CONSTRUCTOR",
                        cStart,
                        cEnd,
                        ctor.getDeclarationAsString(false, false, true),
                        calculateComplexity(ctor)
                    ));
                    for (Parameter param : ctor.getParameters()) {
                        extractTypeReferences(param.getType(), ownerNode, isTestFile ? "TESTS" : "INJECTS", deps);
                    }
                }

                for (MethodDeclaration method : type.getMethods()) {
                    int mStart = method.getBegin().map(p -> p.line).orElse(start);
                    int mEnd = method.getEnd().map(p -> p.line).orElse(mStart);
                    int mComp = calculateComplexity(method);
                    String paramTypes = method.getParameters().stream()
                        .map(p -> p.getType().asString())
                        .collect(Collectors.joining(","));
                    symbols.add(new ParsedSymbol(
                        method.getNameAsString(),
                        qualified + "#" + method.getNameAsString() + "(" + paramTypes + ")",
                        "METHOD",
                        mStart,
                        mEnd,
                        method.getDeclarationAsString(false, false, true),
                        mComp
                    ));
                    extractTypeReferences(method.getType(), ownerNode, isTestFile ? "TESTS" : "REFERENCES", deps);
                    for (Parameter param : method.getParameters()) {
                        extractTypeReferences(param.getType(), ownerNode, isTestFile ? "TESTS" : "REFERENCES", deps);
                    }
                }
            }

            // 2. Records (Java 16+)
            for (RecordDeclaration rec : cu.findAll(RecordDeclaration.class)) {
                String name = rec.getNameAsString();
                String qualified = resolveNestedQualifiedName(pkg, rec);
                int start = rec.getBegin().map(p -> p.line).orElse(1);
                int end = rec.getEnd().map(p -> p.line).orElse(start);
                int comp = calculateComplexity(rec);
                totalComplexity = Math.max(totalComplexity, comp);
                symbols.add(new ParsedSymbol(name, qualified, "RECORD", start, end, "record " + name, comp));
                rec.getImplementedTypes().forEach(impl ->
                    addTypeDep(deps, primaryNode, impl.getNameAsString(), "IMPLEMENTS"));
                for (Parameter param : rec.getParameters()) {
                    extractTypeReferences(param.getType(), primaryNode, isTestFile ? "TESTS" : "REFERENCES", deps);
                }
            }

            // 3. Enums
            for (EnumDeclaration en : cu.findAll(EnumDeclaration.class)) {
                String name = en.getNameAsString();
                String qualified = resolveNestedQualifiedName(pkg, en);
                int start = en.getBegin().map(p -> p.line).orElse(1);
                int end = en.getEnd().map(p -> p.line).orElse(start);
                symbols.add(new ParsedSymbol(name, qualified, "ENUM", start, end, "enum " + name, 1));
                en.getImplementedTypes().forEach(impl ->
                    addTypeDep(deps, primaryNode, impl.getNameAsString(), "IMPLEMENTS"));
            }

            // 4. Explicit AST Imports
            for (ImportDeclaration imp : cu.getImports()) {
                String impName = imp.getNameAsString();
                if (!impName.startsWith("java.") && !impName.startsWith("jakarta.")
                    && !impName.startsWith("org.springframework.") && !impName.startsWith("org.slf4j.")
                    && !impName.startsWith("org.junit.") && !impName.startsWith("org.assertj.")
                    && !impName.startsWith("org.mockito.")) {
                    String simple = impName.contains(".") ? impName.substring(impName.lastIndexOf('.') + 1) : impName;
                    if (!simple.equals("*") && !simple.equals(primaryNode)) {
                        addTypeDep(deps, primaryNode, simple, isTestFile ? "TESTS" : "IMPORTS");
                    }
                }
            }

            // 5. AST Object instantiations (`new Foo()`) and Static method calls (`Foo.bar()`) — zero comment/string false positives!
            for (ObjectCreationExpr creation : cu.findAll(ObjectCreationExpr.class)) {
                addTypeDep(deps, primaryNode, creation.getType().getNameAsString(), isTestFile ? "TESTS" : "INSTANTIATES");
            }
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                call.getScope().ifPresent(scope -> {
                    String scopeText = scope.toString();
                    if (scopeText.matches("^[A-Z][A-Za-z0-9_]+$")) {
                        addTypeDep(deps, primaryNode, scopeText, isTestFile ? "TESTS" : "CALLS");
                    }
                });
            }

            // 6. AST ClassOrInterfaceType references inside method bodies (catches local variables & generic args cleanly)
            for (ClassOrInterfaceType typeRef : cu.findAll(ClassOrInterfaceType.class)) {
                addTypeDep(deps, primaryNode, typeRef.getNameAsString(), isTestFile ? "TESTS" : "REFERENCES");
            }

            if (symbols.isEmpty()) {
                symbols.add(new ParsedSymbol(primaryNode, pkg + primaryNode, "MODULE", 1,
                    Math.max(1, content.split("\\R").length), primaryNode, totalComplexity));
            }

            return new AstResult(primaryNode, totalComplexity, symbols, new ArrayList<>(deps));
        } catch (Exception ex) {
            log.debug("JavaParser fallback for {}: {}", filePath, ex.getMessage());
            return analyzeFallback(filePath, content);
        }
    }

    private void extractTypeReferences(
        com.github.javaparser.ast.type.Type type,
        String sourceNode,
        String depType,
        Set<RawDependency> deps
    ) {
        if (type == null) return;
        for (ClassOrInterfaceType cit : type.findAll(ClassOrInterfaceType.class)) {
            addTypeDep(deps, sourceNode, cit.getNameAsString(), depType);
        }
    }

    private void addTypeDep(Set<RawDependency> deps, String sourceNode, String targetSimple, String depType) {
        if (targetSimple == null || targetSimple.isBlank()) return;
        String cleaned = targetSimple.trim();
        if (cleaned.equals(sourceNode) || cleaned.length() <= 1 || isJdkPrimitiveOrCommon(cleaned)) {
            return;
        }
        deps.add(new RawDependency(sourceNode, cleaned, depType));
    }

    private String resolveNestedQualifiedName(String pkgPrefix, TypeDeclaration<?> type) {
        List<String> chain = new ArrayList<>();
        chain.add(type.getNameAsString());
        Node cursor = type.getParentNode().orElse(null);
        while (cursor != null) {
            if (cursor instanceof TypeDeclaration<?> enclosing) {
                chain.add(0, enclosing.getNameAsString());
            }
            cursor = cursor.getParentNode().orElse(null);
        }
        return pkgPrefix + String.join(".", chain);
    }

    private AstResult analyzeFallback(String filePath, String content) {
        String node = extractSimpleName(filePath);
        int lineCount = Math.max(1, content.split("\\R").length);
        return new AstResult(
            node,
            1,
            List.of(new ParsedSymbol(node, filePath, "MODULE", 1, lineCount, filePath, 1)),
            List.of()
        );
    }

    private int calculateComplexity(Node node) {
        int c = 1;
        c += node.findAll(IfStmt.class).size();
        c += node.findAll(ForStmt.class).size();
        c += node.findAll(ForEachStmt.class).size();
        c += node.findAll(WhileStmt.class).size();
        c += node.findAll(CatchClause.class).size();
        c += node.findAll(ConditionalExpr.class).size();
        c += (int) node.findAll(SwitchEntry.class).stream().filter(s -> !s.getLabels().isEmpty()).count();
        c += (int) node.findAll(BinaryExpr.class).stream()
            .filter(b -> b.getOperator() == BinaryExpr.Operator.AND || b.getOperator() == BinaryExpr.Operator.OR)
            .count();
        return c;
    }

    static String extractSimpleName(String path) {
        String normalized = path.replace('\\', '/');
        String base = normalized.contains("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    private boolean isJdkPrimitiveOrCommon(String name) {
        return Set.of(
            "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short", "Character",
            "Object", "Class", "Void", "Number", "Throwable", "Error", "List", "Map", "Set",
            "Collection", "Iterable", "Iterator", "Queue", "Deque", "Optional", "UUID", "Instant",
            "LocalDate", "LocalDateTime", "ZonedDateTime", "OffsetDateTime", "BigDecimal", "BigInteger",
            "RuntimeException", "Exception", "IllegalArgumentException", "IllegalStateException",
            "NullPointerException", "UnsupportedOperationException", "IOException", "AssertionError",
            "System", "Math", "Arrays", "Collections", "Collectors", "Comparator", "Function", "Predicate",
            "Consumer", "Supplier", "Logger", "LoggerFactory", "ResponseEntity", "HttpStatus",
            "Override", "Service", "Component", "Repository", "RestController", "Controller",
            "Transactional", "Autowired", "Value", "Nullable", "NotBlank", "NotNull", "Valid", "Size",
            "RequestMapping", "GetMapping", "PostMapping", "PatchMapping", "PutMapping", "DeleteMapping",
            "PathVariable", "RequestBody", "RequestParam", "RequestHeader", "Test", "BeforeEach", "BeforeAll",
            "Assertions", "StringBuilder", "StringBuffer", "Objects", "Duration", "Path", "Paths", "Files",
            "URL", "URI", "Pattern", "Matcher", "Stream", "CompletableFuture", "ConcurrentHashMap",
            "ArrayList", "HashMap", "HashSet", "LinkedHashMap", "LinkedHashSet", "ArrayDeque",
            "DecimalFormat", "DateTimeFormatter", "RoundingMode", "StandardCharsets", "Base64",
            "JsonNode", "ObjectMapper", "MediaType", "PageRequest", "Pageable",
            "int", "long", "boolean", "double", "float", "byte", "char", "void"
        ).contains(name);
    }
}
