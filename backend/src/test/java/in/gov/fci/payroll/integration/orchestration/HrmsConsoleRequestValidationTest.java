package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SRS-INT-33, R-ASCII; screen X-03: the console request carries a mandatory reason of 1 to 500
 * printable ASCII characters and a bounded query; the constraints sit on the request record so the
 * API refuses before the service runs.
 */
class HrmsConsoleRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void open() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    private static Set<?> violations(Map<String, String> query, String reason) {
        return validator.validate(new HrmsConsoleService.CallRequest(query, null, reason));
    }

    @Test
    void SRS_INT_33_theReasonIsRequiredBoundedAndPrintableAscii() {
        assertThat(violations(null, "Check")).isEmpty();
        assertThat(violations(null, "x")).isEmpty();
        assertThat(violations(null, "x".repeat(500))).isEmpty();
        assertThat(violations(null, "x".repeat(501))).hasSize(1);
        assertThat(violations(null, null)).hasSize(1);
        assertThat(violations(null, "")).isNotEmpty();
        assertThat(violations(null, "   ")).isNotEmpty();
        assertThat(violations(null, "café")).isNotEmpty();
        assertThat(violations(null, "हिन्दी")).isNotEmpty();
    }

    @Test
    void SRS_INT_33_aQueryHoldsAtMostTwentyEntries() {
        Map<String, String> twenty = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            twenty.put("k" + i, "v");
        }
        assertThat(violations(twenty, "ok")).isEmpty();
        twenty.put("k20", "v");
        assertThat(violations(twenty, "ok")).hasSize(1);
        assertThat(violations(Map.of(), "ok")).isEmpty();
    }

    @Test
    void SRS_INT_33_queryKeysAreUpTo60AndValuesUpTo200Characters() {
        assertThat(violations(Map.of("k".repeat(60), "v".repeat(200)), "ok")).isEmpty();
        assertThat(violations(Map.of("k".repeat(61), "v"), "ok")).hasSize(1);
        assertThat(violations(Map.of("k", "v".repeat(201)), "ok")).hasSize(1);
        assertThat(violations(Map.of("", "v"), "ok")).isNotEmpty();
        assertThat(violations(Map.of("k", ""), "ok")).isEmpty();
    }

    @Test
    void SRS_INT_33_queryTextIsPrintableAscii() {
        assertThat(violations(Map.of("ké", "v"), "ok")).isNotEmpty();
        assertThat(violations(Map.of("k", "vé"), "ok")).isNotEmpty();
    }

    @Test
    void SRS_INT_33_theRecordKeepsItsFieldsAndComparesByValue() {
        JsonNode body = JsonMapper.builder().build().readTree("[1]");
        HrmsConsoleService.CallRequest request =
                new HrmsConsoleService.CallRequest(Map.of("a", "b"), body, "why");
        assertThat(request.query()).containsEntry("a", "b");
        assertThat(request.body()).isEqualTo(body);
        assertThat(request.reason()).isEqualTo("why");
        assertThat(request)
                .isEqualTo(new HrmsConsoleService.CallRequest(Map.of("a", "b"), body, "why"));
    }
}
