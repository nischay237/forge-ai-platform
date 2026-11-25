package com.forge.platform.codeintel;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class JavaAstAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(JavaAstAnalyzer.class);
    private static final Pattern TS_DECL = Pattern.compile("(?:export\\s+)?(?:class|interface|function|const)\\s+([A-Za-z0-9_]+)");
    private static final Pattern TS_IMPORT = Pattern.compile("import\\s+.*?from\\s+['\"]([^'\"]+)['\"]");

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

    public AstResult analyze(String filePath, String language, String content) {
        if ("java".equalsIgnoreCase(language)) {
            return analyzeJava(filePath, content);
        }
        return analyzeGeneric(filePath, content);
    }

    private AstResult analyzeJava(String filePath, String content) {
        List<ParsedSymbol> symbols = new ArrayList<>();
        Set<RawDependency> deps = new LinkedHashSet<>();
        String fallbackNode = extractSimpleName(filePath);
        int totalComplexity = 1;

        try {
            CompilationUnit cu = StaticJavaParser.parse(content);
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
            String primaryNode = fallbackNode;

            List<ClassOrInterfaceDeclaration> types = cu.findAll(ClassOrInterfaceDeclaration.class);
            for (ClassOrInterfaceDeclaration type : types) {
                String name = type.getNameAsString();
                if (primaryNode.equals(fallbackNode)) {
                    primaryNode = name;
                }
                int start = type.getBegin().map(p -> p.line).orElse(1);
                int end = type.getEnd().map(p -> p.line).orElse(start);
                String kind = type.isInterface() ? "INTERFACE" : (name.endsWith("Test") ? "TEST_CLASS" : "CLASS");
                int classComplexity = calculateComplexity(type);
                totalComplexity = Math.max(totalComplexity, classComplexity);
                symbols.add(new ParsedSymbol(name, pkg + name, kind, start, end,
                    (type.isInterface() ? "interface " : "class ") + name, classComplexity));

                type.getExtendedTypes().forEach(ext ->
                    deps.add(new RawDependency(name, ext.getNameAsString(), "EXTENDS")));
                type.getImplementedTypes().forEach(impl ->
                    deps.add(new RawDependency(name, impl.getNameAsString(), "IMPLEMENTS")));

                for (FieldDeclaration field : type.getFields()) {
                    String fieldType = stripGenerics(field.getElementType().asString());
                    if (!isJdkPrimitiveOrCommon(fieldType) && !fieldType.equals(name)) {
                        String depType = name.endsWith("Test") ? "TESTS" : "INJECTS";
                        deps.add(new RawDependency(name, fieldType, depType));
                    }
                }

                for (MethodDeclaration method : type.getMethods()) {
                    int mStart = method.getBegin().map(p -> p.line).orElse(start);
                    int mEnd = method.getEnd().map(p -> p.line).orElse(mStart);
                    int mComp = calculateComplexity(method);
                    symbols.add(new ParsedSymbol(
                        method.getNameAsString(),
                        pkg + name + "#" + method.getNameAsString(),
                        "METHOD",
                        mStart,
                        mEnd,
                        method.getDeclarationAsString(false, false, true),
                        mComp
                    ));
                }
            }

            for (RecordDeclaration rec : cu.findAll(RecordDeclaration.class)) {
                String name = rec.getNameAsString();
                int start = rec.getBegin().map(p -> p.line).orElse(1);
                int end = rec.getEnd().map(p -> p.line).orElse(start);
                symbols.add(new ParsedSymbol(name, pkg + name, "RECORD", start, end, "record " + name, 1));
            }

            for (ImportDeclaration imp : cu.getImports()) {
                String impName = imp.getNameAsString();
                if (!impName.startsWith("java.") && !impName.startsWith("jakarta.")
                    && !impName.startsWith("org.springframework.") && !impName.startsWith("org.slf4j.")) {
                    String simple = impName.contains(".") ? impName.substring(impName.lastIndexOf('.') + 1) : impName;
                    if (!simple.equals("*") && !simple.equals(primaryNode)) {
                        deps.add(new RawDependency(primaryNode, simple, "IMPORTS"));
                    }
                }
            }

            // Detect intra-package type references (e.g., new Foo(), Foo.bar(), Foo param)
            String bodyWithoutComments = content.replaceAll("//.*", "");
            Matcher wordMatcher = Pattern.compile("\\b([A-Z][A-Za-z0-9_]{2,})\\b").matcher(bodyWithoutComments);
            Set<String> seenTokens = new LinkedHashSet<>();
            while (wordMatcher.find()) {
                seenTokens.add(wordMatcher.group(1));
            }
            for (String token : seenTokens) {
                if (!token.equals(primaryNode) && !isJdkPrimitiveOrCommon(token)) {
                    String depType = primaryNode.endsWith("Test") ? "TESTS" : "REFERENCES";
                    deps.add(new RawDependency(primaryNode, token, depType));
                }
            }

            return new AstResult(primaryNode, totalComplexity, symbols, new ArrayList<>(deps));
        } catch (Exception ex) {
            log.debug("JavaParser fallback for {}: {}", filePath, ex.getMessage());
            return analyzeGeneric(filePath, content);
        }
    }

    private AstResult analyzeGeneric(String filePath, String content) {
        String node = extractSimpleName(filePath);
        List<ParsedSymbol> symbols = new ArrayList<>();
        Set<RawDependency> deps = new LinkedHashSet<>();

        String[] lines = content.split("\\R");
        int complexity = 1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("if ") || line.contains("if(") || line.contains("for ")
                || line.contains("while ") || line.contains("catch ") || line.contains("&&") || line.contains("||")) {
                complexity++;
            }
            Matcher decl = TS_DECL.matcher(line);
            if (decl.find()) {
                String name = decl.group(1);
                symbols.add(new ParsedSymbol(name, filePath + "#" + name, "SYMBOL", i + 1, i + 1, line.trim(), 1));
            }
            Matcher imp = TS_IMPORT.matcher(line);
            if (imp.find()) {
                String target = imp.group(1);
                String simpleTarget = extractSimpleName(target);
                if (!simpleTarget.isBlank() && !simpleTarget.equals(node)) {
                    deps.add(new RawDependency(node, simpleTarget, "IMPORTS"));
                }
            }
        }
        if (symbols.isEmpty()) {
            symbols.add(new ParsedSymbol(node, filePath, "MODULE", 1, Math.max(1, lines.length), filePath, complexity));
        }
        return new AstResult(node, complexity, symbols, new ArrayList<>(deps));
    }

    private int calculateComplexity(com.github.javaparser.ast.Node node) {
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

    private String stripGenerics(String type) {
        int idx = type.indexOf('<');
        String cleaned = idx > 0 ? type.substring(0, idx) : type;
        if (cleaned.endsWith("[]")) {
            cleaned = cleaned.substring(0, cleaned.length() - 2);
        }
        return cleaned.trim();
    }

    private String extractSimpleName(String path) {
        String normalized = path.replace('\\', '/');
        String base = normalized.contains("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    private boolean isJdkPrimitiveOrCommon(String name) {
        return Set.of(
            "String", "Integer", "Long", "Double", "Boolean", "Object", "List", "Map", "Set",
            "Optional", "UUID", "Instant", "LocalDate", "LocalDateTime", "BigDecimal",
            "RuntimeException", "Exception", "IllegalArgumentException", "IllegalStateException",
            "AssertionError", "System", "Math", "Arrays", "Collections", "Collectors", "Logger",
            "LoggerFactory", "ResponseEntity", "HttpStatus", "Override", "Service", "Component",
            "Repository", "RestController", "Transactional", "Autowired", "Value", "Nullable",
            "NotBlank", "Valid", "RequestMapping", "GetMapping", "PostMapping", "PatchMapping",
            "DeleteMapping", "PathVariable", "RequestBody", "RequestParam", "Test", "BeforeEach",
            "Assertions", "StringBuilder", "Objects", "Duration", "Path", "Paths", "Files", "URL",
            "URI", "Pattern", "Matcher", "Stream", "CompletableFuture", "ConcurrentHashMap",
            "ArrayList", "HashMap", "HashSet", "LinkedHashMap", "LinkedHashSet", "DecimalFormat",
            "int", "long", "boolean", "double", "float", "byte", "char", "void"
        ).contains(name);
    }
}
