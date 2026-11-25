package com.forge.platform.codeintel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaAstAnalyzerTest {

    private final JavaAstAnalyzer analyzer = new JavaAstAnalyzer();

    @Test
    void parsesJavaClassMethodsComplexityAndDependencies() {
        String source = """
            package com.acme.ledger;

            import org.springframework.stereotype.Service;

            @Service
            public class PaymentProcessor {
                private final LedgerRepository ledgerRepository;
                private final RiskEngine riskEngine;

                public PaymentProcessor(LedgerRepository ledgerRepository, RiskEngine riskEngine) {
                    this.ledgerRepository = ledgerRepository;
                    this.riskEngine = riskEngine;
                }

                public String process(String id, boolean urgent) {
                    if (id == null || id.isBlank()) {
                        throw new IllegalArgumentException("id required");
                    }
                    if (urgent && riskEngine.isHighRisk(id)) {
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
            .extracting(JavaAstAnalyzer.ParsedSymbol::name)
            .contains("PaymentProcessor", "process");
        assertThat(result.dependencies())
            .extracting(JavaAstAnalyzer.RawDependency::targetNode)
            .contains("LedgerRepository", "RiskEngine");
    }
}
