package com.devmind.architecture;

import com.devmind.DevMindApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** Module boundaries and cycles, verified by Spring Modulith (ADR-0001, ARCH-2, ARCH-4). */
class ModularityTest {

    private static final ApplicationModules MODULES = ApplicationModules.of(DevMindApplication.class);

    @Test
    void modulesRespectBoundariesAndHaveNoCycles() {
        MODULES.verify();
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(MODULES).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
