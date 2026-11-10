package in.gov.fci.payroll.allowance.orchestration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.allowance.business.ClaimRules;
import in.gov.fci.payroll.allowance.persistence.AllowanceRepositories;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.HrmsTaskEffects.Task;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * An employee reimbursement claim from HRMS through I-21 (SRS-11-14; plan 10 D10-5): a record that
 * is missing, empty, not JSON or not a JSON object is refused as not readable; an approved claim
 * whose claim type has no active pay code is held on the exception worklist, never paid under a
 * guessed code.
 */
class EmployeeClaimRecordTest {

    private static final String OK =
            "{\"claimType\":\"TRANSPORT\",\"periodFrom\":\"2026-10-01\","
                    + "\"periodTo\":\"2026-10-31\",\"amount\":\"500\"}";

    private final AllowanceRepositories.Claims claims = mock(AllowanceRepositories.Claims.class);
    private final ClaimCatalogue catalogue = mock(ClaimCatalogue.class);
    private final ExceptionWorklist worklist = mock(ExceptionWorklist.class);
    private final EmployeeClaimEffects effects =
            new EmployeeClaimEffects(
                    claims,
                    catalogue,
                    worklist,
                    mock(AuditService.class),
                    JsonMapper.builder().build());

    EmployeeClaimRecordTest() {
        when(catalogue.member(7L))
                .thenReturn(
                        new ClaimCatalogue.MemberFacts(
                                7L, "E7", "Asha", "STAFF", "IN_SERVICE", null, 40L));
        when(catalogue.types(eq("EMPLOYEE"), any()))
                .thenReturn(List.of(new ClaimCatalogue.ClaimType("TRANSPORT", "Transport", 915)));
        when(catalogue.norm(eq("TRANSPORT"), eq("EMPLOYEE"), any()))
                .thenReturn(
                        new ClaimCatalogue.Norm(
                                3L, new BigDecimal("1000"), ClaimRules.Periodicity.MONTHLY));
        when(catalogue.adjudicate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(
                        new ClaimRules.Adjudication(
                                new BigDecimal("500"), new BigDecimal("500"), false));
        when(claims.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private void approve() {
        effects.apply(
                new Task(
                        5L,
                        "T-5",
                        7L,
                        EmployeeClaimEffects.FIELD,
                        OK,
                        LocalDate.of(2026, 10, 1),
                        "HR-5"),
                "checker@x",
                9L,
                Instant.parse("2026-10-29T06:00:00Z"));
    }

    @Test
    void SRS_11_14_aClaimTypeWithoutAnActivePayCodeIsHeld() {
        when(catalogue.type(eq("TRANSPORT"), any())).thenReturn(Optional.empty());
        approve();
        verify(worklist).raise(eq("checker@x"), any());
    }

    @Test
    void SRS_11_14_aClaimTypeWithAnActivePayCodeIsNotHeld() {
        when(catalogue.type(eq("TRANSPORT"), any()))
                .thenReturn(
                        Optional.of(new ClaimCatalogue.ClaimType("TRANSPORT", "Transport", 915)));
        when(catalogue.payCodeActive(eq(915), any())).thenReturn(true);
        approve();
        verify(worklist, never()).raise(any(), any());
    }

    @Test
    void SRS_11_14_aRecordThatIsNotAJsonObjectIsNotReadable() {
        for (String bad : Arrays.asList(null, "", "{bad", "[1]", "\"text\"")) {
            assertThatThrownBy(() -> effects.check(EmployeeClaimEffects.FIELD, 7L, bad))
                    .as(String.valueOf(bad))
                    .isInstanceOf(FciException.class);
        }
    }
}
