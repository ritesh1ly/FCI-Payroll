package in.gov.fci.payroll.labour.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * M-05 history on X-04 (SRS-20-29; SRS-3-13, US-GAP-61): a Departmental Labour set shows each
 * designation's Basic wage and DA, not a Minimum Guaranteed Wage.
 */
class LabourHistoryBranchGapsTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final LabourHistory config = new LabourHistory();

    @Test
    void SRS_3_13_aDepartmentalSetShowsItsBasicWageAndDaByDesignation() {
        RecordHistorySource source = config.labourRateHistory(db.named);
        LocalDate from = LocalDate.of(2026, 4, 1);
        db.rows(
                "FROM labour_min_wage w",
                row(
                        "min_wage_id",
                        30L,
                        "effective_from",
                        from,
                        "effective_to",
                        null,
                        "version_no",
                        1,
                        "approval_id",
                        null,
                        "status",
                        null,
                        "interim_relief",
                        null,
                        "goi_rate",
                        null,
                        "goi_rate_source",
                        null,
                        "comparison_grain",
                        null,
                        "designation_code",
                        "HL",
                        "min_guaranteed_wage",
                        null,
                        "basic_wage",
                        new BigDecimal("18500.00"),
                        "vda",
                        new BigDecimal("2405.00")));

        HistoryRecord set = source.find("DEPT_LABOUR").orElseThrow();

        assertThat(set.versions()).hasSize(1);
        assertThat(set.versions().get(0).values())
                .containsEntry("basic_wage.HL", "18500.00")
                .containsEntry("da.HL", "2405.00")
                .doesNotContainKey("mgw.HL");
    }
}
