package in.gov.fci.payroll.employee.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.employee.business.model.Employee;
import in.gov.fci.payroll.employee.persistence.EmployeeRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The directory other modules read members through (SRS-SEC-4: identity and unit, never the
 * restricted fields): a missing identifier is simply no member, an EIS is matched stripped, and
 * bulk reads stay within Oracle's 1,000-value IN list.
 */
class EmployeeDirectoryTest {

    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final EmployeeDirectory directory = new EmployeeDirectory(employees);

    private static Employee employee(long id, String eis) {
        Employee e = mock(Employee.class);
        when(e.getId()).thenReturn(id);
        when(e.getEmpNum()).thenReturn(eis);
        when(e.fullName()).thenReturn("Asha Verma");
        when(e.getLocationId()).thenReturn(300L);
        when(e.getEmploymentStatus()).thenReturn("IN_SERVICE");
        return e;
    }

    @Test
    void aMissingIdentifierIsNoMemberAndNeverReachesTheDatabase() {
        assertThat(directory.idOf(null)).isEmpty();
        assertThat(directory.locationOf(null)).isEmpty();
        assertThat(directory.eisOf(null)).isEmpty();
        assertThat(directory.member(null)).isEmpty();
        assertThat(directory.memberByEis(null)).isEmpty();
        assertThat(directory.memberByEis("   ")).isEmpty();
        assertThat(directory.cpfIdentity(null)).isEmpty();
        assertThat(directory.cpfEligibility(null)).isEmpty();
        assertThat(directory.cpfKyc(null)).isEmpty();
        assertThat(directory.idsAt(null)).isEmpty();
        assertThat(directory.idsAt(List.of())).isEmpty();
        assertThat(directory.members(null)).isEmpty();
        assertThat(directory.cpfIdentities(List.of())).isEmpty();
        verifyNoInteractions(employees);
    }

    @Test
    void anEisIsMatchedStrippedAndALocationListsItsMembers() {
        Employee asha = employee(9L, "E100");
        when(employees.findByEmpNum("E100")).thenReturn(Optional.of(asha));
        when(employees.idsAt(List.of(300L))).thenReturn(Set.of(9L));

        assertThat(directory.idOf(" E100 ")).contains(9L);
        assertThat(directory.memberByEis(" E100 "))
                .contains(
                        new EmployeeDirectory.Member(9L, "E100", "Asha Verma", 300L, "IN_SERVICE"));
        assertThat(directory.idsAt(List.of(300L))).containsExactly(9L);
    }

    @Test
    void bulkReadsAskAtMostAThousandDistinctIdsPerQuery() {
        List<Long> ids = new ArrayList<>();
        for (long id = 1; id <= 1001; id++) {
            ids.add(id);
        }
        ids.add(1L);
        when(employees.findByIdIn(any()))
                .thenAnswer(
                        invocation -> {
                            Collection<Long> asked = invocation.getArgument(0);
                            return asked.stream().map(id -> employee(id, "E" + id)).toList();
                        });

        Map<Long, EmployeeDirectory.Member> members = directory.members(ids);

        assertThat(members).hasSize(1001);
        ArgumentCaptor<Collection<Long>> batches = ArgumentCaptor.captor();
        verify(employees, times(2)).findByIdIn(batches.capture());
        assertThat(batches.getAllValues()).extracting(Collection::size).containsExactly(1000, 1);
    }

    /** SRS-7-13 / SRS-7-23: a PAN's state from its structure; no member, no PAN. */
    @Test
    void SRS_7_13_aPanIsMissingValidOrInvalidAndNoMemberHasNone() {
        assertThat(EmployeeDirectory.panStatusOf(null))
                .isEqualTo(EmployeeDirectory.PanStatus.MISSING);
        assertThat(EmployeeDirectory.panStatusOf(" "))
                .isEqualTo(EmployeeDirectory.PanStatus.MISSING);
        assertThat(EmployeeDirectory.panStatusOf(" KLKPK7001A "))
                .isEqualTo(EmployeeDirectory.PanStatus.VALID);
        assertThat(EmployeeDirectory.panStatusOf("KLKPK7001"))
                .isEqualTo(EmployeeDirectory.PanStatus.INVALID);
        assertThat(directory.panStatus(null)).isEmpty();
        assertThat(directory.panForStatutoryOutput(null)).isEmpty();
        verifyNoInteractions(employees);
    }

    /**
     * SRS l.2589 / SRS-7-23: the C-10 register shows the PAN masked with its state, never whole -
     * in the one format every module uses ({@code PiiMasks.pan}, R-PII): the last four behind six
     * asterisks; a value that is not ten characters, or a missing one, shows none of itself.
     */
    @Test
    void SRS_7_23_aPanIsShownMaskedWithItsStateAndAShortOrMissingOneHasNoDigits() {
        assertThat(EmployeeDirectory.maskedPanOf(" KLKPK7001A "))
                .isEqualTo(
                        new EmployeeDirectory.MaskedPan(
                                "******001A", EmployeeDirectory.PanStatus.VALID));
        assertThat(EmployeeDirectory.maskedPanOf("KLKPK7001"))
                .isEqualTo(
                        new EmployeeDirectory.MaskedPan(
                                "****", EmployeeDirectory.PanStatus.INVALID));
        assertThat(EmployeeDirectory.maskedPanOf("KLKPK7001AB"))
                .isEqualTo(
                        new EmployeeDirectory.MaskedPan(
                                "****", EmployeeDirectory.PanStatus.INVALID));
        assertThat(EmployeeDirectory.maskedPanOf("AB1"))
                .isEqualTo(
                        new EmployeeDirectory.MaskedPan(
                                "****", EmployeeDirectory.PanStatus.INVALID));
        assertThat(EmployeeDirectory.maskedPanOf("   "))
                .isEqualTo(
                        new EmployeeDirectory.MaskedPan(null, EmployeeDirectory.PanStatus.MISSING));
        assertThat(EmployeeDirectory.maskedPanOf(null))
                .isEqualTo(
                        new EmployeeDirectory.MaskedPan(null, EmployeeDirectory.PanStatus.MISSING));
        Employee e = mock(Employee.class);
        when(e.getId()).thenReturn(5L);
        when(e.getPanNumber()).thenReturn("KLKPK7001A");
        when(employees.findByIdIn(any())).thenReturn(List.of(e));
        assertThat(directory.maskedPans(List.of(5L)).get(5L).masked()).isEqualTo("******001A");
        assertThat(directory.maskedPans(List.of())).isEmpty();
    }

    /**
     * SRS I-22 l.2234: a member is found by CPF code or UAN only when exactly one record holds it.
     */
    @Test
    void SRS_l2234_aCpfCodeOrUanFindsOneMemberNeverAGuess() {
        Employee e = mock(Employee.class);
        when(e.getId()).thenReturn(9L);
        when(e.getEmpNum()).thenReturn("104882");
        when(employees.findByCpfCode("CPF/104882")).thenReturn(List.of(e));
        when(employees.findByUanNumber("100000104882")).thenReturn(List.of(e, e));
        assertThat(directory.memberByCpfCodeOrUan(" CPF/104882 ", null))
                .map(EmployeeDirectory.Member::eis)
                .contains("104882");
        assertThat(directory.memberByCpfCodeOrUan(null, "100000104882"))
                .as("two records hold the UAN")
                .isEmpty();
        assertThat(directory.memberByCpfCodeOrUan("", " ")).isEmpty();
        assertThat(directory.memberByCpfCodeOrUan(null, null)).isEmpty();
    }

    /** SRS-INT-27: the HRMS intake reads the employee type; labour have no HRMS interface. */
    @Test
    void SRS_INT_27_theEmployeeTypeIsReadForAKnownRecordAndIsAbsentForAMissingOrUnknownOne() {
        Employee staff = mock(Employee.class);
        when(staff.getEmployeeType()).thenReturn("STAFF");
        Employee labour = mock(Employee.class);
        when(labour.getEmployeeType()).thenReturn("DEPT_LABOUR");
        when(employees.findById(1L)).thenReturn(Optional.of(staff));
        when(employees.findById(2L)).thenReturn(Optional.of(labour));
        when(employees.findById(3L)).thenReturn(Optional.empty());
        assertThat(directory.employeeTypeOf(1L)).contains("STAFF");
        assertThat(directory.employeeTypeOf(2L)).contains("DEPT_LABOUR");
        assertThat(directory.employeeTypeOf(3L)).isEmpty();
        assertThat(directory.employeeTypeOf(null)).isEmpty();
    }
}
