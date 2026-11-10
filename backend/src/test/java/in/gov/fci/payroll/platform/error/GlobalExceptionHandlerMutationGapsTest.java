package in.gov.fci.payroll.platform.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import tools.jackson.databind.json.JsonMapper;

/**
 * SRS-EH-1 violations of the global handler: method-parameter validation with bound objects, and
 * the exact code of a violation (the last segment of its most general message code).
 */
@ResourceLock("thread-context")
class GlobalExceptionHandlerMutationGapsTest {

    private final ProblemResponder responder =
            new ProblemResponder(
                    new ProblemDetailFactory(
                            "https://errors.fci.gov.in",
                            null,
                            Clock.fixed(Instant.parse("2026-11-05T10:15:30Z"), ZoneOffset.UTC)),
                    new ProblemDetailWriter(new JsonMapper()));
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(responder);

    @SuppressWarnings("unchecked")
    private static List<Violation> violationsOf(ResponseEntity<Object> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("errorCode", "COM-400-VALIDATION");
        return (List<Violation>) body.get("violations");
    }

    private ResponseEntity<Object> handle(Exception ex) {
        return handler.handleExceptionInternal(
                ex, null, new HttpHeaders(), HttpStatus.BAD_REQUEST, mock(WebRequest.class));
    }

    @Test
    void SRS_EH_1_aBoundObjectOfAMethodParameterListsEachFieldError() {
        FieldError name =
                new FieldError(
                        "request",
                        "name",
                        null,
                        false,
                        new String[] {"NotBlank.request.name", "NotBlank"},
                        null,
                        "must not be blank");
        FieldError code =
                new FieldError(
                        "request",
                        "code",
                        null,
                        false,
                        new String[] {"Size.request.code", "Size"},
                        null,
                        "size must be at most 5");
        ParameterErrors errors = mock(ParameterErrors.class);
        when(errors.getFieldErrors()).thenReturn(List.of(name, code));
        MethodValidationResult result = mock(MethodValidationResult.class);
        when(result.isForReturnValue()).thenReturn(false);
        when(result.getParameterValidationResults()).thenReturn(List.of(errors));

        ResponseEntity<Object> response = handle(new HandlerMethodValidationException(result));

        assertThat(violationsOf(response))
                .containsExactly(
                        new Violation("name", "NotBlank", "must not be blank"),
                        new Violation("code", "Size", "size must be at most 5"));
    }

    @Test
    void SRS_EH_1_theCodeOfAViolationIsTheLastSegmentOfItsMostGeneralMessageCode()
            throws Exception {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "item");
        result.addError(new ObjectError("item", new String[] {"item.dates.Crossed"}, null, "a"));
        result.addError(new ObjectError("item", new String[] {".Leading"}, null, "b"));
        result.addError(new ObjectError("item", new String[] {"NoDot"}, null, "c"));
        result.addError(new ObjectError("item", new String[] {"x.Specific", "Pattern"}, null, "d"));
        result.addError(new ObjectError("item", null, null, "e"));
        MethodArgumentNotValidException invalid =
                new MethodArgumentNotValidException(
                        new MethodParameter(
                                Object.class.getDeclaredMethod("equals", Object.class), 0),
                        result);

        assertThat(violationsOf(handle(invalid)))
                .containsExactly(
                        new Violation("_object", "Crossed", "a"),
                        new Violation("_object", "Leading", "b"),
                        new Violation("_object", "NoDot", "c"),
                        new Violation("_object", "Pattern", "d"),
                        new Violation("_object", "Invalid", "e"));
    }
}
