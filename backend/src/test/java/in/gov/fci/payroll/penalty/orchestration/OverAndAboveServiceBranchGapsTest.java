package in.gov.fci.payroll.penalty.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.penalty.business.model.OverAndAbovePayment;
import in.gov.fci.payroll.penalty.orchestration.OverAndAboveService.Request;
import in.gov.fci.payroll.penalty.persistence.PenaltyRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
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
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import tools.jackson.databind.ObjectMapper;

/**
 * I-06 over-and-above payments (SRS-14-2, SRS-14-15, US-GAP-230): a pay component flagged not
 * taxable makes the payment not taxable; the list shows a payment whose member is no longer in the
 * directory without an EIS, and looks up the Checker's reason only for a payment that has an
 * approval (one recorded and never submitted has none).
 */
class OverAndAboveServiceBranchGapsTest {

    private final PenaltyRepositories.Payments payments = mock(PenaltyRepositories.Payments.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final AccessContext context = mock(AccessContext.class);
    private final OverAndAboveService service =
            new OverAndAboveService(
                    payments,
                    employees,
                    mock(Authorization.class),
                    approvals,
                    mock(AuditService.class),
                    jdbc,
                    named,
                    mock(ObjectMapper.class),
                    Clock.fixed(Instant.parse("2026-04-10T05:00:00Z"), ZoneOffset.UTC));

    @Test
    void SRS_14_2_aPaymentUnderAComponentFlaggedNotTaxableIsNotTaxable() {
        when(employees.memberByEis("E2"))
                .thenReturn(
                        Optional.of(new EmployeeDirectory.Member(2L, "E2", "B", 5L, "IN_SERVICE")));
        when(jdbc.queryForList(contains("FROM pay_component"), any(Object[].class)))
                .thenReturn(List.of(Map.of("COMPONENT_ID", 140L, "TAXABLE_FLAG", "N")));
        when(payments.save(any())).thenAnswer(call -> call.getArgument(0));
        when(approvals.submit(any(), any(), isNull()))
                .thenReturn(new SubmittedApproval(9L, ApprovalStatus.PENDING_APPROVAL, null));
        service.submit(
                context,
                new Request(
                        "E2",
                        914,
                        new BigDecimal("2500"),
                        "202604",
                        "Honorarium for the stock audit",
                        LocalDate.of(2026, 4, 1),
                        "SANC/7",
                        LocalDate.of(2026, 4, 2)));
        ArgumentCaptor<OverAndAbovePayment> saved = ArgumentCaptor.captor();
        verify(payments, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().isTaxable()).isFalse();
        assertThat(saved.getValue().getComponentId()).isEqualTo(140L);
    }

    private static OverAndAbovePayment payment(long id, long employee, Long approval) {
        OverAndAbovePayment p = mock(OverAndAbovePayment.class);
        when(p.getId()).thenReturn(id);
        when(p.getEmployeeId()).thenReturn(employee);
        when(p.getComponentId()).thenReturn(140L);
        when(p.getApprovalId()).thenReturn(approval);
        when(p.status()).thenReturn(OverAndAbovePayment.Status.RECORDED);
        return p;
    }

    @Test
    void SRS_14_15_aPaymentWithoutItsMemberOrAnApprovalIsListedWithoutAnEisOrAReason() {
        when(context.dataScope()).thenReturn(Set.of(5L));
        OverAndAbovePayment recorded = payment(1L, 2L, null);
        OverAndAbovePayment orphan = payment(2L, 99L, null);
        when(payments.inScope(Set.of(5L))).thenReturn(List.of(recorded, orphan));
        when(employees.members(any()))
                .thenReturn(
                        Map.of(2L, new EmployeeDirectory.Member(2L, "E2", "B", 5L, "IN_SERVICE")));
        List<OverAndAboveService.PaymentView> views = service.payments(context);
        assertThat(views)
                .extracting(OverAndAboveService.PaymentView::eis)
                .containsExactly("E2", "");
        assertThat(views)
                .extracting(OverAndAboveService.PaymentView::checkerReason)
                .containsOnlyNulls();
        verify(named, never())
                .query(anyString(), any(SqlParameterSource.class), any(RowCallbackHandler.class));
    }
}
