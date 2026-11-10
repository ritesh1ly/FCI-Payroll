package in.gov.fci.payroll.cpf.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.employee.api.EmployeeDirectory.CpfIdentity;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.platform.error.FciException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SRS-6-59, BR-CPF-040: a CPF posting needs a CPF member whose CPF code and parent zone came from
 * HRMS; a gap is raised on X-05 with the field to correct.
 */
class CpfMembersTest {

    private final EmployeeDirectory directory = mock(EmployeeDirectory.class);
    private final CpfExceptions exceptions = mock(CpfExceptions.class);
    private final CpfMembers members = new CpfMembers(directory, exceptions);

    private void identity(String fund, String code, Long zone) {
        when(directory.cpfIdentity(7L))
                .thenReturn(
                        Optional.of(
                                new CpfIdentity(7L, "104882", code, zone, fund, "IN_SERVICE", 3L)));
    }

    private static String code(Throwable t) {
        return ((FciException) t).getErrorCode().code();
    }

    @Test
    void SRS_6_59_aCpfMemberWithCodeAndZoneIsReturned() {
        identity("CPF", "CPF/104882", 11L);
        assertThat(members.require(7L, "x"))
                .isEqualTo(new CpfMembers.Member(7L, "104882", "CPF/104882", 11L, 3L));
        verify(exceptions, never()).raise(any(), any());
    }

    @Test
    void SRS_6_59_anUnknownMemberOrAnotherFundIsRefused() {
        when(directory.cpfIdentity(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> members.require(7L, "x"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("COM-404-NOT-FOUND"));
        for (String fund : new String[] {"NPS", "GPF", "NONE", null}) {
            identity(fund, "CPF/1", 11L);
            assertThatThrownBy(() -> members.require(7L, "x"))
                    .as(String.valueOf(fund))
                    .satisfies(t -> assertThat(code(t)).isEqualTo("CPF-422-NOT-MEMBER"));
        }
        verify(exceptions, never()).raise(any(), any());
    }

    // BR-EMPM-008: a CPF-deducting member without a CPF code is raised.
    @Test
    void SRS_6_59_aMissingOrBlankCodeOrAMissingZoneIsRaisedOnX05NamingTheField() {
        String[][] cases = {{null, "cpf_code"}, {"  ", "cpf_code"}, {"CPF/1", "parent_zone_id"}};
        for (String[] c : cases) {
            identity("CPF", c[0], c[1].equals("parent_zone_id") ? null : 11L);
            assertThatThrownBy(() -> members.require(7L, "maker"))
                    .satisfies(t -> assertThat(code(t)).isEqualTo("CPF-422-CPF-CODE"));
        }
        ArgumentCaptor<ExceptionWorklist.NewEntry> raised =
                ArgumentCaptor.forClass(ExceptionWorklist.NewEntry.class);
        verify(exceptions, org.mockito.Mockito.times(3)).raise(eq("maker"), raised.capture());
        assertThat(raised.getAllValues())
                .extracting(ExceptionWorklist.NewEntry::fieldName)
                .containsExactly("cpf_code", "cpf_code", "parent_zone_id");
        ExceptionWorklist.NewEntry first = raised.getAllValues().get(0);
        assertThat(first.exceptionCode()).isEqualTo("CPF_CODE_MISSING");
        assertThat(first.employeeId()).isEqualTo(7L);
        assertThat(first.fieldSource()).isEqualTo("HRMS");
        assertThat(first.severity()).isEqualTo("BLOCKING");
        assertThat(first.detail()).contains("code").doesNotContain("parent zone");
        assertThat(raised.getAllValues().get(2).detail()).contains("parent zone");
    }
}
