package dev.swirlit.demo.order.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import com.tngtech.archunit.lang.ArchRule;
import dev.swirlit.demo.common.architecture.ArchitectureRules;

@AnalyzeClasses(packages = OrderArchitectureTest.MODULE_PACKAGE, importOptions = ImportOption.DoNotIncludeTests.class)
class OrderArchitectureTest {

    static final String MODULE_PACKAGE = "dev.swirlit.demo.order";

    @ArchTest
    static final ArchRule moduleBoundary = ArchitectureRules.dependsOnlyOnCommon(MODULE_PACKAGE);

    @ArchTest
    static final ArchTests codingRules = ArchTests.in(ArchitectureRules.CodingRules.class);

    @ArchTest
    static final ArchTests serviceRules = ArchTests.in(ArchitectureRules.ServiceRules.class);
}
