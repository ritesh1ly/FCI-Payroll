package in.gov.fci.payroll.payroll.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * I-19 Maintain Direct Payroll (SRS-15-2, SRS-15-19, BR-CYC-018): the register shows an entry not
 * yet stamped with its entry time without one, and names the waiting decision of an entry only
 * while it waits (none for an entry never sent, nor for one decided); a capture is refused outside
 * a locked cycle's open MDP window, and while the permitted heads are not configured.
 */
class DirectPayrollServiceBranchGapsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final DirectPayrollService service =
            new DirectPayrollService(
                    db.jdbc,
                    db.named,
                    org,
                    mock(Authorization.class),
                    mock(MakerChecker.class),
                    statuses,
                    mock(AuditService.class),
                    parameters,
                    JsonMapper.builder().build(),
                    mock(in.gov.fci.payroll.document.api.DocumentStore.class));

    private final AccessContext maker =
            new AccessContext(
                    3L,
                    "maker@x",
                    "Maker",
                    "s",
                    "P",
                    300L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of("PAYROLL_MAKER"),
                    Set.of(300L));

    DirectPayrollServiceBranchGapsTest() {
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(300L);
        when(org.cycleUnitOf(300L)).thenReturn(Optional.of(unit));
        when(parameters.today()).thenReturn(TODAY);
    }

    private static Map<String, Object> entry(long id, String status, Instant entered) {
        return cols(
                id,
                "202610",
                "E" + id,
                "Asha",
                "Rao",
                905,
                "Canteen recovery",
                new BigDecimal("150.00"),
                status,
                "Bulk correction",
                "maker@x",
                entered == null ? null : Timestamp.from(entered),
                null,
                null);
    }

    @Test
    void SRS_15_19_theRegisterNamesTheWaitingDecisionOnlyWhileItWaits() {
        Instant at = Instant.parse("2026-10-14T06:00:00Z");
        db.rows(
                        "ORDER BY m.mdp_id DESC OFFSET",
                        entry(1L, "ENTERED", null),
                        entry(2L, "APPROVED", at),
                        entry(3L, "AMENDED", at))
                .rows("SELECT entity_key, MAX(approval_id)", cols("2", 20L), cols("3", 30L));
        when(statuses.of(any()))
                .thenReturn(
                        Map.of(20L, ApprovalStatus.APPROVED, 30L, ApprovalStatus.PENDING_APPROVAL));
        List<DirectPayrollService.Entry> rows =
                service.register(
                                maker,
                                new DirectPayrollService.Filter(null, null, null, null),
                                new PageSpec(0, 20))
                        .items();
        assertThat(rows)
                .extracting(DirectPayrollService.Entry::pendingApprovalId)
                .containsExactly(null, null, 30L);
        assertThat(rows.getFirst().enteredAt()).isNull();
        assertThat(rows.get(1).enteredAt()).isEqualTo(at);
        assertThat(rows.getFirst().reference()).isEqualTo("MDP-202610-1");
        assertThat(rows.getFirst().period()).isEqualTo("2026-10");
    }

    private void refused(Object code) {
        assertThatThrownBy(
                        () ->
                                service.capture(
                                        maker,
                                        YearMonth.of(2026, 10),
                                        "E1",
                                        905,
                                        new BigDecimal("150"),
                                        "Bulk correction"))
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private void cycle(LocalDate from, LocalDate to) {
        db.rows(
                "JOIN payroll_calendar k ON k.payroll_yymm = c.payroll_yymm WHERE",
                row(
                        "cycle_instance_id",
                        41L,
                        "mdp_window_from",
                        from == null ? null : Date.valueOf(from),
                        "mdp_window_to",
                        to == null ? null : Date.valueOf(to)));
    }

    @Test
    void SRS_15_2_aCaptureOutsideALockedCyclesOpenMdpWindowIsRefused() {
        refused(ApiErrorCode.MDP_WINDOW);
        cycle(TODAY.minusDays(20), TODAY.minusDays(1));
        refused(ApiErrorCode.MDP_WINDOW);
    }

    @Test
    void BR_CYC_018_aCaptureWhileThePermittedHeadsAreNotConfiguredIsRefused() {
        cycle(TODAY.minusDays(5), TODAY.plusDays(5));
        refused(ApiErrorCode.MDP_NOT_CONFIGURED);
    }
}
