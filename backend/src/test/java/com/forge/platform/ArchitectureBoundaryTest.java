package com.forge.platform;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureBoundaryTest {

    private static JavaClasses importedClasses;

    @BeforeAll
    static void importClasses() {
        importedClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.forge.platform");
    }

    @Test
    void commonModuleMustNotDependOnBusinessModules() {
        noClasses()
            .that().resideInAPackage("com.forge.platform.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.forge.platform.identity..",
                "com.forge.platform.repository..",
                "com.forge.platform.codeintel..",
                "com.forge.platform.security..",
                "com.forge.platform.review..",
                "com.forge.platform.ci..",
                "com.forge.platform.analytics..",
                "com.forge.platform.bootstrap.."
            )
            .check(importedClasses);
    }

    @Test
    void auditModuleMustNotDependOnFeatureModules() {
        noClasses()
            .that().resideInAPackage("com.forge.platform.audit..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.forge.platform.repository..",
                "com.forge.platform.codeintel..",
                "com.forge.platform.security..",
                "com.forge.platform.review..",
                "com.forge.platform.ci..",
                "com.forge.platform.analytics.."
            )
            .check(importedClasses);
    }

    @Test
    void codeIntelMustNotDependOnReviewOrCi() {
        noClasses()
            .that().resideInAPackage("com.forge.platform.codeintel..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.forge.platform.review..",
                "com.forge.platform.ci..",
                "com.forge.platform.analytics.."
            )
            .check(importedClasses);
    }

    @Test
    void restControllersMustFollowNamingConvention() {
        classes()
            .that().areAnnotatedWith(RestController.class)
            .should().haveSimpleNameEndingWith("Controller")
            .check(importedClasses);
    }
}
