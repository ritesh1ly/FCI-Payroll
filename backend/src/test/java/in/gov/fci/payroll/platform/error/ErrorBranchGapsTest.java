package in.gov.fci.payroll.platform.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Edge cases of the error model (SRS-EH-1, SRS-20-19): the code of a violation is the last segment
 * of its most general message code; an unknown property without a name is reported under the
 * placeholder field; a server error without an exception is still logged once; an exception without
 * violations carries an empty list; a cause chain whose cause is itself ends instead of looping.
 */
@ResourceLock("thread-context")
class ErrorBranchGapsTest {

    private final ProblemResponder responder =
            new ProblemResponder(
                    new ProblemDetailFactory(
                            "https://errors.fci.gov.in",
                            null,
                            Clock.fixed(Instant.parse("2026-10-14T10:15:30Z"), ZoneOffset.UTC)),
                    new ProblemDetailWriter(new JsonMapper()));
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(responder);

    @Test
    void SRS_EH_1_aDottedMessageCodeIsReportedByItsLastSegment() throws Exception {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "item");
        result.addError(new ObjectError("item", new String[] {"item.dates.Crossed"}, null, "x"));
        MethodArgumentNotValidException invalid =
                new MethodArgumentNotValidException(
                        new org.springframework.core.MethodParameter(
                                Object.class.getDeclaredMethod("equals", Object.class), 0),
                        result);
        ResponseEntity<Object> response =
                handler.handleExceptionInternal(
                        invalid,
                        null,
                        new HttpHeaders(),
                        HttpStatus.BAD_REQUEST,
                        mock(WebRequest.class));
        assertThat(String.valueOf(response.getBody())).contains("Crossed").doesNotContain("item.");
    }

    @Test
    void SRS_EH_1_anUnknownPropertyWithoutANameIsReportedUnderThePlaceholderField() {
        UnrecognizedPropertyException unknown = mock(UnrecognizedPropertyException.class);
        when(unknown.getPropertyName()).thenReturn(null);
        HttpMessageNotReadableException unreadable =
                new HttpMessageNotReadableException(
                        "unknown", unknown, mock(HttpInputMessage.class));
        ResponseEntity<Object> response =
                handler.handleExceptionInternal(
                        unreadable,
                        null,
                        new HttpHeaders(),
                        HttpStatus.BAD_REQUEST,
                        mock(WebRequest.class));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(response.getBody()))
                .contains("COM-400-VALIDATION", "_field", "UnknownProperty");
    }

    @Test
    void SRS_EH_1_aServerErrorWrittenWithoutAnExceptionIsStillAnswered() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        responder.write(
                null,
                CommonErrorCode.INTERNAL_ERROR,
                new MockHttpServletRequest("GET", "/api/v1/x"),
                response);
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains(CommonErrorCode.INTERNAL_ERROR.code());
    }

    @Test
    void SRS_EH_1_anExceptionWithoutViolationsCarriesAnEmptyListAndCopiesGivenOnes() {
        assertThat(
                        new FciException(CommonErrorCode.VALIDATION_FAILED, null, null, null)
                                .getViolations())
                .isEmpty();
        List<Violation> given = new java.util.ArrayList<>();
        given.add(new Violation("a", "NotBlank", "must be given"));
        FciException copied =
                new FciException(CommonErrorCode.VALIDATION_FAILED, null, given, null);
        given.clear();
        assertThat(copied.getViolations()).hasSize(1);
    }

    @Test
    void SRS_20_19_aCauseChainWhoseCauseIsItselfEndsInsteadOfLooping() {
        RuntimeException selfCaused =
                new RuntimeException("no audit code here") {
                    @Override
                    public synchronized Throwable getCause() {
                        return this;
                    }
                };
        assertThat(HistoryTamperAttempt.codeIn(selfCaused)).isNull();
        RuntimeException selfCausedTamper =
                new RuntimeException("ORA-20001: FCIPAY-AUD-002 read only") {
                    @Override
                    public synchronized Throwable getCause() {
                        return this;
                    }
                };
        assertThat(HistoryTamperAttempt.codeIn(selfCausedTamper)).isEqualTo("FCIPAY-AUD-002");
    }
}
