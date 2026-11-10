package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.configuration.orchestration.ReferenceListService.Proposal;
import in.gov.fci.payroll.platform.error.FciException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * SRS-16-15: an M-16 value carries an English label of 1 - 200, a Hindi label of at most 200, an
 * order of 0 - 99,999 and a reason of at most 500, checked again in the service whatever the caller
 * (defence in depth behind the request validation).
 */
class ReferenceListRulesTest {

    private static Proposal p(String en, String hi, Integer order, String reason) {
        return new Proposal("CODE", en, hi, order, null, null, reason);
    }

    @Test
    void SRS_16_15_aValueWithinItsLimitsIsAccepted() {
        assertThatCode(() -> ReferenceListService.validate(p("Zonal Office", null, null, "r")))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                ReferenceListService.validate(
                                        p(
                                                "x".repeat(200),
                                                "क".repeat(200),
                                                99_999,
                                                "r".repeat(500))))
                .doesNotThrowAnyException();
        assertThatCode(() -> ReferenceListService.validate(p(" Office ", " ", 0, " r ")))
                .doesNotThrowAnyException();
    }

    @Test
    void SRS_16_15_aValueOutsideItsLimitsIsRefused() {
        for (Proposal bad :
                Arrays.asList(
                        null,
                        p(null, null, null, "r"),
                        p("  ", null, null, "r"),
                        p("x".repeat(201), null, null, "r"),
                        p("x", "क".repeat(201), null, "r"),
                        p("x", null, -1, "r"),
                        p("x", null, 100_000, "r"),
                        p("x", null, null, null),
                        p("x", null, null, " "),
                        p("x", null, null, "r".repeat(501)))) {
            assertThatThrownBy(() -> ReferenceListService.validate(bad))
                    .isInstanceOf(FciException.class);
        }
    }
}
