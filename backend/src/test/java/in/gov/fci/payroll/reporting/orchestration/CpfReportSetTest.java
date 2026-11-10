package in.gov.fci.payroll.reporting.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * O-07 and O-08 (Wireframes v6.1, OD-121; BR-CPF-036, SRS-18-24): the CPF report sets in the drawn
 * order, under the screen's VIEW, and whether a month is Final from the cycle state.
 */
class CpfReportSetTest {

    private static final PageSpec SPEC = new PageSpec(0, 20);

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final Authorization policy = mock(Authorization.class);
    private final AccessContext user = mock(AccessContext.class);
    private final ReportCatalogueService service =
            new ReportCatalogueService(
                    db.named,
                    policy,
                    Clock.fixed(Instant.parse("2026-11-07T05:00:00Z"), ZoneOffset.UTC));

    private static Map<String, Object> entry(String key, String blocked) {
        return row(
                "REPORT_KEY", key,
                "REPORT_NAME", key,
                "REPORT_FAMILY", "Control-Audit-MIS",
                "CATALOGUE_SECTION", "A.4.6",
                "OUTPUT_DOMAIN", "REPORT",
                "PRODUCING_SCREEN_CODE", "O-04",
                "APPLICABLE_PAYROLL_TYPES", "All",
                "PERIODICITY", "Monthly",
                "LOCK_REQUIREMENT", "POST_LOCK",
                "VERSION_NO", 1,
                "LAYOUT_FORMAT", null,
                "IS_BLOCKED", blocked == null ? "N" : "Y",
                "BLOCKED_REASON", blocked);
    }

    @Test
    void SRS_G_2_aRefusedViewReadsNothing() {
        doThrow(new FciException(CommonErrorCode.ACCESS_DENIED))
                .when(policy)
                .require(user, "O-07", "VIEW");
        assertThatThrownBy(() -> service.cpf(user, "O-07", SPEC)).isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.cpfMonths(user, "202611"))
                .isInstanceOf(FciException.class);
        assertThat(db.queries("")).isEmpty();
    }

    @Test
    void SRS_G_54_theFamilyComesInTheDrawnOrder() {
        db.rows(
                "SELECT d.report_key",
                entry("IOCPF_PENDING", null),
                entry("CPF_OBCB", "Layout not supplied"),
                entry("FCI_ESEVA_REPORT", "ECR file format not supplied (OQ-23)"));
        var page = service.cpf(user, "O-07", SPEC);
        assertThat(page.items())
                .extracting(ReportCatalogueService.Entry::key)
                .containsExactly("FCI_ESEVA_REPORT", "CPF_OBCB", "IOCPF_PENDING");
        assertThat(page.items().getFirst().blockedReason()).contains("OQ-23");
    }

    @Test
    void SRS_18_24_o08ReadsItsOwnSetAndABlockedEntrySaysWhy() {
        db.rows("SELECT d.report_key", entry("CPF_UNMATCHED", "Layout not supplied"));
        var page = service.cpf(user, "O-08", SPEC);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().getFirst().status()).isEqualTo("BLOCKED");
    }

    @Test
    void BR_CPF_036_aMonthIsProvisionalUntilItsLock() {
        var m = service.cpfMonths(user, "202611");
        assertThat(m.previous()).isEqualTo("202610");
        assertThat(m.version()).isEqualTo("PROVISIONAL");
        assertThat(m.previousVersion()).isEqualTo("PROVISIONAL");
    }

    @Test
    void BR_CPF_036_aLockedMonthIsFinalAndTheYearTurnsOver() {
        db.rows("SELECT c.payroll_yymm", row("PAYROLL_YYMM", "202612"));
        var m = service.cpfMonths(user, "202701");
        assertThat(m.previous()).isEqualTo("202612");
        assertThat(m.version()).isEqualTo("PROVISIONAL");
        assertThat(m.previousVersion()).isEqualTo("FINAL");
        assertThat(List.of(m.period())).containsExactly("202701");
    }
}
