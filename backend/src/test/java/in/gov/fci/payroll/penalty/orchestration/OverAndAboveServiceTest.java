package in.gov.fci.payroll.penalty.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.penalty.business.PenaltyErrorCode;
import in.gov.fci.payroll.penalty.business.model.OverAndAbovePayment;
import in.gov.fci.payroll.penalty.orchestration.OverAndAboveService.Request;
import in.gov.fci.payroll.penalty.persistence.PenaltyRepositories;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * I-06 over-and-above payments (SRS-14-2, SRS-14-20, BR-PEN2-002): the entry and cancel refusals.
 */
class OverAndAboveServiceTest {

    private static final LocalDate APPLIED = LocalDate.of(2026, 4, 1);

    private final PenaltyRepositories.Payments payments = mock(PenaltyRepositories.Payments.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final Authorization policy = mock(Authorization.class);
    private final AccessContext context = mock(AccessContext.class);
    private final OverAndAboveService service =
            new OverAndAboveService(
                    payments,
                    employees,
                    policy,
                    mock(MakerChecker.class),
                    mock(AuditService.class),
                    mock(JdbcTemplate.class),
                    mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class),
                    mock(ObjectMapper.class),
                    Clock.systemUTC());

    private static Request request(
            String eis,
            BigDecimal amount,
            String yymm,
            String reason,
            String sanction,
            LocalDate sanctioned) {
        return new Request(eis, 101, amount, yymm, reason, APPLIED, sanction, sanctioned);
    }

    private static void refused(ErrorCode code, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        FciException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }

    @Test
    void SRS_14_2_aPaymentNeedsAServingMemberAnAmountAMonthAReasonAndALaterSanction() {
        refused(PenaltyErrorCode.ORDER, () -> service.submit(context, null));
        when(employees.memberByEis(null)).thenReturn(Optional.empty());
        refused(
                PenaltyErrorCode.ORDER,
                () ->
                        service.submit(
                                context, request(null, BigDecimal.ONE, "202604", "r", "S", null)));
        when(employees.memberByEis("E1"))
                .thenReturn(
                        Optional.of(new EmployeeDirectory.Member(1L, "E1", "A", 5L, "RETIRED")));
        refused(
                PenaltyErrorCode.ORDER,
                () ->
                        service.submit(
                                context,
                                request(" E1 ", BigDecimal.ONE, "202604", "r", "S", null)));
        when(employees.memberByEis("E2"))
                .thenReturn(
                        Optional.of(new EmployeeDirectory.Member(2L, "E2", "B", 5L, "IN_SERVICE")));
        for (BigDecimal bad :
                new BigDecimal[] {
                    null, BigDecimal.ZERO, new BigDecimal("-1"), new BigDecimal("1.005")
                }) {
            refused(
                    PenaltyErrorCode.ORDER,
                    () -> service.submit(context, request("E2", bad, "202604", "r", "S", null)));
        }
        BigDecimal ok = new BigDecimal("10.50");
        refused(
                PenaltyErrorCode.ORDER,
                () -> service.submit(context, request("E2", ok, "2026-04", "r", "S", null)));
        refused(
                PenaltyErrorCode.ORDER,
                () -> service.submit(context, request("E2", ok, "202604", null, "S", null)));
        refused(
                PenaltyErrorCode.ORDER,
                () ->
                        service.submit(
                                context, request("E2", ok, "202604", "r", "x".repeat(61), null)));
        refused(
                PenaltyErrorCode.ORDER,
                () -> service.submit(context, request("E2", ok, "202604", "r\u0007", "S", null)));
        refused(
                PenaltyErrorCode.ORDER,
                () -> service.submit(context, request("E2", ok, "202604", "r", "S", APPLIED)));
        refused(
                PenaltyErrorCode.ORDER,
                () ->
                        service.submit(
                                context,
                                request("E2", ok, "202604", "r", "S", APPLIED.minusDays(1))));
    }

    /**
     * US-GAP-231 (SRS-14-2, BR-PEN2-002): a sanction date, where given, is later than the
     * application date - the same day and the day before are refused; a sanction date one day
     * later, or none at all, passes this check (and is next refused here only for its pay
     * component, which the mocked database does not have).
     */
    @Test
    void BR_PEN2_002_aSanctionDateIsLaterThanTheApplicationOrAbsent() {
        when(employees.memberByEis("E2"))
                .thenReturn(
                        Optional.of(new EmployeeDirectory.Member(2L, "E2", "B", 5L, "IN_SERVICE")));
        BigDecimal ok = new BigDecimal("10.50");
        for (LocalDate bad : new LocalDate[] {APPLIED, APPLIED.minusDays(1)}) {
            assertThat(why(request("E2", ok, "202604", "r", "S", bad))).contains("sanction date");
        }
        for (LocalDate good : new LocalDate[] {APPLIED.plusDays(1), null}) {
            assertThat(why(request("E2", ok, "202604", "r", "S", good)))
                    .as("past the sanction check: refused for the component only")
                    .contains("pay component");
        }
        assertThat(why(new Request("E2", 101, ok, "202604", "r", null, "S", APPLIED)))
                .as("no application date: nothing to compare")
                .contains("pay component");
    }

    private String why(Request r) {
        FciException ex =
                (FciException)
                        org.assertj.core.api.Assertions.catchThrowable(
                                () -> service.submit(context, r));
        assertThat(ex.getErrorCode()).isEqualTo(PenaltyErrorCode.ORDER);
        return String.valueOf(ex.getMessageArgs()[0]);
    }

    @Test
    void SRS_14_20_aPaymentOutOfScopeIsNotFoundAndAPaidOneIsNotCancelled() {
        when(payments.findById(1L)).thenReturn(Optional.empty());
        refused(CommonErrorCode.NOT_FOUND, () -> service.cancel(context, 1L, "r"));
        OverAndAbovePayment orphan = mock(OverAndAbovePayment.class);
        when(orphan.getEmployeeId()).thenReturn(9L);
        when(payments.findById(2L)).thenReturn(Optional.of(orphan));
        when(employees.member(9L)).thenReturn(Optional.empty());
        refused(CommonErrorCode.NOT_FOUND, () -> service.cancel(context, 2L, "r"));
        when(employees.member(10L))
                .thenReturn(
                        Optional.of(new EmployeeDirectory.Member(10L, "E", "C", 5L, "IN_SERVICE")));
        OverAndAbovePayment p = mock(OverAndAbovePayment.class);
        when(p.getEmployeeId()).thenReturn(10L);
        when(payments.findById(3L)).thenReturn(Optional.of(p));
        refused(CommonErrorCode.NOT_FOUND, () -> service.cancel(context, 3L, "r"));
        when(policy.canAt(any(), anyString(), anyString(), anyLong())).thenReturn(true);
        refused(PenaltyErrorCode.ORDER, () -> service.cancel(context, 3L, " "));
        for (OverAndAbovePayment.Status s :
                new OverAndAbovePayment.Status[] {
                    OverAndAbovePayment.Status.PAID,
                    OverAndAbovePayment.Status.CANCELLED,
                    OverAndAbovePayment.Status.REJECTED
                }) {
            when(p.status()).thenReturn(s);
            refused(PenaltyErrorCode.ORDER, () -> service.cancel(context, 3L, "r"));
        }
        when(p.status()).thenReturn(OverAndAbovePayment.Status.APPROVED);
        when(p.getInvoiceStatus()).thenReturn("SENT");
        refused(PenaltyErrorCode.ORDER, () -> service.cancel(context, 3L, "r"));
    }

    private static in.gov.fci.payroll.platform.approval.ApprovalDecided decided(
            String entity, in.gov.fci.payroll.platform.approval.ApprovalStatus outcome) {
        return new in.gov.fci.payroll.platform.approval.ApprovalDecided(
                4L,
                "I-06",
                entity,
                "9",
                outcome,
                "no sanction",
                null,
                "ddo@x",
                java.time.Instant.now());
    }

    /**
     * SRS-14-14: the Checker approves, returns (back to the Maker) or rejects; a cancel takes
     * effect only approved.
     */
    @Test
    void SRS_14_14_eachDecisionSetsItsStatusAndARejectedCancelChangesNothing() {
        OverAndAbovePayment p = mock(OverAndAbovePayment.class);
        when(payments.findById(9L)).thenReturn(Optional.of(p));
        when(p.status()).thenReturn(OverAndAbovePayment.Status.APPROVED);
        service.on(
                decided(
                        "over_and_above_payment",
                        in.gov.fci.payroll.platform.approval.ApprovalStatus.APPROVED));
        service.on(
                decided(
                        "over_and_above_payment",
                        in.gov.fci.payroll.platform.approval.ApprovalStatus.RETURNED));
        service.on(
                decided(
                        "over_and_above_payment",
                        in.gov.fci.payroll.platform.approval.ApprovalStatus.REJECTED));
        org.mockito.Mockito.verify(p).status(OverAndAbovePayment.Status.APPROVED);
        org.mockito.Mockito.verify(p).status(OverAndAbovePayment.Status.RECORDED);
        org.mockito.Mockito.verify(p).status(OverAndAbovePayment.Status.REJECTED);
        service.on(
                decided(
                        "over_above_cancel",
                        in.gov.fci.payroll.platform.approval.ApprovalStatus.REJECTED));
        org.mockito.Mockito.verify(p, org.mockito.Mockito.never())
                .status(OverAndAbovePayment.Status.CANCELLED);
        service.on(
                decided(
                        "over_above_cancel",
                        in.gov.fci.payroll.platform.approval.ApprovalStatus.APPROVED));
        org.mockito.Mockito.verify(p).status(OverAndAbovePayment.Status.CANCELLED);
        service.on(decided("other", in.gov.fci.payroll.platform.approval.ApprovalStatus.APPROVED));
    }

    @Test
    void SRS_14_15_aReaderWithoutOfficesSeesNoPayments() {
        when(context.dataScope()).thenReturn(java.util.Set.of());
        assertThat(service.payments(context)).isEmpty();
    }
}
