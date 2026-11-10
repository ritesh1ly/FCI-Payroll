package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.payroll.api.StaffDeductionRunner;
import in.gov.fci.payroll.payroll.business.PayDataException;
import in.gov.fci.payroll.payroll.business.PayRules;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Professional Tax as a staff deduction (plan 16 phase 16e-2; SRS-1-6, SRS-7-4): the demo slab of
 * the office's state on the taxable gross, recovered under the PT pay code (demo 901), written
 * signed; no PT where the state does not levy it (Delhi, demo Punjab); an unset pay code refuses
 * the member. Demo slab values, not FCI's.
 */
class ProfessionalTaxDeductionIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private StaffDeductionRunner deductions;
    @Autowired private PayConfiguration configuration;

    private long location(String state) {
        return jdbc.queryForObject(
                "SELECT MIN(e.location_id) FROM employee e JOIN org_location l ON l.location_id ="
                        + " e.location_id WHERE l.state_code = ? AND e.record_status = 'ACTIVE'",
                Long.class,
                state);
    }

    /** A member whose provident fund is NPS: no CPF line, so the PT is the only deduction. */
    private long nps() {
        return jdbc.queryForObject(
                "SELECT MIN(employee_id) FROM employee WHERE provident_fund_type = 'NPS' AND"
                        + " record_status = 'ACTIVE'",
                Long.class);
    }

    private PayRules.Outcome outcome(String basic, String hra, PayConfiguration.Snapshot config) {
        List<PayRules.Line> lines =
                List.of(
                        new PayRules.Line(
                                PayRules.BASIC,
                                config.componentIds().get(PayRules.BASIC),
                                new BigDecimal(basic),
                                null,
                                null),
                        new PayRules.Line(
                                PayRules.HRA,
                                config.componentIds().get(PayRules.HRA),
                                new BigDecimal(hra),
                                null,
                                null));
        BigDecimal gross = new BigDecimal(basic).add(new BigDecimal(hra));
        return new PayRules.Outcome(PayRules.COMPUTED, null, lines, gross, BigDecimal.ZERO, gross);
    }

    @Test
    void SRS_7_4_ptIsTheStateSlabOnTheTaxableGrossUnderItsPayCode() {
        YearMonth month = YearMonth.now(IST);
        PayConfiguration.Snapshot config = configuration.staffConfiguration(month.atDay(1));
        StaffDeductionRunner.Session session = deductions.open(month);

        PayRules.Outcome chennai =
                session.apply(
                        outcome("30000", "6000", config), nps(), location("TN"), "IDA", config);
        PayRules.Line pt =
                chennai.lines().stream().filter(l -> l.payCode() == 901).findFirst().orElseThrow();
        assertThat(pt.amount()).as("SRS-DM-6: signed").isEqualByComparingTo("-208");
        assertThat(pt.basis()).isEqualByComparingTo("36000");
        assertThat(chennai.deductions()).isEqualByComparingTo("208");
        assertThat(chennai.net()).isEqualByComparingTo("35792");

        PayRules.Outcome below =
                session.apply(
                        outcome("15000", "3000", config), nps(), location("TN"), "IDA", config);
        assertThat(below.deductions()).as("below the TN slab").isEqualByComparingTo("0");
        PayRules.Outcome kolkata =
                session.apply(
                        outcome("20000", "2000", config), nps(), location("WB"), "IDA", config);
        assertThat(kolkata.deductions()).isEqualByComparingTo("130");
        for (String state : List.of("DL", "PB")) {
            PayRules.Outcome none =
                    session.apply(
                            outcome("90000", "9000", config),
                            nps(),
                            location(state),
                            "IDA",
                            config);
            assertThat(none.deductions()).as(state + " levies no PT").isEqualByComparingTo("0");
            assertThat(none.lines()).hasSize(2);
        }
        assertThat(session.versionSetHash(config)).isNotEqualTo(config.versionSetHash());
    }

    @Test
    void SRS_1_6_withoutThePtPayCodeTheMemberIsRefused() {
        YearMonth month = YearMonth.now(IST);
        String update =
                "UPDATE reference_value SET numeric_attribute = ? WHERE value_code = 'PT' AND"
                        + " list_id = (SELECT list_id FROM reference_list WHERE list_code ="
                        + " 'STATUTORY_DEDUCTION')";
        jdbc.update(update, (Object) null);
        try {
            PayConfiguration.Snapshot config = configuration.staffConfiguration(month.atDay(1));
            StaffDeductionRunner.Session session = deductions.open(month);
            assertThatThrownBy(
                            () ->
                                    session.apply(
                                            outcome("30000", "6000", config),
                                            nps(),
                                            location("MH"),
                                            "IDA",
                                            config))
                    .isInstanceOf(PayDataException.class)
                    .hasMessageContaining("pay code not configured");
            assertThat(
                            session.apply(
                                            outcome("30000", "6000", config),
                                            nps(),
                                            location("DL"),
                                            "IDA",
                                            config)
                                    .deductions())
                    .as("no PT due, no code needed")
                    .isEqualByComparingTo("0");
        } finally {
            jdbc.update(update, 901);
        }
    }
}
