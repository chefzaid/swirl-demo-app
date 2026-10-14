package dev.swirlit.demo.common.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = ArchitectureRules.COMMON_PACKAGE, importOptions = ImportOption.DoNotIncludeTests.class)
class CommonArchitectureTest {

    @ArchTest
    static final ArchRule moduleBoundary = ArchitectureRules.dependsOnlyOnCommon(ArchitectureRules.COMMON_PACKAGE);

    @ArchTest
    static final ArchTests codingRules = ArchTests.in(ArchitectureRules.CodingRules.class);
}
