package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.employee.business.model.Employee;
import in.gov.fci.payroll.employee.persistence.EmployeeRepository;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.api.OrgMasters;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class EmployeeServiceTest {

    private static AccessContext scoped(Set<Long> dataScope) {
        return new AccessContext(
                1L,
                "viewer@x",
                "Viewer",
                "s",
                "P",
                10L,
                null,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                dataScope);
    }

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final OrgMasters masters = mock(OrgMasters.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final EmployeeService service =
            new EmployeeService(employees, masters, org, mock(Authorization.class));

    /** SRS-22-8: a session whose access groups name no office sees no employee at all. */
    @Test
    void SRS_22_8_anEmptyDataScopeListsCountsAndHeadCountsNothingWithoutAQuery() {
        AccessContext nobody = scoped(Set.of());
        assertThat(service.list(nobody, "sharma", null, null, new PageSpec(0, 20)).total())
                .isZero();
        assertThat(service.statusCounts(nobody, null, null))
                .isEqualTo(new EmployeeService.StatusCounts(0, 0, 0, 0, 0, 0));
        assertThat(service.headCount(nobody)).isEmpty();
        verifyNoInteractions(employees);
    }

    /**
     * SRS-NF-3 and Oracle review F3: the EIS number is unique per parent zone only (V002
     * uk_employee_emp_num), so the M-01 pages end on the employee id: two members of one EIS in
     * different zones never swap between pages.
     */
    @Test
    void SRS_NF_3_theRegisterPagesEndOnTheEmployeeIdBecauseTheEisRepeatsAcrossZones() {
        when(employees.search(any(), any(), any(), any(), any())).thenReturn(Page.empty());
        service.list(scoped(Set.of(10L)), null, null, null, new PageSpec(0, 20));
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(employees).search(any(), any(), any(), any(), page.capture());
        assertThat(page.getValue().getSort())
                .containsExactly(Sort.Order.asc("empNum"), Sort.Order.asc("id"));
    }

    @Test
    void theOwnRecordNeedsASessionLinkedToAnEmployee() {
        refused(() -> service.own(scoped(Set.of(10L))), ApiErrorCode.PERMISSION_DENIED);
        verifyNoInteractions(employees);
    }

    @Test
    void aRecordWhoseOfficeDesignationOrBandIsMissingFromTheMastersStillOpens() {
        Employee record = mock(Employee.class);
        when(record.getId()).thenReturn(9L);
        when(record.getEmpNum()).thenReturn("E100");
        when(record.getLocationId()).thenReturn(300L);
        when(record.getDesignationId()).thenReturn(5L);
        when(record.getBandId()).thenReturn(2L);
        when(employees.findByEmpNum("E100")).thenReturn(Optional.of(record));
        when(masters.designationsById()).thenReturn(Map.of());
        when(masters.payBandsById()).thenReturn(Map.of());
        when(org.location(300L)).thenReturn(Optional.empty());

        EmployeeService.EmployeeDetail detail = service.get(scoped(Set.of(300L)), " E100 ");

        assertThat(detail.eis()).isEqualTo("E100");
        assertThat(detail.location()).isNull();
        assertThat(detail.locationCode()).isNull();
        assertThat(detail.designation()).isNull();
        assertThat(detail.band()).isNull();
        assertThat(detail.nextIncrementDue()).as("no increment module: none shown").isNull();
        // SRS-13-1 (US-GAP-51): with the increment module, the next due date it holds.
        EmployeeService withIncrements =
                new EmployeeService(
                        employees,
                        masters,
                        org,
                        mock(Authorization.class),
                        id -> Optional.of(java.time.LocalDate.of(2027, 7, 1)));
        assertThat(withIncrements.get(scoped(Set.of(300L)), "E100").nextIncrementDue())
                .isEqualTo(java.time.LocalDate.of(2027, 7, 1));
        // No number at all is no record, not a lookup of the empty string that could match one.
        refused(() -> service.get(scoped(Set.of(300L)), null), CommonErrorCode.NOT_FOUND);
    }

    /**
     * SRS-1-52 / SRS-1-54 / SRS-1-76 (US-GAP-40, US-16-04 AC4): the M-01 view carries the field
     * table's HRMS dates and identity fields, the EPS details, the control flags, the HRA city
     * class of the place of posting and the DA / HRA rates the rule engine has in force for the
     * pattern; Aadhaar only as its last four digits and the UAN only as "on record".
     */
    @Test
    void SRS_1_52_theViewShowsTheFieldTableGroupsWithAadhaarMaskedAndTheRatesInForce() {
        Employee record = mock(Employee.class);
        when(record.getId()).thenReturn(9L);
        when(record.getEmpNum()).thenReturn("E100");
        when(record.getLocationId()).thenReturn(300L);
        when(record.getPayPattern()).thenReturn("CDA");
        when(record.getBirthDate()).thenReturn(java.time.LocalDate.of(1980, 5, 2));
        when(record.getJoiningDate()).thenReturn(java.time.LocalDate.of(2005, 7, 1));
        when(record.getRetirementDate()).thenReturn(java.time.LocalDate.of(2040, 5, 31));
        when(record.getGender()).thenReturn("F");
        when(record.getHrmsEmployeeType()).thenReturn("REGULAR");
        when(record.aadhaarLastFourMasked()).thenReturn("XXXX-XXXX-1234");
        when(record.getPranNumber()).thenReturn("110012345678");
        when(record.hasUan()).thenReturn(true);
        when(record.getEpsNumber()).thenReturn("EPS-1");
        when(record.getEpsPre2014Member()).thenReturn(Boolean.TRUE);
        when(record.isHoldFlag()).thenReturn(true);
        when(record.isAbscondingFlag()).thenReturn(false);
        when(record.isStopPaymentFlag()).thenReturn(true);
        when(record.isCasualLabour()).thenReturn(false);
        when(employees.findByEmpNum("E100")).thenReturn(Optional.of(record));
        when(masters.designationsById()).thenReturn(Map.of());
        when(masters.payBandsById()).thenReturn(Map.of());
        in.gov.fci.payroll.organisation.business.model.OrgLocation posting =
                mock(in.gov.fci.payroll.organisation.business.model.OrgLocation.class);
        when(posting.getCityClass()).thenReturn("Y");
        when(org.location(300L)).thenReturn(Optional.of(posting));
        in.gov.fci.payroll.configuration.api.PayConfiguration rates =
                mock(in.gov.fci.payroll.configuration.api.PayConfiguration.class);
        java.time.Instant now = java.time.Instant.parse("2026-10-31T20:00:00Z");
        // 01:30 IST on 1 November: the business day is India's, not the UTC one.
        java.time.LocalDate day = java.time.LocalDate.of(2026, 11, 1);
        when(rates.staffConfiguration(day))
                .thenReturn(
                        new in.gov.fci.payroll.configuration.api.PayConfiguration.Snapshot(
                                day,
                                Map.of("CDA", new java.math.BigDecimal("45")),
                                Map.of(
                                        "Y",
                                        new java.math.BigDecimal("20"),
                                        "CDA/Y",
                                        new java.math.BigDecimal("15")),
                                Map.of(),
                                false,
                                Map.of(),
                                Map.of(),
                                "h"));
        EmployeeService full =
                new EmployeeService(
                        employees,
                        masters,
                        org,
                        mock(Authorization.class),
                        id -> Optional.empty(),
                        rates,
                        java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));

        EmployeeService.EmployeeDetail d = full.get(scoped(Set.of(300L)), "E100");

        assertThat(d.birthDate()).isEqualTo(java.time.LocalDate.of(1980, 5, 2));
        assertThat(d.joiningDate()).isEqualTo(java.time.LocalDate.of(2005, 7, 1));
        assertThat(d.retirementDate()).isEqualTo(java.time.LocalDate.of(2040, 5, 31));
        assertThat(d.gender()).isEqualTo("F");
        assertThat(d.employeeCategory()).isEqualTo("REGULAR");
        assertThat(d.aadhaarMasked()).isEqualTo("XXXX-XXXX-1234");
        assertThat(d.pranNumber()).isEqualTo("110012345678");
        assertThat(d.uanOnRecord()).isTrue();
        assertThat(d.epsNumber()).isEqualTo("EPS-1");
        assertThat(d.epsPre2014Member()).isTrue();
        assertThat(d.holdFlag()).isTrue();
        assertThat(d.abscondingFlag()).isFalse();
        assertThat(d.stopPaymentFlag()).isTrue();
        assertThat(d.casualLabour()).isFalse();
        assertThat(d.payStatus()).as("SRS l.3738: in service with the Hold flag").isEqualTo("HOLD");
        assertThat(d.hraCityClass()).isEqualTo("Y");
        assertThat(d.daRateApplied()).isEqualByComparingTo("45");
        assertThat(d.hraRateApplied()).as("the CDA rule (SRS-1-3)").isEqualByComparingTo("15");

        // No posting: no city class and no HRA rate; a pattern without a DA rate shows none.
        when(org.location(300L)).thenReturn(Optional.empty());
        when(record.getPayPattern()).thenReturn("IDA");
        EmployeeService.EmployeeDetail none = full.get(scoped(Set.of(300L)), "E100");
        assertThat(none.hraCityClass()).isNull();
        assertThat(none.hraRateApplied()).isNull();
        assertThat(none.daRateApplied()).isNull();
        // Without the rule engine (the shorter constructor) no rate is shown.
        assertThat(service.get(scoped(Set.of(300L)), "E100").daRateApplied()).isNull();
    }

    @Test
    void SRS_SEC_4_panIsShownByItsLastFourCharactersOnly() {
        assertThat(EmployeeService.maskPan("ABCPS1234K")).isEqualTo("******234K");
        // BMAD TR-035: a value that is not a PAN is never shown as it is.
        assertThat(EmployeeService.maskPan("ABC12")).isEqualTo("****");
        assertThat(EmployeeService.maskPan(null)).isNull();
        assertThat(EmployeeService.maskPan("SHORT"))
                .as("was shown unmasked before BMAD TR-035")
                .isEqualTo("****");
    }
}
