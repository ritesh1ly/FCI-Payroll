package in.gov.fci.payroll.penalty.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.NetPayFloors;
import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.penalty.business.PenaltyErrorCode;
import in.gov.fci.payroll.penalty.orchestration.PenaltyOrderService.Copy;
import in.gov.fci.payroll.penalty.orchestration.PenaltyOrderService.Request;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
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

/** I-06 recovery orders (SRS-14-1, SRS-14-12; plan 12 D12-2): the refusals before the preview. */
class PenaltyOrderServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 4, 1);

    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final ReferenceData references = mock(ReferenceData.class);
    private final AccessContext context = mock(AccessContext.class);
    private final PenaltyOrderService service =
            new PenaltyOrderService(
                    mock(JdbcTemplate.class),
                    employees,
                    org,
                    mock(PayrollRecords.class),
                    mock(RuleEngine.class),
                    mock(NetPayFloors.class),
                    references,
                    mock(Authorization.class),
                    mock(MakerChecker.class),
                    mock(DocumentStore.class),
                    mock(ObjectMapper.class),
                    Clock.systemUTC(),
                    mock(in.gov.fci.payroll.audit.api.AuditService.class));

    private static Request request(
            String eis, String nature, String reference, LocalDate effective) {
        return new Request(eis, nature, reference, effective, BigDecimal.TEN, 1, "202604");
    }

    private void refused(ErrorCode code, Request r) {
        assertThatThrownBy(() -> service.preview(context, r))
                .isInstanceOfSatisfying(
                        FciException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }

    /** A PEN ORDER refusal for exactly this cause: the causes share one code (US-GAP-236). */
    private void refusedBecause(String why, Request r) {
        assertThatThrownBy(() -> service.preview(context, r))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> {
                            assertThat(ex.getErrorCode()).isEqualTo(PenaltyErrorCode.ORDER);
                            assertThat(ex.getMessageArgs()).containsExactly(why);
                        });
    }

    @Test
    void SRS_14_12_anOrderIsNotSubmittedWithoutItsCopy() {
        Request r = request("E1", "RECOVERY", "O-1", DAY);
        for (Copy copy :
                new Copy[] {
                    null,
                    new Copy("ORDER", "O-1", DAY, "DGM", "o.pdf", null),
                    new Copy("ORDER", "O-1", DAY, "DGM", "o.pdf", new byte[0])
                }) {
            assertThatThrownBy(() -> service.submit(context, r, copy))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            ex ->
                                    assertThat(ex.getErrorCode())
                                            .isEqualTo(PenaltyErrorCode.ORDER_COPY));
        }
    }

    @Test
    void SRS_14_1_SRS_14_8_aRecoveryOrderNeedsAServingMemberARecoveryNatureAReferenceAndADay() {
        String reference = "the order reference is required, printable, at most 60 characters";
        refusedBecause("the order is missing", null);
        when(employees.memberByEis(null)).thenReturn(Optional.empty());
        refusedBecause("no such member", request(null, "RECOVERY", "O-1", DAY));
        // US-GAP-236 (SRS-14-11 l.2972, l.3019): the nature is valid, so the refusal of a dismissed
        // member is the in-service check and nothing else.
        when(references.contains(PenaltyOrderService.NATURES, "RECOVERY")).thenReturn(true);
        when(employees.memberByEis("E0"))
                .thenReturn(
                        Optional.of(new EmployeeDirectory.Member(1L, "E0", "A", 5L, "DISMISSED")));
        refusedBecause("the member is not in service", request(" E0 ", "RECOVERY", "O-1", DAY));
        when(employees.memberByEis("E1"))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.Member(2L, "E1", "B", 5L, "ON_DEPUTATION")));
        refusedBecause("choose a penalty nature of the list", request("E1", null, "O-1", DAY));
        refusedBecause("choose a penalty nature of the list", request("E1", "FINE", "O-1", DAY));
        when(references.contains(PenaltyOrderService.NATURES, "CENSURE")).thenReturn(true);
        refused(PenaltyErrorCode.HRMS_ONLY, request("E1", "CENSURE", "O-1", DAY));
        refusedBecause(reference, request("E1", "RECOVERY", null, DAY));
        refusedBecause(reference, request("E1", "RECOVERY", "x".repeat(61), DAY));
        refusedBecause(reference, request("E1", "RECOVERY", "O 1", DAY));
        refusedBecause("the effective date is required", request("E1", "RECOVERY", "O-1", null));
        when(org.cycleUnitOf(5L)).thenReturn(Optional.empty());
        refused(ApiErrorCode.CYCLE_NO_UNIT, request("E1", "RECOVERY", "O-1", DAY));
    }
}
