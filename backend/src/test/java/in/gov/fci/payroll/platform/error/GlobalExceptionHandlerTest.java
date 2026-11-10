package in.gov.fci.payroll.platform.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import in.gov.fci.payroll.platform.logging.LogCapture;
import in.gov.fci.payroll.platform.logging.MdcKeys;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

// Thread-local logging / security context: never alongside another such test.
@ResourceLock("thread-context")
class GlobalExceptionHandlerTest {

    record ItemRequest(@NotBlank String name, @Size(max = 5) String code) {}

    record Probe(@NotBlank String value) {}

    @RestController
    static class TestController {

        @PostMapping("/items")
        String create(@Valid @RequestBody ItemRequest request) {
            return "ok";
        }

        @PutMapping("/items/{id}")
        String update(@PathVariable String id, @RequestHeader("If-Match") String version) {
            return "ok";
        }

        @PostMapping("/commands")
        String command(@RequestHeader("Idempotency-Key") String key) {
            return "ok";
        }

        @GetMapping("/traced")
        String traced(@RequestHeader("X-Trace") String trace) {
            return "ok";
        }

        @GetMapping("/export-busy")
        String exportBusy() {
            throw new org.springframework.core.task.TaskRejectedException("pool full");
        }

        @GetMapping("/search")
        String search(@RequestParam @Max(100) Integer size) {
            return "ok";
        }

        @GetMapping("/fci")
        String fci() {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("connection failed password=hunter2");
        }

        @GetMapping("/denied")
        String denied() {
            throw new AccessDeniedException("denied");
        }

        @GetMapping("/unauthenticated")
        String unauthenticated() {
            throw new BadCredentialsException("bad");
        }

        @GetMapping("/stale")
        String stale() {
            throw new OptimisticLockingFailureException("version 3 != 4");
        }

        @GetMapping("/duplicate")
        String duplicate() {
            throw new DuplicateKeyException("E11000 duplicate key { email: \"x@fci.gov.in\" }");
        }

        @GetMapping("/integrity")
        String integrity() {
            throw new org.springframework.dao.DataIntegrityViolationException(
                    "Unique index or primary key violation: UK_USER_EMAIL ON USERS(EMAIL)");
        }

        @GetMapping("/tamper-audit")
        String tamperAudit() {
            // The trigger's message as the JDBC driver reports it, wrapped by Spring.
            throw new org.springframework.jdbc.UncategorizedSQLException(
                    "UPDATE audit_event",
                    "UPDATE audit_event SET reason = ?",
                    new java.sql.SQLException(
                            "FCIPAY-AUD-001: the audit trail is append-only.", "45000", 20110));
        }

        @GetMapping("/tamper-legacy")
        String tamperLegacy() {
            throw new org.springframework.dao.DataIntegrityViolationException(
                    "could not execute statement",
                    new java.sql.SQLException(
                            "ORA-20111: FCIPAY-AUD-002: migrated legacy audit records are"
                                    + " immutable."));
        }

        @GetMapping("/sql-other")
        String sqlOther() {
            throw new org.springframework.jdbc.UncategorizedSQLException(
                    "SELECT",
                    "SELECT secret_column FROM t",
                    new java.sql.SQLException("ORA-00942"));
        }

        @GetMapping("/down")
        String down() {
            throw new DataAccessResourceFailureException("Timed out after 5000 ms");
        }

        @GetMapping("/constraint")
        String constraint() {
            var validator = Validation.buildDefaultValidatorFactory().getValidator();
            throw new ConstraintViolationException(validator.validate(new Probe("")));
        }
    }

    private MockMvc mvc;

    private final java.util.List<Object> published = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-14T10:15:30Z"), ZoneOffset.UTC);
        ProblemDetailFactory factory =
                new ProblemDetailFactory("https://errors.fci.gov.in", null, clock);
        ProblemResponder responder =
                new ProblemResponder(factory, new ProblemDetailWriter(new JsonMapper()));
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        JsonMapper mapper =
                JsonMapper.builder()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .build();
        mvc =
                MockMvcBuilders.standaloneSetup(new TestController())
                        .setControllerAdvice(
                                new GlobalExceptionHandler(responder),
                                new DataAccessExceptionHandler(responder, published::add),
                                new SecurityExceptionHandler(responder))
                        .setValidator(validator)
                        .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                        .build();
        MDC.put(MdcKeys.CORRELATION_ID, "corr-test-0001");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void beanValidationFailureListsViolationsWithoutRejectedValues() throws Exception {
        mvc.perform(
                        post("/items")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"\",\"code\":\"TOOLONGVALUE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(
                        jsonPath("$.type")
                                .value("https://errors.fci.gov.in/request/validation-failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("COM-400-VALIDATION"))
                .andExpect(jsonPath("$.correlationId").value("corr-test-0001"))
                .andExpect(jsonPath("$.timestamp").value("2026-10-14T10:15:30Z"))
                .andExpect(jsonPath("$.instance").value("/items"))
                .andExpect(jsonPath("$.violations.length()").value(2))
                .andExpect(jsonPath("$.violations[?(@.field=='name')].code").value("NotBlank"))
                .andExpect(jsonPath("$.violations[?(@.field=='code')].code").value("Size"))
                .andExpect(
                        result ->
                                assertThat(result.getResponse().getContentAsString())
                                        .doesNotContain("TOOLONGVALUE"));
    }

    @Test
    void unknownPropertyIsRejectedAndNamedOnlyIfSafe() throws Exception {
        mvc.perform(
                        post("/items")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"a\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COM-400-VALIDATION"))
                .andExpect(jsonPath("$.violations[0].field").value("status"))
                .andExpect(jsonPath("$.violations[0].code").value("UnknownProperty"));
    }

    @Test
    void malformedJsonDoesNotEchoInput() throws Exception {
        mvc.perform(
                        post("/items")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": <script>"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COM-400-MALFORMED"))
                .andExpect(jsonPath("$.detail").value("The request could not be read."))
                .andExpect(
                        result ->
                                assertThat(result.getResponse().getContentAsString())
                                        .doesNotContain("script"));
    }

    @Test
    void missingIfMatchIs428AndMissingIdempotencyKeyIs400() throws Exception {
        mvc.perform(put("/items/1"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.errorCode").value("COM-428-IF-MATCH"));
        mvc.perform(post("/commands"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COM-400-IDEMPOTENCY-KEY"));
    }

    @Test
    void methodParameterValidationIsReported() throws Exception {
        mvc.perform(get("/search").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COM-400-VALIDATION"))
                .andExpect(jsonPath("$.violations[0].field").value("size"))
                .andExpect(jsonPath("$.violations[0].code").value("Max"));
    }

    @Test
    void constraintViolationExceptionIsReported() throws Exception {
        mvc.perform(get("/constraint"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("value"))
                .andExpect(jsonPath("$.violations[0].code").value("NotBlank"));
    }

    @Test
    void frameworkErrorsAreMappedToCatalogue() throws Exception {
        mvc.perform(post("/fci"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.errorCode").value("COM-405-METHOD"));
        mvc.perform(post("/items").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("COM-415-MEDIA-TYPE"));
    }

    /** SRS-EH-3: a missing header of no special meaning reads as a malformed request. */
    @Test
    void SRS_EH_3_aMissingOrdinaryHeaderIsAMalformedRequest() throws Exception {
        mvc.perform(get("/traced"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COM-400-MALFORMED"));
    }

    /** SRS-NF-5: a full export pool refuses the download at once instead of queueing it forever. */
    @Test
    void SRS_NF_5_aFullExportPoolRefusesTheDownload() throws Exception {
        mvc.perform(get("/export-busy"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("COM-503-EXPORT-BUSY"));
    }

    @Test
    void fciExceptionUsesItsErrorCode() throws Exception {
        mvc.perform(get("/fci"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COM-404-NOT-FOUND"))
                .andExpect(jsonPath("$.title").value("Not found"));
    }

    @Test
    void unexpectedErrorIsGeneric500LoggedOnceAtErrorWithMaskedMessage() throws Exception {
        try (LogCapture logs = LogCapture.ofThisThread(ProblemResponder.class, Level.INFO)) {
            mvc.perform(get("/boom"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.errorCode").value("COM-500-INTERNAL"))
                    .andExpect(
                            result ->
                                    assertThat(result.getResponse().getContentAsString())
                                            .doesNotContain(
                                                    "hunter2",
                                                    "IllegalStateException",
                                                    "at in.gov"));

            assertThat(logs.events()).hasSize(1);
            ILoggingEvent event = logs.events().get(0);
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .contains("errorCode=COM-500-INTERNAL", "password=***")
                    .doesNotContain("hunter2");
            assertThat(event.getThrowableProxy()).isNotNull();
        }
    }

    @Test
    void securityExceptionsMapTo403And401AndLogAtWarnWithoutStackTrace() throws Exception {
        try (LogCapture logs = LogCapture.ofThisThread(ProblemResponder.class, Level.INFO)) {
            mvc.perform(get("/denied"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("COM-403-DENIED"));
            mvc.perform(get("/unauthenticated"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("COM-401-AUTH"));

            assertThat(logs.events())
                    .allSatisfy(
                            e -> {
                                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                                assertThat(e.getThrowableProxy()).isNull();
                            });
        }
    }

    @Test
    void dataAccessErrorsDoNotLeakDatabaseMessages() throws Exception {
        mvc.perform(get("/stale"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.errorCode").value("COM-412-STALE"));
        mvc.perform(get("/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COM-409-DUPLICATE"))
                .andExpect(
                        result ->
                                assertThat(result.getResponse().getContentAsString())
                                        .doesNotContain("E11000", "x@fci.gov.in"));
        mvc.perform(get("/integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COM-409-CONFLICT"))
                .andExpect(
                        result ->
                                assertThat(result.getResponse().getContentAsString())
                                        .doesNotContain("UK_USER_EMAIL", "USERS"));
        mvc.perform(get("/down"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("COM-503-UNAVAILABLE"));
    }

    @Test
    void SRS_20_19_aDatabaseRefusalOfAHistoryChangeIsAnsweredAndPublishedForTheSecurityLog()
            throws Exception {
        mvc.perform(get("/tamper-audit"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("AUD-409-IMMUTABLE"))
                .andExpect(
                        result ->
                                assertThat(result.getResponse().getContentAsString())
                                        .doesNotContain("audit_event", "45000"));
        mvc.perform(get("/tamper-legacy"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("AUD-409-IMMUTABLE"));
        assertThat(published)
                .containsExactly(
                        new HistoryTamperAttempt("FCIPAY-AUD-001", "GET", "/tamper-audit"),
                        new HistoryTamperAttempt("FCIPAY-AUD-002", "GET", "/tamper-legacy"));
    }

    @Test
    void anOtherUncategorisedDatabaseErrorIsAGeneric500WithoutTheStatement() throws Exception {
        mvc.perform(get("/sql-other"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("COM-500-INTERNAL"))
                .andExpect(
                        result ->
                                assertThat(result.getResponse().getContentAsString())
                                        .doesNotContain("secret_column", "ORA-00942"));
        assertThat(published).isEmpty();
    }

    @Test
    void servicesNeverTranslateButReturnAMessageKey() throws Exception {
        mvc.perform(get("/fci").locale(Locale.forLanguageTag("hi-IN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not found"))
                .andExpect(jsonPath("$.messageKey").value("error.COM-404-NOT-FOUND"))
                .andExpect(jsonPath("$.args").isArray());
    }

    /**
     * Errors a service raises programmatically (no resolution codes, a field name taken from the
     * request) and errors raised outside a servlet request (a plain WebRequest) still become a
     * validation problem: the field is replaced by a placeholder, the code defaults to Invalid, and
     * the locale to English.
     */
    @Test
    void aValidationErrorWithoutCodesOrASafeFieldNameStillMakesAProblem() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-10-14T10:15:30Z"), ZoneOffset.UTC);
        GlobalExceptionHandler handler =
                new GlobalExceptionHandler(
                        new ProblemResponder(
                                new ProblemDetailFactory("https://errors.fci.gov.in", null, clock),
                                new ProblemDetailWriter(new JsonMapper())));
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "item");
        result.addError(new FieldError("item", "name; drop table", "must be given"));
        result.addError(
                new FieldError("item", "code", null, false, new String[0], null, "too long"));
        result.addError(new ObjectError("item", new String[] {"Mismatch"}, null, "dates cross"));
        MethodArgumentNotValidException invalid =
                new MethodArgumentNotValidException(
                        new MethodParameter(
                                TestController.class.getDeclaredMethod("create", ItemRequest.class),
                                0),
                        result);

        ResponseEntity<Object> response =
                handler.handleExceptionInternal(
                        invalid,
                        null,
                        new HttpHeaders(),
                        HttpStatus.BAD_REQUEST,
                        mock(WebRequest.class));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(response.getBody()))
                .contains(
                        "COM-400-VALIDATION",
                        "_field",
                        "Invalid",
                        "must be given",
                        "too long",
                        "_object",
                        "Mismatch",
                        "dates cross")
                .doesNotContain("drop table");
    }
}
