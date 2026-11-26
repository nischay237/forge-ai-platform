package com.forge.platform.codeintel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TypeScriptJsAnalyzerTest {

    private final TypeScriptJsAnalyzer analyzer = new TypeScriptJsAnalyzer();

    @Test
    void parsesTypeScriptDeclarationsImportsInheritanceAndIgnoresCommentsAndStrings() {
        String ts = """
            // import { FakeCommentImport } from './fakeComment';
            /*
             * export class FakeBlockCommentClass {}
             */
            import { verifySignature } from './webhookVerifier';
            import type { AuditEvent } from '../audit/auditTypes';
            const legacy = require('./legacyAdapter');

            export interface CheckoutHandler extends BaseHandler {
              handle(payload: string): boolean;
            }

            export type PaymentStatus = 'PENDING' | 'PAID';
            export enum CurrencyCode { USD, INR }

            export class EdgeCheckoutService extends AbstractService implements CheckoutHandler {
              handle(payload: string): boolean {
                const msg = "class FakeStringClass {} if (true && false)";
                if (!payload || payload.length === 0) {
                  return false;
                }
                return verifySignature(payload, 'k', 'h');
              }
            }

            export const normalizePayload = (raw: string) => raw.trim();
            """;

        JavaAstAnalyzer.AstResult result = analyzer.analyze("src/edge/edgeCheckoutService.ts", ts);

        assertThat(result.primaryNode()).isEqualTo("edgeCheckoutService");
        assertThat(result.symbols())
            .extracting(JavaAstAnalyzer.ParsedSymbol::name)
            .contains("CheckoutHandler", "PaymentStatus", "CurrencyCode", "EdgeCheckoutService", "normalizePayload")
            .doesNotContain("FakeBlockCommentClass", "FakeStringClass");
        assertThat(result.dependencies())
            .extracting(JavaAstAnalyzer.RawDependency::targetNode)
            .contains("webhookVerifier", "auditTypes", "legacyAdapter", "BaseHandler", "AbstractService", "CheckoutHandler")
            .doesNotContain("fakeComment", "FakeCommentImport");
        assertThat(result.fileComplexity()).isGreaterThanOrEqualTo(3);
    }
}
