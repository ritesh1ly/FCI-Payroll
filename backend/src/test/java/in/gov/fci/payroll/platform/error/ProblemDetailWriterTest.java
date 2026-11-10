package in.gov.fci.payroll.platform.error;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.platform.logging.MdcKeys;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ProblemDetailWriterTest {

    private final JsonMapper mapper = new JsonMapper();
    private final ProblemDetailFactory factory =
            new ProblemDetailFactory(
                    "https://errors.fci.gov.in/",
                    null,
                    Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void writesFlattenedProblemJson() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/bff/v1/auth/login");
        request.setAttribute(MdcKeys.CORRELATION_ATTRIBUTE, "corr-from-attribute");
        ProblemDetail problem =
                factory.create(
                        CommonErrorCode.VALIDATION_FAILED,
                        request,
                        null,
                        List.of(new Violation("username", "Pattern", "Invalid username.")));
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ProblemDetailWriter(mapper).write(response, problem);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode body = mapper.readTree(response.getContentAsString());
        assertThat(body.get("type").asString())
                .isEqualTo("https://errors.fci.gov.in/request/validation-failed");
        assertThat(body.get("errorCode").asString()).isEqualTo("COM-400-VALIDATION");
        assertThat(body.get("correlationId").asString()).isEqualTo("corr-from-attribute");
        assertThat(body.get("instance").asString()).isEqualTo("/bff/v1/auth/login");
        assertThat(body.get("violations").get(0).get("field").asString()).isEqualTo("username");
        assertThat(body.has("properties")).isFalse();
        assertThat(body.get("severity").asString()).isEqualTo("BLOCKING");
        assertThat(body.get("actionKey").asString()).isEqualTo("error.COM-400-VALIDATION.action");
    }

    @Test
    void aBareProblemIsWrittenWithoutDetailInstanceOrProperties() {
        ProblemDetail bare =
                ProblemDetail.forStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        java.util.Map<String, Object> body = ProblemDetailWriter.toMap(bare);
        assertThat(body).containsOnlyKeys("type", "title", "status");
        assertThat(body.get("status")).isEqualTo(503);
        ProblemDetail full =
                ProblemDetail.forStatusAndDetail(
                        org.springframework.http.HttpStatus.CONFLICT, "Changed meanwhile");
        full.setInstance(java.net.URI.create("/bff/v1/x"));
        full.setProperty("errorCode", "COM-409");
        assertThat(ProblemDetailWriter.toMap(full))
                .containsEntry("detail", "Changed meanwhile")
                .containsEntry("instance", "/bff/v1/x")
                .containsEntry("errorCode", "COM-409");
    }

    /** SRS-EH-1, SRS-EH-5 (US-GAP-22): a refused access is a Security condition. */
    @Test
    void SRS_EH_5_aRefusedAccessIsASecurityCondition() {
        for (CommonErrorCode code : CommonErrorCode.values()) {
            ProblemDetail problem = factory.create(code, null, null, null);
            assertThat(problem.getProperties())
                    .containsEntry(
                            "severity", MessageSeverity.ofStatus(code.status().value()).name());
        }
        assertThat(MessageSeverity.ofStatus(401)).isEqualTo(MessageSeverity.SECURITY);
        assertThat(MessageSeverity.ofStatus(403)).isEqualTo(MessageSeverity.SECURITY);
        assertThat(MessageSeverity.ofStatus(423)).isEqualTo(MessageSeverity.SECURITY);
        assertThat(MessageSeverity.ofStatus(400)).isEqualTo(MessageSeverity.BLOCKING);
        assertThat(MessageSeverity.ofStatus(402)).isEqualTo(MessageSeverity.BLOCKING);
        assertThat(MessageSeverity.ofStatus(404)).isEqualTo(MessageSeverity.BLOCKING);
        assertThat(MessageSeverity.ofStatus(422)).isEqualTo(MessageSeverity.BLOCKING);
        assertThat(MessageSeverity.ofStatus(424)).isEqualTo(MessageSeverity.BLOCKING);
        assertThat(MessageSeverity.values()).hasSize(4);
    }

    @Test
    void doesNotWriteToCommittedResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);
        new ProblemDetailWriter(mapper)
                .write(response, factory.create(CommonErrorCode.INTERNAL_ERROR, null, null, null));
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void omitsUnsafeInstancePath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.setRequestURI("/x/<script>");
        ProblemDetail problem = factory.create(CommonErrorCode.NOT_FOUND, request, null, null);
        assertThat(problem.getInstance()).isNull();
        assertThat(problem.getProperties()).doesNotContainKey("violations");
    }

    @Test
    void statusMappingCoversCatalogue() {
        assertThat(CommonErrorCode.forStatus(404)).isEqualTo(CommonErrorCode.NOT_FOUND);
        assertThat(CommonErrorCode.forStatus(418)).isEqualTo(CommonErrorCode.MALFORMED_REQUEST);
        assertThat(CommonErrorCode.forStatus(502)).isEqualTo(CommonErrorCode.INTERNAL_ERROR);
        for (CommonErrorCode code : CommonErrorCode.values()) {
            assertThat(code.code()).matches("^COM-\\d{3}-[A-Z-]+$");
            assertThat(code.titleKey()).isEqualTo("error." + code.code() + ".title");
            assertThat(
                            factory.message(
                                    code.detailKey(),
                                    null,
                                    null,
                                    java.util.Locale.forLanguageTag("hi")))
                    .as("English detail for %s, whatever the requested locale", code)
                    .isNotBlank();
            assertThat(ProblemDetailFactory.messageKey(code)).isEqualTo("error." + code.code());
        }
    }

    @Test
    void fciExceptionCarriesCodeAsMessageOnly() {
        FciException ex =
                new FciException(
                        CommonErrorCode.CONFLICT,
                        new Object[] {"x"},
                        List.of(new Violation("f", "c", "m")),
                        null);
        assertThat(ex).hasMessage("COM-409-CONFLICT");
        assertThat(ex.getMessageArgs()).containsExactly("x");
        assertThat(ex.getViolations()).hasSize(1);
        assertThat(
                        new FciException(CommonErrorCode.CONFLICT, new IllegalStateException())
                                .getCause())
                .isInstanceOf(IllegalStateException.class);
    }
}
