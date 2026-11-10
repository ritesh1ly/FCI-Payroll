package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.api.EmployeeEvents;
import in.gov.fci.payroll.employee.api.HrmsNewEmployees.NewEmployee;
import in.gov.fci.payroll.employee.api.HrmsNewEmployees.Outcome;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Onboarding of a new staff record (plan 16 D16-34 … D16-37; SRS-1-11, SRS-1-12, SRS-1-13): each
 * reference the record names must exist, an EIS already held by another person is held, a
 * retirement date only with its age, and the Checker's decision on a record that left onboarding.
 */
class OnboardingServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AuditService audit = mock(AuditService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ObjectMapper json = JsonMapper.builder().build();
    private final AccessContext context = mock(AccessContext.class);
    private final OnboardingService service = service(json);

    private OnboardingService service(ObjectMapper mapper) {
        return new OnboardingService(
                jdbc, mock(Authorization.class), org, approvals, parameters, audit, events, mapper);
    }

    private static NewEmployee employee(String cpf) {
        return new NewEmployee(
                "104990",
                "Kavita",
                null,
                null,
                null,
                LocalDate.of(1994, 3, 12),
                LocalDate.of(2026, 10, 1),
                "AM",
                "E-2",
                "DH001",
                "DMOPO7101K",
                "999900104886",
                "300000007101",
                "SBIN0DEMO01",
                cpf,
                null,
                null,
                null);
    }

    private void row(String part, Map<String, Object> value) {
        when(jdbc.queryForList(contains(part), any(Object[].class)))
                .thenReturn(value == null ? List.of() : List.of(value));
    }

    private void id(String part, Long value) {
        when(jdbc.queryForList(contains(part), eq(Long.class), any(Object[].class)))
                .thenReturn(value == null ? List.of() : List.of(value));
    }

    @BeforeEach
    void wire() {
        when(parameters.configured(SystemParameters.Code.RETIREMENT_AGE))
                .thenReturn(OptionalLong.empty());
        row("FROM pay_scale_band", Map.of("BAND_ID", 2L, "SCALE_TYPE", "IDA"));
        row("FROM org_location WHERE location_code", Map.of("LOCATION_ID", 12L, "ZONE_ID", 1L));
        id("FROM org_designation", 3L);
        id("FROM bank_branch", 9L);
        row("WHERE emp_num = ? AND parent_zone_id", null);
        id("SELECT employee_id FROM employee WHERE emp_num", 70L);
    }

    @Test
    void SRS_1_52_eachReferenceOfTheRecordMustExist() {
        row("FROM pay_scale_band", null);
        assertThat(service.receive(employee(null)).defect()).isEqualTo("PAY_SCALE");
        Map<String, Object> noType = new HashMap<>();
        noType.put("BAND_ID", 2L);
        noType.put("SCALE_TYPE", null);
        row("FROM pay_scale_band", noType);
        assertThat(service.receive(employee(null)).defect()).isEqualTo("PAY_SCALE");
        wire();
        Map<String, Object> noZone = new HashMap<>();
        noZone.put("LOCATION_ID", 12L);
        noZone.put("ZONE_ID", null);
        row("FROM org_location WHERE location_code", noZone);
        assertThat(service.receive(employee(null)).defect()).isEqualTo("POSTING");
        wire();
        id("FROM org_designation", null);
        assertThat(service.receive(employee(null)).defect()).isEqualTo("DESIGNATION");
        wire();
        id("FROM bank_branch", null);
        assertThat(service.receive(employee(null)).defect()).isEqualTo("IFSC");
    }

    @Test
    void SRS_1_13_anEisHeldByAnotherPersonOrACpfCodeAlreadyUsedIsHeld() {
        // SRS-1-18: the CPF Code from HRMS must be unique across the Corporation; one already used
        // holds the record.
        row(
                "WHERE emp_num = ? AND parent_zone_id",
                Map.of("EMPLOYEE_ID", 5L, "PAN_NUMBER", "ABCPX1234Z"));
        assertThat(service.receive(employee(null)))
                .satisfies(
                        r -> {
                            assertThat(r.outcome()).isEqualTo(Outcome.HELD_DUPLICATE);
                            assertThat(r.defect()).isEqualTo("EIS");
                        });
        row("WHERE emp_num = ? AND parent_zone_id", null);
        when(jdbc.queryForList(
                        contains("WHERE cpf_code = ?"), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("104001"));
        assertThat(service.receive(employee("CPF/DL/1")).matchedEis()).isEqualTo("104001");
    }

    @Test
    void SRS_SEC_5_eachAadhaarCheckIsAuditedWithItsOutcomeAndNeverTheNumber() {
        // SRS-1-53: the Aadhaar from HRMS is used only to prevent a duplicate member record, never
        // shown or logged.
        when(jdbc.queryForList(
                        contains("WHERE aadhaar_number = ?"),
                        eq(String.class),
                        any(Object[].class)))
                .thenReturn(List.of("104002"));
        assertThat(service.receive(employee(null)).matchedEis()).isEqualTo("104002");
        ArgumentCaptor<AuditService.Entry> rows = ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("hrms.interface"), rows.capture());
        assertThat(rows.getValue())
                .satisfies(
                        r -> {
                            assertThat(r.entityKey()).isEqualTo("104990");
                            assertThat(r.fieldName()).isEqualTo("AADHAAR_CHECK");
                            assertThat(r.newValue()).isEqualTo("MATCH 104002");
                            assertThat(r.reason()).isNotBlank();
                            assertThat(r.toString()).doesNotContain("999900104886", "4886");
                        });
    }

    @Test
    void SRS_SEC_5_aCheckWithoutAMatchIsAuditedAndAPanMatchNeverUsesTheAadhaar() {
        assertThat(service.receive(employee(null)).outcome()).isEqualTo(Outcome.CREATED);
        ArgumentCaptor<AuditService.Entry> rows = ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit, times(2)).record(eq("hrms.interface"), rows.capture());
        assertThat(rows.getAllValues().getFirst().newValue()).isEqualTo("NO_MATCH");
        clearInvocations(audit, jdbc);
        when(jdbc.queryForList(
                        contains("WHERE pan_number = ?"), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("104003"));
        assertThat(service.receive(employee(null)).matchedEis()).isEqualTo("104003");
        verify(audit, never()).record(any(), any());
        verify(jdbc, never())
                .queryForList(contains("aadhaar_number"), eq(String.class), any(Object[].class));
    }

    @Test
    void SRS_1_22_theRetirementDateIsWrittenOnlyWithItsAge() {
        when(parameters.configured(SystemParameters.Code.RETIREMENT_AGE))
                .thenReturn(OptionalLong.of(60));
        assertThat(service.receive(employee(" ")).outcome()).isEqualTo(Outcome.CREATED);
        verify(jdbc)
                .update(
                        contains("INSERT INTO employee"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(java.sql.Date.valueOf("2054-03-31")));
    }

    private void record(String status, String pattern) {
        Map<String, Object> r = new HashMap<>();
        r.put("EMPLOYEE_ID", 70L);
        r.put("LOCATION_ID", 12L);
        r.put("PAY_PATTERN", pattern);
        r.put("RECORD_STATUS", status);
        row("record_status FROM employee WHERE emp_num", r);
    }

    @Test
    void SRS_1_11_anUnknownRecordOrOfficeIsNotFoundAndAPayloadThatCannotBeWrittenFails() {
        row("record_status FROM employee WHERE emp_num", null);
        assertThatThrownBy(() -> service.submit(context, "104990", null, "Ready"))
                .isInstanceOf(FciException.class);
        record("PAYROLL_SETUP_PENDING", "IDA");
        when(org.cycleUnitOf(12L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.submit(context, "104990", null, "Ready"))
                .isInstanceOf(FciException.class);
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(3L);
        when(org.cycleUnitOf(12L)).thenReturn(Optional.of(unit));
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any()))
                .thenThrow(new tools.jackson.core.exc.StreamWriteException(null, "no"));
        assertThatThrownBy(() -> service(broken).submit(context, "104990", null, "Ready"))
                .isInstanceOf(IllegalStateException.class);
        when(approvals.submit(any(), any(Submission.class), any()))
                .thenReturn(mock(SubmittedApproval.class));
        service.submit(context, "104990", null, " Ready ");
        verify(jdbc).update(contains("record_status = 'CHANGE_PENDING'"), eq(70L));
    }

    private ApprovalDecided decided(ApprovalStatus outcome, String payload, String reason) {
        return new ApprovalDecided(
                31L,
                "M-01",
                OnboardingService.ENTITY,
                "70",
                outcome,
                reason,
                payload,
                "ddo",
                Instant.now(),
                null);
    }

    @Test
    void SRS_1_12_aDecisionOnARecordGoneOrUnreadableAndAReturnWithoutAnOffice() {
        row("SELECT emp_num, location_id FROM employee", null);
        service.on(decided(ApprovalStatus.APPROVED, "{}", null));
        verify(jdbc, never())
                .update(contains("UPDATE employee SET record_status"), any(Object[].class));
        row(
                "SELECT emp_num, location_id FROM employee",
                Map.of("EMP_NUM", "104990", "LOCATION_ID", 12L));
        assertThatThrownBy(() -> service.on(decided(ApprovalStatus.APPROVED, "{", null)))
                .isInstanceOf(IllegalStateException.class);
        when(org.cycleUnitOf(12L)).thenReturn(Optional.empty());
        service.on(decided(ApprovalStatus.RETURNED, null, "Flags"));
        verify(events, never()).publishEvent(any(EmployeeEvents.OnboardingReturned.class));
        service.on(decided(ApprovalStatus.REJECTED, null, null));
        verify(audit, times(2)).record(eq("ddo"), any());
        service.on(
                new ApprovalDecided(
                        31L,
                        "M-01",
                        "employee",
                        "70",
                        ApprovalStatus.APPROVED,
                        null,
                        null,
                        "ddo",
                        Instant.now(),
                        null));
    }
}
