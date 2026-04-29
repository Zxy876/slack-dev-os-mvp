package com.asyncaiflow.service;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class ActionCapabilityResolverTest {

    @Test
    void defaultsToSameNameCapabilityWhenNoMappingProvided() {
        ActionCapabilityResolver resolver = new ActionCapabilityResolver();

        assertEquals("design_solution", resolver.resolveRequiredCapability("design_solution"));
        assertEquals("review_code", resolver.resolveRequiredCapability("  review_code  "));
    }

    @Test
    void usesConfiguredCapabilityMappingWhenPresent() {
        ActionCapabilityResolver resolver = new ActionCapabilityResolver();
        resolver.setCapabilityMapping(Map.of(
                "design_solution", "solution_planner",
                "review_code", "code_reviewer"
        ));

        assertEquals("solution_planner", resolver.resolveRequiredCapability("design_solution"));
        assertEquals("code_reviewer", resolver.resolveRequiredCapability("review_code"));
        assertEquals("search_code", resolver.resolveRequiredCapability("search_code"));
    }

    @Test
    void supportsCompatibilityMappingForAnalyzeModule() {
        ActionCapabilityResolver resolver = new ActionCapabilityResolver();
        resolver.setCapabilityMapping(Map.of("analyze_module", "read_file"));

        assertEquals("read_file", resolver.resolveRequiredCapability("analyze_module"));
    }
}
