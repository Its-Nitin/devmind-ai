package com.devmind.architecture;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/**
 * The single home for every {@code [arch]} rule in docs/coding-standards.md (TEST-10). Module boundaries and cycles
 * are checked separately by {@link ModularityTest}. Add a rule here whenever the standards gain one.
 */
@AnalyzeClasses(packages = "com.devmind", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** A module's top-level package, e.g. {@code com.devmind.project} (its public API). */
    private static final String MODULE_API = "com.devmind.*";

    // ARCH-3: public APIs hold only interfaces, records, enums and events.
    @ArchTest
    static final ArchRule moduleApisExposeNoImplementations = noClasses()
            .that()
            .resideInAPackage(MODULE_API)
            .should()
            .beAnnotatedWith(Entity.class)
            .orShould()
            .beAnnotatedWith(Service.class)
            .orShould()
            .beAnnotatedWith(Component.class)
            .orShould()
            .beAnnotatedWith(Repository.class)
            .orShould()
            .beAnnotatedWith(Controller.class)
            .orShould()
            .beAnnotatedWith(RestController.class)
            .because("ARCH-3: a module's public API contains only interfaces, records, enums and events");

    // ARCH-5: domain depends on nothing outward-facing.
    @ArchTest
    static final ArchRule domainIsPure = noClasses()
            .that()
            .resideInAPackage("..internal.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..internal.web..",
                    "..internal.persistence..",
                    "..internal.application..",
                    "org.springframework.web..",
                    "org.springframework.jdbc..",
                    "org.springframework.ai..")
            .because("ARCH-5: domain must not depend on web, persistence, Spring MVC, JdbcClient or AI adapters");

    // ARCH-8: AI SDK types stay inside the ai module's internals.
    @ArchTest
    static final ArchRule aiSdkConfined = noClasses()
            .that()
            .resideOutsideOfPackage("com.devmind.ai.internal..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework.ai..")
            .because("ARCH-8: Spring AI / provider SDK types appear only in com.devmind.ai.internal");

    // API-1: controllers (and their DTOs) live in the web layer.
    @ArchTest
    static final ArchRule controllersOnlyInWeb = classes()
            .that()
            .areAnnotatedWith(RestController.class)
            .or()
            .areAnnotatedWith(Controller.class)
            .should()
            .resideInAPackage("..internal.web..")
            .because("API-1: request handling lives in <module>.internal.web");

    // ADR-0005 / DB-4: JPA entities never leave the persistence layer.
    @ArchTest
    static final ArchRule entitiesOnlyInPersistence = classes()
            .that()
            .areAnnotatedWith(Entity.class)
            .should()
            .resideInAPackage("..internal.persistence..")
            .because("ADR-0005: JPA entities live only in <module>.internal.persistence");

    // JAVA-6: constructor injection only.
    @ArchTest
    static final ArchRule noFieldInjection = noFields()
            .should()
            .beAnnotatedWith(Autowired.class)
            .because("JAVA-6: use constructor injection with final fields");

    // JAVA-9: time comes from an injected Clock; Instant over LocalDateTime.
    @ArchTest
    static final ArchRule noClockBypass = noClasses()
            .should()
            .callMethod(Instant.class, "now")
            .orShould()
            .callMethod(LocalDateTime.class, "now")
            .orShould()
            .callMethod(LocalDate.class, "now")
            .orShould()
            .callMethod(OffsetDateTime.class, "now")
            .orShould()
            .callMethod(ZonedDateTime.class, "now")
            .orShould()
            .callMethod(System.class, "currentTimeMillis")
            .because("JAVA-9: read time from the injected Clock");

    @ArchTest
    static final ArchRule noLocalDateTimeFields = noFields()
            .should()
            .haveRawType(LocalDateTime.class)
            .because("JAVA-9: store and transmit Instant (UTC), never LocalDateTime");

    // JAVA-11: concurrency via virtual threads / structured executors.
    @ArchTest
    static final ArchRule noRawThreads = noClasses()
            .should()
            .callConstructorWhere(target(owner(assignableTo(Thread.class))))
            .because("JAVA-11: no raw new Thread; use virtual threads or structured executors");

    // SEC-4: never invoke a shell.
    @ArchTest
    static final ArchRule noRuntimeExec = noClasses()
            .should()
            .callMethodWhere(target(owner(equivalentTo(Runtime.class))).and(target(name("exec"))))
            .because("SEC-4: never invoke a shell; use ProcessBuilder with a fixed executable (M8 only)");

    // SPRING-1: typed @ConfigurationProperties, no scattered @Value.
    @ArchTest
    static final ArchRule noValueInjection = noClasses()
            .should()
            .dependOnClassesThat()
            .areAssignableTo(Value.class)
            .because("SPRING-1: bind configuration to validated @ConfigurationProperties records");

    // SPRING-2: transactions belong on application services.
    @ArchTest
    static final ArchRule noTransactionsOutsideApplicationLayer = noClasses()
            .that()
            .resideInAnyPackage("..internal.web..", "..internal.persistence..")
            .should()
            .beAnnotatedWith(Transactional.class)
            .because("SPRING-2: declare transactions on application services");

    @ArchTest
    static final ArchRule noTransactionalMethodsOutsideApplicationLayer = methods()
            .that()
            .areDeclaredInClassesThat()
            .resideInAnyPackage("..internal.web..", "..internal.persistence..")
            .should()
            .notBeAnnotatedWith(Transactional.class)
            .because("SPRING-2: declare transactions on application services");
}
