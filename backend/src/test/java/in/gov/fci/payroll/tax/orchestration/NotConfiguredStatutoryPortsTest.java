package in.gov.fci.payroll.tax.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Until the posting interface (module 23) and the prescribed return validation utility exist, the
 * stand-in ports answer "nothing known", so a certificate or a return is never released on an
 * assumed figure (plan 08 section 0; SRS 24Q / Form 16 release rules).
 */
class NotConfiguredStatutoryPortsTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private final NotConfiguredStatutoryPorts.Ledger ledger =
            new NotConfiguredStatutoryPorts.Ledger();
    private final NotConfiguredStatutoryPorts.Validator validator =
            new NotConfiguredStatutoryPorts.Validator();

    @Test
    void noPostedTdsIsAssumedForAnyUnitOrMonthSet() {
        assertThat(ledger.postedTds(10L, List.of("2026-04", "2026-05"))).isEmpty();
        assertThat(ledger.postedTds(10L, List.of())).as("no months").isEmpty();
        assertThat(ledger.postedTds(null, List.of())).as("no unit").isEmpty();
    }

    @Test
    void noRemittanceIsAssumedNeitherTrueNorFalseForAnyChallan() {
        assertThat(ledger.remitted("0510308", "0230263", TODAY)).isEmpty();
        assertThat(ledger.remitted(null, null, null)).isEmpty();
    }

    @Test
    void noUtilityVerdictIsAssumedForAnyReturn() {
        assertThat(validator.validate(501L)).isEmpty();
        assertThat(validator.validate(null)).isEmpty();
    }

    @Test
    void theStandInsAreComponentsSoTheContextStartsWithoutTheRealPorts() {
        assertThat(NotConfiguredStatutoryPorts.Ledger.class)
                .hasAnnotation(org.springframework.stereotype.Component.class);
        assertThat(NotConfiguredStatutoryPorts.Validator.class)
                .hasAnnotation(org.springframework.stereotype.Component.class);
    }
}
