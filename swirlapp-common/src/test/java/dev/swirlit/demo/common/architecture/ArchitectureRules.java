package dev.swirlit.demo.common.architecture;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

/** Architecture rules shared by the common module and every service module. */
public final class ArchitectureRules {

    public static final String ROOT_PACKAGE = "dev.swirlit.demo";
    public static final String COMMON_PACKAGE = ROOT_PACKAGE + ".common";

    /** Coding rules every module follows. Include with {@code ArchTests.in(ArchitectureRules.CodingRules.class)}. */
    public static final class CodingRules {
        @ArchTest
        static final ArchRule constructorInjection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
        @ArchTest
        static final ArchRule structuredLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
        @ArchTest
        static final ArchRule noStandardStreams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

        private CodingRules() {
        }
    }

    /** Layering every service follows. Include with {@code ArchTests.in(ArchitectureRules.ServiceRules.class)}. */
    public static final class ServiceRules {
        @ArchTest
        static final ArchRule layers = layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Controller").definedBy("..controller..")
                .layer("Service").definedBy("..service..")
                .layer("Repository").definedBy("..repository..")
                .layer("Config").definedBy("..config..")
                .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
                .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
                .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service", "Config");

        @ArchTest
        static final ArchRule domainStaysIndependent = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..controller..", "..service..", "..repository..", "..dto..", "..config..");

        private ServiceRules() {
        }
    }

    private ArchitectureRules() {
    }

    /**
     * A module may use only its own classes and the common module, so services stay
     * independently deployable and the common module never reaches into a service.
     */
    public static ArchRule dependsOnlyOnCommon(String modulePackage) {
        return noClasses()
                .that().resideInAPackage(modulePackage + "..")
                .should().dependOnClassesThat(resideInAPackage(ROOT_PACKAGE + "..")
                        .and(not(resideInAnyPackage(modulePackage + "..", COMMON_PACKAGE + ".."))))
                .as("classes in " + modulePackage + " should depend only on their own module and "
                        + COMMON_PACKAGE);
    }
}
