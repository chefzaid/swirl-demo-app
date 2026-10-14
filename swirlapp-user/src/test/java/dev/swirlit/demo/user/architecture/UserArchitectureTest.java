package dev.swirlit.demo.user.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import com.tngtech.archunit.lang.ArchRule;
import dev.swirlit.demo.common.architecture.ArchitectureRules;

@AnalyzeClasses(packages = UserArchitectureTest.MODULE_PACKAGE, importOptions = ImportOption.DoNotIncludeTests.class)
class UserArchitectureTest {

    static final String MODULE_PACKAGE = "dev.swirlit.demo.user";

    @ArchTest
    static final ArchRule moduleBoundary = ArchitectureRules.dependsOnlyOnCommon(MODULE_PACKAGE);

    @ArchTest
    static final ArchTests codingRules = ArchTests.in(ArchitectureRules.CodingRules.class);

    @ArchTest
    static final ArchTests serviceRules = ArchTests.in(ArchitectureRules.ServiceRules.class);
}
