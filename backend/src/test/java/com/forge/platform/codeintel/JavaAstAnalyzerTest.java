package com.forge.platform.codeintel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaAstAnalyzerTest {

    private final JavaAstAnalyzer analyzer = new JavaAstAnalyzer();

    @Test
    void parsesJavaClassEnumsRecordsConstructorsOverloadsAndIgnoresComments() {
        String source = """
            package com.acme.ledger;

            import org.springframework.stereotype.Service;
            import java.util.List;

            // Mentioning FakeCommentType inside a comment must NOT create a dependency!
            @Service
            public class PaymentProcessor extends BaseProcessor implements SettlementPort {
                private final LedgerRepository ledgerRepository;
                private final List<RiskRule> activeRules;

                public enum Status { PENDING, SETTLED }
                public record Receipt(String txId, AuditMeta meta) {}

                public PaymentProcessor(LedgerRepository ledgerRepository, List<RiskRule> activeRules) {
                    this.ledgerRepository = ledgerRepository;
                    this.activeRules = activeRules;
                }

                public String process(String id) {
                    String note = "FakeStringClass inside literal must not become a dependency";
                    return process(id, false);
                }

                public String process(String id, boolean urgent) {
                    if (id == null || id.isBlank()) {
                        throw new IllegalArgumentException("id required");
                    }
                    if (urgent && FraudEngine.isHighRisk(id)) {
                        return "BLOCKED";
                    }
                    return ledgerRepository.save(id);
                }
            }
            """;

        JavaAstAnalyzer.AstResult result = analyzer.analyze("src/main/java/com/acme/ledger/PaymentProcessor.java", "java", source);

        assertThat(result.primaryNode()).isEqualTo("PaymentProcessor");
        assertThat(result.fileComplexity()).isGreaterThanOrEqualTo(4);
        assertThat(result.symbols())
            .extracting(JavaAstAnalyzer.ParsedSymbol::qualifiedName)
            .contains(
                "com.acme.ledger.PaymentProcessor",
                "com.acme.ledger.PaymentProcessor.Status",
                "com.acme.ledger.PaymentProcessor.Receipt",
                "com.acme.ledger.PaymentProcessor#<init>(LedgerRepository,List<RiskRule>)",
                "com.acme.ledger.PaymentProcessor#process(String)",
                "com.acme.ledger.PaymentProcessor#process(String,boolean)"
            );
        assertThat(result.dependencies())
            .extracting(JavaAstAnalyzer.RawDependency::targetNode)
            .contains("BaseProcessor", "SettlementPort", "LedgerRepository", "RiskRule", "AuditMeta", "FraudEngine")
            .doesNotContain("FakeCommentType", "FakeStringClass");
    }

    @Test
    void handlesMalformedJavaFilesGracefullyWithoutCrashing() {
        String broken = "public class Broken { void foo( { if (a) }";
        JavaAstAnalyzer.AstResult result = analyzer.analyze("src/main/java/Broken.java", "java", broken);

        assertThat(result.primaryNode()).isEqualTo("Broken");
        assertThat(result.symbols()).isNotEmpty();
    }
}
