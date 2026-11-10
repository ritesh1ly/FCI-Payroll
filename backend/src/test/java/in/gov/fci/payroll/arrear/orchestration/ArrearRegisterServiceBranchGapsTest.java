package in.gov.fci.payroll.arrear.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * I-08 arrear register (SRS-8-14, SRS-8-21): an event raised for one office names it with its code
 * and name; an event raised for the whole organisation (no scope office) names none; an open event
 * has no end date.
 */
class ArrearRegisterServiceBranchGapsTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final ArrearRegisterService service =
            new ArrearRegisterService(
                    db.named,
                    mock(Authorization.class),
                    mock(MakerChecker.class),
                    mock(OrgDirectory.class),
                    mock(PayrollRecords.class),
                    mock(AuditService.class),
                    Clock.fixed(Instant.parse("2026-10-27T05:00:00Z"), ZoneOffset.UTC));

    private final AccessContext context =
            new AccessContext(
                    1L,
                    "maker@x",
                    "M",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    private static Map<String, Object> event(long id, String office, LocalDate to) {
        return row(
                "arrear_event_id",
                id,
                "event_code",
                "DA_REVISION",
                "event_name",
                "DA revision",
                "event_reference",
                "ORD-" + id,
                "effective_from",
                Date.valueOf(LocalDate.of(2026, 7, 1)),
                "effective_to",
                to == null ? null : Date.valueOf(to),
                "location_code",
                office,
                "location_name",
                office == null ? null : "Delhi",
                "event_status",
                "COMPUTED",
                "posted_to_yymm",
                null,
                "members",
                3L,
                "arrears",
                new BigDecimal("4500"),
                "recoveries",
                BigDecimal.ZERO,
                "raised_by",
                "maker@x",
                "raised_at",
                Timestamp.valueOf(LocalDateTime.of(2026, 10, 1, 10, 0)),
                "source_module",
                "I-08",
                "single_member",
                "N",
                "stated",
                "N",
                "beyond_window_flag",
                "N",
                "hq_exception",
                null);
    }

    @Test
    void SRS_8_14_anEventOfTheWholeOrganisationNamesNoOffice() {
        db.rows(
                "ORDER BY e.arrear_event_id DESC OFFSET",
                event(1L, "DH001", LocalDate.of(2026, 9, 30)),
                event(2L, null, null));
        List<ArrearRegisterService.EventRow> rows =
                service.events(
                                context,
                                new ArrearRegisterService.Filter(null, null),
                                new PageSpec(0, 20))
                        .items();
        assertThat(rows)
                .extracting(ArrearRegisterService.EventRow::office)
                .containsExactly("DH001 - Delhi", null);
        assertThat(rows)
                .extracting(ArrearRegisterService.EventRow::effectiveTo)
                .containsExactly(LocalDate.of(2026, 9, 30), null);
    }
}
