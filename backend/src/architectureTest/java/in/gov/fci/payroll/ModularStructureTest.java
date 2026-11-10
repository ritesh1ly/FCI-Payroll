package in.gov.fci.payroll;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * The modular monolith's rules, checked on the compiled classes:
 *
 * <ul>
 *   <li>Spring Modulith: modules ({@code in.gov.fci.payroll.<module>}) have no dependency cycles
 *       and use another module only through its {@code api} package (platform is open);
 *   <li>ArchUnit, within every module: {@code web → orchestration → business}; business is pure (no
 *       web, orchestration, persistence, batch, servlet or Spring MVC); persistence and batch never
 *       reach the web layer; nothing but web uses Spring MVC.
 * </ul>
 */
class ModularStructureTest {

    private static final String BASE = "in.gov.fci.payroll";
    private static final ApplicationModules MODULES =
            ApplicationModules.of(FciPayrollApplication.class);

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages(BASE);
    }

    @Test
    void modulesRespectTheirBoundaries() {
        MODULES.verify();
    }

    @Test
    void businessLayerIsPure() {
        rule(
                noClasses()
                        .that()
                        .resideInAPackage(BASE + ".*.business..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage(
                                BASE + ".*.web..",
                                BASE + ".*.orchestration..",
                                BASE + ".*.persistence..",
                                BASE + ".*.batch..",
                                "org.springframework.web..",
                                "org.springframework.batch..",
                                "jakarta.servlet..")
                        .because("business holds the domain model and rules only"));
    }

    @Test
    void orchestrationDoesNotKnowTheWebLayer() {
        rule(
                noClasses()
                        .that()
                        .resideInAPackage(BASE + ".*.orchestration..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage(BASE + ".*.web..", "jakarta.servlet..")
                        .because("use cases are independent of the transport"));
    }

    @Test
    void persistenceAndBatchDoNotKnowTheWebLayer() {
        rule(
                noClasses()
                        .that()
                        .resideInAnyPackage(BASE + ".*.persistence..", BASE + ".*.batch..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAPackage(BASE + ".*.web.."));
    }

    @Test
    void webLayerDoesNotUseRepositories() {
        rule(
                noClasses()
                        .that()
                        .resideInAPackage(BASE + ".*.web..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAPackage(BASE + ".*.persistence..")
                        .because("controllers go through the orchestration layer"));
    }

    @Test
    void onlyTheWebLayerDefinesControllers() {
        rule(
                noClasses()
                        .that()
                        .resideOutsideOfPackages(BASE + ".*.web..", BASE + ".platform..")
                        .should()
                        .beAnnotatedWith("org.springframework.web.bind.annotation.RestController"));
    }

    /**
     * BR-INT-014 (FRS l.4675; SRS-INT-23 l.5105; US-23-02 AC4): every interface is implemented in
     * the integration layer, which maps the external format to the Payroll model, so a format
     * change touches only that layer: an adapter (an {@code InterfaceAdapter}) is a class of the
     * integration module, and no computation or business module depends on an adapter or on the
     * integration module's internals.
     */
    @Test
    void BR_INT_014_noPayrollModuleDependsOnAnInterfaceAdapterOrTheIntegrationInternals() {
        rule(
                noClasses()
                        .that()
                        .resideOutsideOfPackage(BASE + ".integration..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage(
                                BASE + ".integration.orchestration..",
                                BASE + ".integration.business..",
                                BASE + ".integration.web..")
                        .orShould()
                        .dependOnClassesThat()
                        .haveFullyQualifiedName(BASE + ".integration.api.InterfaceAdapter"));
        rule(
                ArchRuleDefinition.classes()
                        .that()
                        .implement(BASE + ".integration.api.InterfaceAdapter")
                        .should()
                        .resideInAPackage(BASE + ".integration..")
                        .allowEmptyShould(true));
    }

    /**
     * Jackson 3 ({@code tools.jackson}) is the JSON library of Spring Boot 4 and of this code base.
     * Jackson 2 databind is still on the classpath only through springdoc's swagger-core: code that
     * imported it would compile, yet find no Jackson 2 mapper at run time and behave differently
     * from the HTTP layer. Annotations ({@code com.fasterxml.jackson.annotation}) are shared by
     * both.
     */
    @Test
    void codeUsesJacksonThreeNotJacksonTwo() {
        rule(
                noClasses()
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage(
                                "com.fasterxml.jackson.databind..", "com.fasterxml.jackson.core..")
                        .because("Spring Boot 4 configures Jackson 3 (tools.jackson) only"));
    }

    /**
     * SRS §6.4 (l.1204, FRS §6.5.6): "The system writes every audit record automatically; nobody
     * triggers or approves it" (US-GAP-131). The audit row store is reached only by {@code
     * AuditService} (which joins the caller's transaction); the audit module's own controllers
     * answer reads, the retention setting (SRS-20-5) and the refusal of every write to history
     * (SRS-20-19) - none creates, triggers or approves an audit row.
     */
    @Test
    void SRS_20_2_auditRowsAreWrittenOnlyByTheAuditServiceAndNoEndpointCreatesOne() {
        rule(
                noClasses()
                        .that()
                        .doNotHaveFullyQualifiedName(BASE + ".audit.api.AuditService")
                        .should()
                        .callMethod(
                                BASE + ".audit.persistence.AuditRepositories$Audit",
                                "save",
                                BASE + ".audit.business.model.AuditEvent")
                        .because("an audit row is written only with the change it records"));
        String web = "org.springframework.web.bind.annotation.";
        Set<String> allowedWrites =
                Set.of(
                        // the Administrator's retention period setting (SRS-20-5), not an audit row
                        "RetentionController.proposeRetention",
                        // SRS-20-19: refuses (and logs) every write to history
                        "RecordHistoryController.refuseHistoryChange");
        List<String> writes = new java.util.ArrayList<>();
        for (JavaClass c : classes) {
            if (!c.getPackageName().startsWith(BASE + ".audit.web")) {
                continue;
            }
            for (JavaMethod m : c.getMethods()) {
                boolean write =
                        m.isAnnotatedWith(web + "PostMapping")
                                || m.isAnnotatedWith(web + "PutMapping")
                                || m.isAnnotatedWith(web + "PatchMapping")
                                || m.isAnnotatedWith(web + "DeleteMapping")
                                || m.isAnnotatedWith(web + "RequestMapping");
                String name = c.getSimpleName() + "." + m.getName();
                if (write && !allowedWrites.contains(name)) {
                    writes.add(name);
                }
            }
        }
        org.assertj.core.api.Assertions.assertThat(writes)
                .as("audit endpoints that could create, trigger or approve an audit row")
                .isEmpty();
    }

    @Test
    void writeModuleDocumentation() {
        String dir = System.getProperty("fci.modules.docs");
        if (dir != null) {
            new Documenter(MODULES, Documenter.Options.defaults().withOutputFolder(dir))
                    .writeModulesAsPlantUml()
                    .writeIndividualModulesAsPlantUml()
                    .writeModuleCanvases();
        }
    }

    /**
     * R-RBAC (OWASP A01 broken access control): every API operation declares its permission gate.
     * The security chain only requires a session ("authenticated"), so an endpoint without
     * {@code @PreAuthorize} would be open to every signed-in user, eSeva members included. The few
     * that must serve every signed-in user (or no one yet) are listed here by name, each for its
     * reason.
     */
    @Test
    void everyApiOperationDeclaresItsPermissionGate() {
        Set<String> open =
                Set.of(
                        // sign-in, sign-out, password and OTP self-service: before or without a
                        // role
                        "AuthController",
                        // the signed-in user's own profile, scopes and preferences
                        "MeController",
                        // UI labels and reference lists every screen needs (no personal data)
                        "I18nController",
                        "ReferenceController",
                        // the payroll periods board of the user's own office
                        "PeriodController",
                        // the order document types an upload form lists (no personal data)
                        "DocumentTypeController",
                        // History button / X-04: the gate depends on the record type, so the
                        // service checks VIEW on the record's own screen (or A-05) at its office
                        // (RecordHistoryService.authorise, LegacyAuditService); writes refused
                        "RecordHistoryController");
        String web = "org.springframework.web.bind.annotation.";
        List<String> mappings =
                List.of(
                        "GetMapping",
                        "PostMapping",
                        "PutMapping",
                        "PatchMapping",
                        "DeleteMapping",
                        "RequestMapping");
        rule(
                ArchRuleDefinition.methods()
                        .that()
                        .areDeclaredInClassesThat()
                        .areAnnotatedWith(web + "RestController")
                        .and()
                        .areDeclaredInClassesThat(
                                DescribedPredicate.describe(
                                        "are not an open controller",
                                        (JavaClass c) -> !open.contains(c.getSimpleName())))
                        .and(
                                DescribedPredicate.describe(
                                        "handle a request",
                                        (JavaMethod m) ->
                                                mappings.stream()
                                                        .anyMatch(a -> m.isAnnotatedWith(web + a))))
                        .should()
                        .beAnnotatedWith(
                                "org.springframework.security.access.prepost.PreAuthorize"));
    }

    /**
     * R-ASCII at the request boundary (the database character set is US7ASCII): every free-text
     * {@code String} a request brings in - a component of a request record of a web package, or a
     * form parameter of a POST / PUT / PATCH handler - carries {@code @PrintableAscii} or a
     * {@code @Pattern}, is a {@code @Sensitive} secret (hashed, never stored as text), or is one of
     * the exemptions below with its reason. A new field without one fails the build.
     */
    @Test
    void everyRequestTextIsPrintableAscii() {
        Set<String> exempt =
                Set.of(
                        // Hindi labels: stored in label_master (Unicode), the one home of Hindi.
                        "NewValueRequest.labelHi", "ChangeRequest.labelHi", "LabelRequest.text");
        String ascii = "in.gov.fci.payroll.platform.validation.PrintableAscii";
        String pattern = "jakarta.validation.constraints.Pattern";
        String sensitive = "in.gov.fci.payroll.platform.logging.Sensitive";
        String web = "org.springframework.web.bind.annotation.";
        // The request records: every @RequestBody type, and by convention every *Request record
        // of a web package (nested ones included, e.g. the levels of a route).
        Set<JavaClass> requests = new java.util.HashSet<>();
        for (JavaClass c : classes) {
            for (JavaMethod m : c.getMethods()) {
                for (com.tngtech.archunit.core.domain.JavaParameter p : m.getParameters()) {
                    if (p.isAnnotatedWith(web + "RequestBody")) {
                        requests.add(p.getRawType());
                    }
                }
            }
            if (c.getPackageName().contains(".web")
                    && c.isAssignableTo(Record.class)
                    && (c.getSimpleName().endsWith("Request") || c.getSimpleName().equals("Otp"))) {
                requests.add(c);
            }
        }
        List<String> missing = new java.util.ArrayList<>();
        for (JavaClass c : requests) {
            if (!c.isAssignableTo(Record.class)) {
                continue;
            }
            for (com.tngtech.archunit.core.domain.JavaField f : c.getFields()) {
                String name = c.getSimpleName() + "." + f.getName();
                if (f.getRawType().isEquivalentTo(String.class)
                        && !f.isAnnotatedWith(ascii)
                        && !f.isAnnotatedWith(pattern)
                        && !f.isAnnotatedWith(sensitive)
                        && !exempt.contains(name)) {
                    missing.add(c.getName() + "." + f.getName());
                }
            }
        }
        for (JavaClass c : classes) {
            if (!c.isAnnotatedWith(web + "RestController")) {
                continue;
            }
            for (JavaMethod m : c.getMethods()) {
                boolean writes =
                        m.isAnnotatedWith(web + "PostMapping")
                                || m.isAnnotatedWith(web + "PutMapping")
                                || m.isAnnotatedWith(web + "PatchMapping");
                if (!writes) {
                    continue;
                }
                for (com.tngtech.archunit.core.domain.JavaParameter p : m.getParameters()) {
                    boolean form =
                            p.isAnnotatedWith(web + "RequestParam")
                                    || p.isAnnotatedWith(web + "RequestPart");
                    if (form
                            && p.getRawType().isEquivalentTo(String.class)
                            && !p.isAnnotatedWith(ascii)
                            && !p.isAnnotatedWith(pattern)
                            && !p.isAnnotatedWith(sensitive)) {
                        missing.add(m.getFullName() + " parameter " + p.getIndex());
                    }
                }
            }
        }
        org.assertj.core.api.Assertions.assertThat(missing)
                .as("request text without @PrintableAscii, @Pattern or @Sensitive")
                .isEmpty();
    }

    private static void rule(ArchRule rule) {
        rule.check(classes);
    }
}
