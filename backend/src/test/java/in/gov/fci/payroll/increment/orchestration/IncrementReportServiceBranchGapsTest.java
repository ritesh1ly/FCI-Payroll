package in.gov.fci.payroll.increment.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * SRS-13-24 / SRS-13-26: the Basic history marks the old entry of each approved correction: a Basic
 * correction (its own new entry, no increment row) marks the entry it replaced; an increment date
 * correction (no new entry of its own, the increment's row) marks that increment's row.
 */
class IncrementReportServiceBranchGapsTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final IncrementReportService service =
            new IncrementReportService(db.named, mock(Authorization.class));
    private final AccessContext context =
            new AccessContext(
                    1L,
                    "hr@x",
                    "H",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    private static Map<String, Object> entry(long id, String from, int sequence, String basic) {
        return cols(
                Date.valueOf(from),
                sequence,
                null,
                "INCREMENT",
                new BigDecimal(basic),
                null,
                "ENT",
                "Annual increment",
                "EST/17",
                "checker@x",
                "maker@x",
                id,
                7L);
    }

    @Test
    void SRS_13_24_eachApprovedCorrectionMarksTheEntryItReplaced() {
        db.value("SELECT COUNT(*) FROM employee_basic_history", 3L);
        db.rows(
                "h.history_id, h.employee_id FROM employee_basic_history",
                entry(3L, "2026-10-01", 2, "64300"),
                entry(2L, "2026-10-01", 1, "63000"),
                entry(1L, "2025-10-01", 1, "62400"));
        db.rows(
                "FROM increment_correction ic",
                cols("BASIC_PAY", 3L, null),
                cols("INCREMENT_DATE", null, 1L));

        List<IncrementReportService.HistoryRow> rows = service.history(context, "109991");

        assertThat(rows)
                .extracting(IncrementReportService.HistoryRow::corrected)
                .containsExactly(false, true, true);
        assertThat(rows.getFirst().basic()).isEqualByComparingTo("64300");
    }
}
