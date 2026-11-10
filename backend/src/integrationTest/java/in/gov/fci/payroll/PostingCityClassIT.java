package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.payroll.batch.computation.ComputationProcessor;
import in.gov.fci.payroll.payroll.batch.computation.MemberReader;
import in.gov.fci.payroll.payroll.business.PayRules;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * SRS-16-21 (l.1305, US-GAP-135): a posting change to a location of another city class changes the
 * member's HRA from the next computation, with no entry of its own. The staff engine reads the city
 * class of the member's location for the month ({@link MemberReader}: the {@code
 * city_type_location_map} version in force at the period start, else the location's own class), and
 * the HRA line takes the M-04 rate of that class. The member (107304, Delhi HQ, class X) is posted
 * to Depot Borivali (DP-MH01, class Y) for the test and put back afterwards; nothing is written but
 * the posting.
 */
class PostingCityClassIT extends ApiTestSupport {

    private static final String EIS = "107304";
    private static final LocalDate JULY = LocalDate.of(2026, 7, 1);

    @Autowired private PayConfiguration configuration;

    @Test
    void SRS_16_21_aPostingToAnotherCityClassChangesHraInTheNextComputation() {
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);
        long employeeId =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee WHERE emp_num = ?", Long.class, EIS);
        long home =
                jdbc.queryForObject(
                        "SELECT location_id FROM employee WHERE employee_id = ?",
                        Long.class,
                        employeeId);
        long posting =
                jdbc.queryForObject(
                        "SELECT location_id FROM org_location WHERE location_code = 'DP-MH01'",
                        Long.class);
        String homeClass = cityClass(home);
        String postingClass = cityClass(posting);
        assertThat(postingClass).as("the fixture needs two city classes").isNotEqualTo(homeClass);
        PayConfiguration.Snapshot rates = configuration.staffConfiguration(JULY);
        ComputationProcessor engine = new ComputationProcessor(rates);

        MemberReader.MemberRow before = MemberReader.member(named, employeeId, JULY).orElseThrow();
        assertThat(before.cityClass()).isEqualTo(homeClass);
        PayRules.Line hraBefore = hra(engine.process(before));
        try {
            jdbc.update(
                    "UPDATE employee SET location_id = ? WHERE employee_id = ?",
                    posting,
                    employeeId);
            MemberReader.MemberRow after =
                    MemberReader.member(named, employeeId, JULY).orElseThrow();
            assertThat(after.cityClass()).as("the new location's class").isEqualTo(postingClass);
            PayRules.Line hraAfter = hra(engine.process(after));
            assertThat(hraBefore.rate()).isEqualByComparingTo(rates.hraPercent().get(homeClass));
            assertThat(hraAfter.rate())
                    .as("the HRA rate of the new city class")
                    .isEqualByComparingTo(rates.hraPercent().get(postingClass));
            assertThat(hraAfter.basis()).isEqualByComparingTo(hraBefore.basis());
            if (rates.hraPercent().get(homeClass).compareTo(rates.hraPercent().get(postingClass))
                    != 0) {
                assertThat(hraAfter.amount()).isNotEqualByComparingTo(hraBefore.amount());
            }
        } finally {
            jdbc.update(
                    "UPDATE employee SET location_id = ? WHERE employee_id = ?", home, employeeId);
        }
        assertThat(MemberReader.member(named, employeeId, JULY).orElseThrow().cityClass())
                .as("posted back")
                .isEqualTo(homeClass);
    }

    /** The class the engine reads for a location in July 2026 (map in force, else its own). */
    private String cityClass(long location) {
        return jdbc.queryForObject(
                "SELECT COALESCE((SELECT m.city_class FROM city_type_location_map m WHERE"
                        + " m.location_id = l.location_id AND m.effective_from = (SELECT"
                        + " MAX(m2.effective_from) FROM city_type_location_map m2 WHERE"
                        + " m2.location_id = l.location_id AND m2.effective_from <= ?)),"
                        + " l.city_class) FROM org_location l WHERE l.location_id = ?",
                String.class,
                java.sql.Date.valueOf(JULY),
                location);
    }

    private static PayRules.Line hra(ComputationProcessor.Computed computed) {
        assertThat(computed).as("the member is computed").isNotNull();
        return computed.outcome().lines().stream()
                .filter(l -> l.payCode() == PayRules.HRA)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no HRA line"));
    }
}
