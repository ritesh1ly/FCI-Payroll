package in.gov.fci.payroll.eseva.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.eseva.orchestration.MemberOverviewService.DocumentRow;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * E-01 overview tiles and documents (SRS-17-1, SRS-17-9, SRS-17-17, BR-ESEVA-004): the loan tile
 * adds up every active loan and shows the first; the TDS tile sums the year's locked TDS once the
 * pay code is configured and shows the regime as stored; a locked current month is listed as final
 * without a "not yet available" row; each released Form 16 is listed with its release time, if any.
 */
class MemberOverviewServiceCoverageGapsTest {

    private static final Instant LOCKED = Instant.parse("2026-11-05T06:00:00Z");

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final MemberOverviewService service =
            new MemberOverviewService(
                    db.jdbc,
                    mock(Authorization.class),
                    Clock.fixed(Instant.parse("2026-11-07T06:00:00Z"), ZoneOffset.UTC));
    private final AccessContext member =
            new AccessContext(
                    3L, "asha@x", "Asha", "s", "P", null, 44L, List.of(), Map.of(), Set.of(),
                    Set.of(), Set.of());

    @Test
    void SRS_17_1_theTilesAddUpEveryLoanAndTheYearsTds() {
        db.rows(
                "FROM loan_header h",
                row("OUT", new BigDecimal("1000"), "NO", "L1", "TYPE", "HBA", "PAID", 3, "N", 120),
                row("OUT", new BigDecimal("500"), "NO", "L2", "TYPE", "CAR", "PAID", 1, "N", 60));
        db.rows("v.value_code = 'TDS'", row("NUMERIC_ATTRIBUTE", new BigDecimal("801")));
        db.value("SELECT COALESCE(SUM(ABS(rc.amount)), 0)", new BigDecimal("12000"));
        db.rows("FROM it_regime_detail", row("REGIME_CODE", "N "));

        MemberOverviewService.Overview o = service.mine(member);

        assertThat(o.loan())
                .isEqualTo(
                        new MemberOverviewService.Loan(
                                new BigDecimal("1500"), "L1", "HBA", 3, 120, 2));
        assertThat(o.tds())
                .isEqualTo(new MemberOverviewService.Tds("2026-27", new BigDecimal("12000"), "N"));
        assertThat(db.queries("c.legacy_pay_code = ?")).hasSize(1);
    }

    @Test
    void BR_ESEVA_004_aLockedCurrentMonthIsFinalAndEachReleasedForm16IsListed() {
        db.rows(
                "SELECT r.payroll_yymm, c.locked_at",
                row("YYMM", "202611", "AT", Timestamp.from(LOCKED)),
                row("YYMM", "202610", "AT", null));
        db.rows(
                "FROM form16 WHERE employee_id",
                row("YEAR", "2025-26", "AT", Timestamp.from(LOCKED), "ID", 5L, "OUT", 51L),
                row("YEAR", "2024-25", "AT", null, "ID", 4L, "OUT", null));

        List<DocumentRow> documents = service.mine(member).documents();

        assertThat(documents)
                .containsExactly(
                        new DocumentRow("PAY_SLIP", "202611", LOCKED, "FINAL", null),
                        new DocumentRow("PAY_SLIP", "202610", null, "FINAL", null),
                        new DocumentRow("FORM_16", "2025-26", LOCKED, "ISSUED", 5L),
                        // OD-153: released before issued copies were kept (no report_output): no
                        // PDF.
                        new DocumentRow("FORM_16", "2024-25", null, "ISSUED_NO_COPY", 4L),
                        new DocumentRow("FORM_16", "2026-27", null, "NOT_DUE", null),
                        new DocumentRow("FORM_12BA", "2025-26", null, "NOT_AVAILABLE", null),
                        new DocumentRow("CPF_SLIP", "2026-27", null, "NOT_AVAILABLE", null));
    }
}
