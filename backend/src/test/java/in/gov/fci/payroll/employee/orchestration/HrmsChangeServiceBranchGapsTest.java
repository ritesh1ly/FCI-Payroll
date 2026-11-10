package in.gov.fci.payroll.employee.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.HrmsChanges;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.approval.ApprovalWithdrawals;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * The I-21 task service at its edges (SRS-1-28, SRS-1-69, SRS-1-30, SRS-G-31, US-GAP-276): a change
 * received after the month's freeze is targeted at the next month and recorded so; a task without a
 * target month shows no pay impact; a task under one bulk order needs no copy of its own; an order
 * copy is added while the task is with the Checker; the register's scope is the user's subtree; a
 * long login is kept to the column's 50 characters.
 */
class HrmsChangeServiceBranchGapsTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-26T06:00:00Z"), ZoneId.of("UTC"));

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final Authorization policy = mock(Authorization.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final PayrollPeriods periods = mock(PayrollPeriods.class);
    private final PayrollRecords payroll = mock(PayrollRecords.class);
    private final HrmsChangeApplier applier = mock(HrmsChangeApplier.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final HrmsChangeService service =
            new HrmsChangeService(
                    db.jdbc,
                    db.named,
                    org,
                    policy,
                    approvals,
                    mock(AuditService.class),
                    mock(SystemParameters.class),
                    periods,
                    payroll,
                    applier,
                    CLOCK,
                    documents,
                    mock(ApprovalWithdrawals.class));

    private static AccessContext user(String login) {
        return new AccessContext(
                3L, login, "Maker", "s", "P", 501L, null, List.of(), Map.of(), Set.of(), Set.of(),
                Set.of());
    }

    private void received(Optional<PayrollRecords.Cycle> cycle) {
        db.rows(
                "FROM hrms_pay_effect_field WHERE field_code",
                row(
                        "pay_effect_field_id", 4L,
                        "field_code", "BASIC_PAY",
                        "auto_apply", "N",
                        "otp_required", "N"));
        db.rows(
                "FROM employee WHERE",
                row("employee_id", 7L, "emp_num", "100041", "location_id", 501L));
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(300L);
        when(org.cycleUnitOf(501L)).thenReturn(Optional.of(unit));
        when(periods.current()).thenReturn(YearMonth.of(2026, 10));
        when(payroll.cycle(eq(300L), anyString())).thenReturn(cycle);
    }

    private List<Object> inserted() {
        return db.updates("INSERT INTO hrms_change_task").getFirst().args();
    }

    private static HrmsChanges.Change change() {
        return new HrmsChanges.Change(
                7L,
                "BASIC_PAY",
                "62400",
                LocalDate.of(2026, 10, 1),
                null,
                HrmsChanges.Source.HRMS_PUSH,
                null);
    }

    /** SRS-1-69: after the month's freeze a change is paid from the next month, marked so. */
    @Test
    void SRS_1_69_aChangeReceivedAfterTheFreezeTargetsTheNextMonth() {
        received(Optional.of(new PayrollRecords.Cycle(1L, 300L, "202610", "FROZEN")));
        HrmsChanges.Received r = service.receive(change());
        assertThat(r.status()).isEqualTo("PENDING_ORDER_DETAILS");
        assertThat(inserted().get(10)).isEqualTo("202611");
        assertThat(inserted().get(11)).isEqualTo("N");
    }

    /** SRS-1-69: with no cycle yet (or one still open) it is paid in the month received. */
    @Test
    void SRS_1_69_aChangeReceivedBeforeAnyCycleTargetsTheCurrentMonth() {
        received(Optional.empty());
        service.receive(change());
        assertThat(inserted().get(10)).isEqualTo("202610");
        assertThat(inserted().get(11)).isEqualTo("Y");
    }

    /** US-GAP-276: a task with its effective date but no target month shows no pay impact. */
    @Test
    void SRS_1_28_aTaskWithoutATargetMonthHasNoPayImpact() {
        HrmsChangeService.TaskView task =
                new HrmsChangeService.TaskView(
                        1L,
                        "HCA-1",
                        "100041",
                        "A B",
                        "BASIC_PAY",
                        "Basic pay",
                        "SERVICE_PAY",
                        "50000",
                        "52000",
                        LocalDate.of(2026, 9, 1),
                        "R-1",
                        "HRMS_PUSH",
                        "PENDING_CHECKER",
                        null,
                        false,
                        Instant.parse("2026-11-01T00:00:00Z"),
                        1L,
                        true,
                        null,
                        null,
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        null);
        assertThat(HrmsChangeService.impact(task)).isNull();
    }

    private void task(String status, Object orderId) {
        db.rows(
                "SELECT * FROM hrms_change_task WHERE hrms_change_task_id",
                row(
                        "task_status",
                        status,
                        "paying_unit_id",
                        300L,
                        "pay_effect_field_id",
                        4L,
                        "employee_id",
                        7L,
                        "new_value",
                        "52000",
                        "task_no",
                        "HCA-2026-0005",
                        "task_order_id",
                        orderId,
                        "effective_date",
                        Date.valueOf("2026-10-01")));
        db.rows(
                "SELECT * FROM hrms_pay_effect_field WHERE pay_effect_field_id",
                row(
                        "field_code", "BASIC_PAY",
                        "otp_required", "N",
                        "hq_estab_if_retire_past", "N"));
    }

    /** SRS-1-67 / SRS-G-31: a task under one bulk order is submitted on that order's copy. */
    @Test
    void SRS_G_31_aTaskUnderABulkOrderNeedsNoCopyOfItsOwn() {
        task("PENDING_ORDER_DETAILS", 77L);
        when(applier.applicable(any())).thenReturn(true);
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(9L, null, null));
        long id =
                service.submit(
                        user("maker@x"),
                        5L,
                        new HrmsChangeService.OrderDetails(
                                "O-1", LocalDate.of(2026, 10, 1), "HQ", false));
        assertThat(id).isEqualTo(9L);
        verify(documents, never()).countActive(anyString(), anyString());
        assertThat(db.updates("SET task_status = 'PENDING_CHECKER'")).hasSize(1);
    }

    /**
     * SRS-1-28: an order copy is added while the task is with the Checker; the uploader's login is
     * kept to the 50 characters of its column.
     */
    @Test
    void SRS_1_28_aCopyIsAddedWhileWithTheCheckerUnderALoginKeptToFifty() {
        task("PENDING_CHECKER", null);
        String login = "establishment.maker.of.the.zonal.office.north@fci.example";
        HrmsChangeService.OrderCopy copy =
                new HrmsChangeService.OrderCopy(
                        "OTHER", "O-1", LocalDate.of(2026, 10, 1), "HQ", "o.pdf", new byte[] {1});
        service.attachOrder(user(login), 5L, copy);
        verify(documents).attach(eq(login.substring(0, 50)), any());
        assertThat(login).hasSizeGreaterThan(50);
    }

    /** SRS-1-28: a decided task takes no further order copy. */
    @Test
    void SRS_1_28_aDecidedTaskTakesNoCopy() {
        task("APPROVED", null);
        HrmsChangeService.OrderCopy copy =
                new HrmsChangeService.OrderCopy(
                        "OTHER", "O-1", LocalDate.of(2026, 10, 1), "HQ", "o.pdf", new byte[] {1});
        assertThatThrownBy(() -> service.attachOrder(user("maker@x"), 5L, copy))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.I21_STATE));
        verify(documents, never()).attach(anyString(), any());
    }

    /** SRS-1-30 (UI audit S10): a unit sees its subtree; an empty subtree matches no unit. */
    @Test
    void SRS_1_30_theRegisterScopeIsTheUnitsSubtreeAndAnEmptyOneMatchesNothing() {
        HrmsChangeService.Filter filter = new HrmsChangeService.Filter(null, null, null, false);
        assertThat(service.params((Long) null, filter).getValue("all")).isEqualTo("Y");
        when(org.subtree(300L)).thenReturn(Set.of(300L, 301L));
        MapSqlParameterSource unit = service.params(300L, filter);
        assertThat(unit.getValue("all")).isEqualTo("N");
        assertThat(unit.getValue("units")).isEqualTo(Set.of(300L, 301L));
        when(org.subtree(302L)).thenReturn(Set.of());
        MapSqlParameterSource empty = service.params(302L, filter);
        assertThat(empty.getValue("all")).isEqualTo("N");
        assertThat(empty.getValue("units")).isEqualTo(Set.of(-1L));
    }
}
