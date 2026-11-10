package in.gov.fci.payroll.penalty.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.NetPayFloors;
import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.penalty.business.PenaltyErrorCode;
import in.gov.fci.payroll.penalty.orchestration.PenaltyOrderService.Request;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * I-06 recovery preview (plan 12; SRS-14-1, SRS-14-7, SRS-14-13; BR-PEN2-002; D12-6): recovery
 * starts in the open month (the next when this month's cycle is locked); a nature without its
 * recovery head (M-16) or an unseeded deduction ceiling (M-18) is not configured; the combined
 * ceiling lengthens the instalments; the net-pay floor is an amount, a share of gross, or none.
 */
class PenaltyOrderPreviewTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final PayrollRecords payroll = mock(PayrollRecords.class);
    private final RuleEngine rules = mock(RuleEngine.class);
    private final NetPayFloors floors = mock(NetPayFloors.class);
    private final ReferenceData references = mock(ReferenceData.class);
    private final AccessContext context = mock(AccessContext.class);
    private final PenaltyOrderService service =
            new PenaltyOrderService(
                    jdbc,
                    employees,
                    org,
                    payroll,
                    rules,
                    floors,
                    references,
                    mock(Authorization.class),
                    mock(MakerChecker.class),
                    mock(DocumentStore.class),
                    JsonMapper.builder().build(),
                    Clock.fixed(Instant.parse("2026-04-10T05:00:00Z"), ZoneOffset.UTC),
                    mock(in.gov.fci.payroll.audit.api.AuditService.class));

    PenaltyOrderPreviewTest() {
        when(employees.memberByEis("E1"))
                .thenReturn(
                        Optional.of(new EmployeeDirectory.Member(2L, "E1", "B", 5L, "IN_SERVICE")));
        when(references.contains(PenaltyOrderService.NATURES, "RECOVERY")).thenReturn(true);
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(3L);
        when(org.cycleUnitOf(5L)).thenReturn(Optional.of(unit));
        when(jdbc.queryForObject(
                        contains("SELECT current_basic"),
                        eq(BigDecimal.class),
                        any(Object[].class)))
                .thenReturn(new BigDecimal("50000"));
    }

    private static Request request(String amount, int instalments, String start) {
        return new Request(
                "E1",
                "RECOVERY",
                "O-1",
                LocalDate.of(2026, 3, 1),
                new BigDecimal(amount),
                instalments,
                start);
    }

    private ErrorCode refusal(Request r) {
        return ((FciException) catchThrowable(() -> service.preview(context, r))).getErrorCode();
    }

    private void head(boolean configured) {
        when(jdbc.queryForList(
                        contains("PENALTY_RECOVERY"), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(configured ? List.of(new BigDecimal("401")) : List.of());
    }

    private void pay(String gross, String deductions, String net) {
        when(jdbc.queryForList(contains("FROM payroll_result"), any(Object[].class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "GROSS_EARNINGS", new BigDecimal(gross),
                                        "TOTAL_DEDUCTIONS", new BigDecimal(deductions),
                                        "NET_PAY", new BigDecimal(net))));
    }

    private void ceiling(String percent) {
        when(rules.resolve(eq("LOAN.DEDUCTION.CEILING"), any(), any()))
                .thenReturn(
                        Optional.of(
                                new RuleEngine.Resolved(
                                        "LOAN.DEDUCTION.CEILING",
                                        "RATE",
                                        1L,
                                        1,
                                        LocalDate.of(2026, 1, 1),
                                        null,
                                        percent == null ? null : new BigDecimal(percent),
                                        null,
                                        null,
                                        null,
                                        false)));
    }

    @Test
    void SRS_14_1_recoveryStartsInTheOpenMonthTheNextOnceThisMonthIsLocked() {
        when(payroll.cycle(3L, "202604"))
                .thenReturn(Optional.of(new PayrollRecords.Cycle(1L, 3L, "202604", "LOCKED")));
        assertThat(refusal(request("1200", 1, "202604"))).isEqualTo(PenaltyErrorCode.ORDER);
        assertThat(refusal(request("1200", 0, "202605")))
                .as("instalments")
                .isEqualTo(PenaltyErrorCode.ORDER);
        assertThat(refusal(request("0", 1, "202605")))
                .as("amount")
                .isEqualTo(PenaltyErrorCode.ORDER);
    }

    // SC-12-0008: every penalty nature is configured with its recovery head, none assumed.
    @Test
    void D12_6_aNatureWithoutItsRecoveryHeadOrAnUnseededCeilingIsNotConfigured() {
        head(false);
        assertThat(refusal(request("1200", 1, "202604")))
                .isEqualTo(PenaltyErrorCode.NOT_CONFIGURED);
        head(true);
        pay("60000", "10000", "50000");
        when(rules.resolve(anyString(), any(), any())).thenReturn(Optional.empty());
        assertThat(refusal(request("1200", 1, "202604")))
                .isEqualTo(PenaltyErrorCode.NOT_CONFIGURED);
        ceiling(null);
        assertThat(refusal(request("1200", 1, "202604")))
                .as("a version without its value")
                .isEqualTo(PenaltyErrorCode.NOT_CONFIGURED);
        when(rules.resolve(anyString(), any(), any()))
                .thenThrow(new FciException(CommonErrorCode.NOT_FOUND));
        assertThat(refusal(request("1200", 1, "202604")))
                .isEqualTo(PenaltyErrorCode.NOT_CONFIGURED);
    }

    @Test
    void
            SRS_14_7_SRS_14_6_SRS_14_18_theCeilingLengthensTheInstalmentsAndTheFloorIsAnAmountAShareOfGrossOrNone() {
        head(true);
        pay("60000", "10000", "50000");
        ceiling("50");
        when(floors.inForce(eq("STAFF"), any()))
                .thenReturn(
                        Optional.of(
                                new NetPayFloors.Floor(
                                        "PERCENT_OF_GROSS", new BigDecimal("90"), true)));
        PenaltyOrderService.Preview wide = service.preview(context, request("60000", 1, "202604"));
        assertThat(wide.adjusted())
                .as("one instalment of 60,000 is above the 50 % ceiling")
                .isTrue();
        assertThat(wide.instalments()).isGreaterThan(1);
        assertThat(wide.floor()).isEqualByComparingTo("54000.00");
        assertThat(wide.belowFloor()).isTrue();
        // SRS-14-6: an effective date before the open month is flagged as back-dated.
        assertThat(wide.backdated()).isTrue();
        // SRS-14-18: a recovery leaves the Basic as it is; not deferred unless the Maker asks.
        assertThat(wide.proposedBasic()).isEqualTo(wide.currentBasic());
        assertThat(wide.deferredToFnf()).isFalse();
        // SRS l.2995: at the breach the Maker may defer the recovery to F&F instead.
        Request deferred =
                new Request(
                        "E1",
                        "RECOVERY",
                        "O-1",
                        LocalDate.of(2026, 3, 1),
                        new BigDecimal("60000"),
                        1,
                        "202604",
                        Boolean.TRUE);
        assertThat(service.preview(context, deferred).deferredToFnf()).isTrue();

        when(floors.inForce(eq("STAFF"), any()))
                .thenReturn(
                        Optional.of(
                                new NetPayFloors.Floor(
                                        "ABSOLUTE_AMOUNT", new BigDecimal("1000"), true)));
        PenaltyOrderService.Preview small = service.preview(context, request("1200", 1, "202604"));
        assertThat(small.adjusted()).isFalse();
        assertThat(small.floor()).isEqualByComparingTo("1000");
        assertThat(small.belowFloor()).isFalse();
        // SRS l.2995: a deferral to F&F is offered only at a breach; without one it is refused.
        assertThat(
                        refusal(
                                new Request(
                                        "E1",
                                        "RECOVERY",
                                        "O-1",
                                        LocalDate.of(2026, 3, 1),
                                        new BigDecimal("1200"),
                                        1,
                                        "202604",
                                        Boolean.TRUE)))
                .isEqualTo(PenaltyErrorCode.ORDER);

        when(floors.inForce(eq("STAFF"), any()))
                .thenReturn(Optional.of(new NetPayFloors.Floor("NONE", BigDecimal.ZERO, true)));
        assertThat(service.preview(context, request("1200", 1, "202604")).floor())
                .isEqualByComparingTo("0");
        when(floors.inForce(eq("STAFF"), any())).thenReturn(Optional.empty());
        PenaltyOrderService.Preview none = service.preview(context, request("1200", 1, "202604"));
        assertThat(none.floor()).isNull();
        assertThat(none.belowFloor()).isFalse();
    }
}
