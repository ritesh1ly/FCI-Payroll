package in.gov.fci.payroll.general.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.general.business.GeneralErrorCode;
import in.gov.fci.payroll.general.business.model.OffCyclePayment;
import in.gov.fci.payroll.general.persistence.GeneralRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.payroll.api.PendingSalary;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The I-20 branches of {@link OffCycleService} that the API cannot reach or that need a state the
 * demo data does not have (SRS-G-10, SRS-G-14). The flows are {@code OffCycleIT}.
 */
class OffCycleServiceTest {

    private static final Long MEMBER = 7L;
    private static final Long OFFICE = 40L;

    private final GeneralRepositories.OffCyclePayments cases =
            mock(GeneralRepositories.OffCyclePayments.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final PayrollRecords payroll = mock(PayrollRecords.class);
    private final PendingSalary pending = mock(PendingSalary.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AccessContext maker = mock(AccessContext.class);

    private final OffCycleService service;

    OffCycleServiceTest() {
        ApiProperties api = mock(ApiProperties.class);
        when(api.businessZone()).thenReturn(ZoneId.of("Asia/Kolkata"));
        service =
                new OffCycleService(
                        mock(in.gov.fci.payroll.platform.export.ExportReader.class),
                        cases,
                        employees,
                        org,
                        payroll,
                        pending,
                        documents,
                        mock(MakerChecker.class),
                        statuses,
                        mock(Authorization.class),
                        mock(AuditService.class),
                        jdbc,
                        api,
                        Clock.fixed(Instant.parse("2026-10-21T05:30:00Z"), ZoneOffset.UTC));
    }

    @BeforeEach
    void member() {
        when(maker.loginId()).thenReturn("maker.delhi@fci-demo.test");
        member("IN_SERVICE");
        when(org.cycleUnitOf(OFFICE)).thenReturn(Optional.empty());
        when(pending.compute(eq(MEMBER), any()))
                .thenReturn(
                        new PendingSalary.Result(
                                true,
                                null,
                                BigDecimal.TEN,
                                BigDecimal.ZERO,
                                BigDecimal.TEN,
                                List.of(),
                                "h"));
        when(cases.saveAndFlush(any()))
                .thenAnswer(
                        call -> {
                            OffCyclePayment c = call.getArgument(0);
                            ReflectionTestUtils.setField(c, "id", 900L);
                            return c;
                        });
        when(statuses.of(any())).thenReturn(Map.of());
    }

    private void member(String employmentStatus) {
        when(employees.memberByEis("106004"))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.Member(
                                        MEMBER, "106004", "Member", OFFICE, employmentStatus)));
        when(employees.member(MEMBER)).thenReturn(Optional.empty()); // views tolerate a gone member
        when(employees.locationOf(MEMBER)).thenReturn(Optional.of(OFFICE));
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        FciException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }

    @Test
    void srsG10_anUnknownCaseTypeIsRefused() {
        assertCode(
                () -> service.capture(maker, "106004", "BONUS", "202609", "ref"),
                GeneralErrorCode.OFFCYCLE_INPUT);
    }

    @Test
    void srsG14_aSeparatedMemberIsPaidOffCycleOnlyForADeathInService() {
        member("SEPARATED");
        assertCode(
                () -> service.capture(maker, "106004", "LATE_JOINER", "202609", "ref"),
                GeneralErrorCode.OFFCYCLE_INPUT);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);
        OffCycleService.CaseView v =
                service.capture(maker, "106004", "DEATH_IN_SERVICE", "202609", "ref");
        assertThat(v.status()).isEqualTo("DRAFT");
        assertThat(v.eis()).isNull(); // the member left the directory: shown without name
    }

    @Test
    void srsG10_aSalaryNotPayableIsNeverCapturedAsZero() {
        when(pending.compute(eq(MEMBER), any()))
                .thenReturn(
                        new PendingSalary.Result(
                                false,
                                "SALARY_STOP",
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                List.of(),
                                "h"));
        assertCode(
                () -> service.capture(maker, "106004", "LATE_JOINER", "202609", "ref"),
                GeneralErrorCode.OFFCYCLE_NOT_COMPUTABLE);
        when(pending.compute(eq(MEMBER), any()))
                .thenThrow(new PendingSalary.NotComputable("DA rate not seeded"));
        assertCode(
                () -> service.capture(maker, "106004", "LATE_JOINER", "202609", "ref"),
                GeneralErrorCode.OFFCYCLE_NOT_COMPUTABLE);
        verify(cases, never()).saveAndFlush(any());
    }

    @Test
    void brCyc010_aSalaryPaidThroughI23FailsTheDuplicateCheck() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
        OffCycleService.CaseView v =
                service.capture(maker, "106004", "LATE_JOINER", "202609", "ref");
        assertThat(v.status()).isEqualTo("BLOCKED");
        assertThat(v.duplicateCheck()).isEqualTo("FAIL: salary for 202609 paid through I-23");
    }

    @Test
    void srsG10_aCaseWaitingForAnAttachmentIsNotEditable() {
        OffCyclePayment c =
                OffCyclePayment.capture(
                        MEMBER, null, "OTHER", "202609", "202610", BigDecimal.TEN, "ref");
        ReflectionTestUtils.setField(c, "id", 901L);
        c.submitted(66L);
        when(cases.findById(901L)).thenReturn(Optional.of(c));
        when(statuses.of(List.of(66L))).thenReturn(Map.of(66L, ApprovalStatus.PENDING_ATTACHMENT));
        assertCode(
                () -> service.attachOrder(maker, 901L, "SANCTION_ORDER", "r", null, "a", "f", null),
                GeneralErrorCode.OFFCYCLE_STATUS);
        assertCode(() -> service.cancel(maker, 901L, "x"), GeneralErrorCode.OFFCYCLE_STATUS);

        c.cancel("withdrawn"); // a cancelled case is not editable whatever its approval
        when(statuses.of(List.of(66L))).thenReturn(Map.of(66L, ApprovalStatus.RETURNED));
        assertCode(() -> service.submit(maker, 901L), GeneralErrorCode.OFFCYCLE_STATUS);
    }

    @Test
    void srsG10_aMonthLockedAfterCaptureIsNeitherSubmittedNorApprovedOffCycle() {
        // The salary month's cycle is open when the draft is captured ...
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(41L);
        when(org.cycleUnitOf(OFFICE)).thenReturn(Optional.of(unit));
        when(payroll.cycle(41L, "202609"))
                .thenReturn(Optional.of(new PayrollRecords.Cycle(5L, 41L, "202609", "COMPUTED")));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);
        OffCyclePayment[] saved = new OffCyclePayment[1];
        // doAnswer: re-stubbing with when(...) would call the @BeforeEach answer with a null.
        doAnswer(
                        call -> {
                            saved[0] = call.getArgument(0);
                            ReflectionTestUtils.setField(saved[0], "id", 900L);
                            return saved[0];
                        })
                .when(cases)
                .saveAndFlush(any());
        assertThat(service.capture(maker, "106004", "LATE_JOINER", "202609", "ref").status())
                .isEqualTo("DRAFT");
        when(cases.findById(900L)).thenReturn(Optional.of(saved[0]));
        when(documents.countActive("off_cycle_payment", "900")).thenReturn(1);

        // ... and month-end locked before it is submitted: it is paid through I-23 instead.
        when(payroll.cycle(41L, "202609"))
                .thenReturn(Optional.of(new PayrollRecords.Cycle(5L, 41L, "202609", "LOCKED")));
        assertCode(() -> service.submit(maker, 900L), GeneralErrorCode.OFFCYCLE_MONTH_LOCKED);

        // An approval reached after the lock is refused too (and rolled back by the throw).
        saved[0].submitted(66L);
        when(cases.findByApprovalId(66L)).thenReturn(Optional.of(saved[0]));
        assertCode(
                () -> service.applyDecision(66L, true, "checker"),
                GeneralErrorCode.OFFCYCLE_MONTH_LOCKED);
        assertThat(saved[0].getExcludedFromYymm()).isNull();
    }

    @Test
    void aReturnedOrRejectedDecisionLeavesTheCaseAsItWas() {
        OffCyclePayment c =
                OffCyclePayment.capture(
                        MEMBER, null, "OTHER", "202609", "202610", BigDecimal.TEN, "ref");
        when(cases.findByApprovalId(66L)).thenReturn(Optional.of(c));
        service.applyDecision(66L, false, "checker");
        assertThat(c.getExcludedFromYymm()).isNull();
        verify(cases, never()).save(any());
        service.applyDecision(67L, true, "checker"); // not an I-20 approval: nothing to do
        when(cases.findByApprovalId(anyLong())).thenReturn(Optional.empty());
    }

    @Test
    void aSessionWithoutDataScopeSeesNoCase() {
        when(maker.dataScope()).thenReturn(Set.of());
        when(cases.findInScopeOfType(eq(Set.of(-1L)), isNull(), any(Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));
        assertThat(
                        service.list(maker, new in.gov.fci.payroll.platform.paging.PageSpec(0, 20))
                                .items())
                .isEmpty();
    }

    /**
     * SRS-G-10: a case is editable while never submitted or after it was returned; one in the
     * Checker's queue (pending approval), approved or rejected is not.
     */
    @Test
    void SRS_G_10_onlyANewOrReturnedCaseIsEditable() {
        OffCyclePayment c =
                OffCyclePayment.capture(
                        MEMBER, null, "OTHER", "202609", "202610", BigDecimal.TEN, "ref");
        ReflectionTestUtils.setField(c, "id", 902L);
        when(cases.findById(902L)).thenReturn(Optional.of(c));
        service.attachOrder(maker, 902L, "SANCTION_ORDER", "r", null, "a", "f", new byte[] {1});
        c.submitted(70L);
        for (ApprovalStatus closed :
                List.of(
                        ApprovalStatus.PENDING_APPROVAL,
                        ApprovalStatus.APPROVED,
                        ApprovalStatus.REJECTED)) {
            when(statuses.of(List.of(70L))).thenReturn(Map.of(70L, closed));
            assertCode(
                    () ->
                            service.attachOrder(
                                    maker, 902L, "SANCTION_ORDER", "r", null, "a", "f", null),
                    GeneralErrorCode.OFFCYCLE_STATUS);
        }
        when(statuses.of(List.of(70L))).thenReturn(Map.of(70L, ApprovalStatus.RETURNED));
        service.attachOrder(maker, 902L, "SANCTION_ORDER", "r", null, "a", "f", new byte[] {1});
        verify(documents, times(2)).attach(eq("maker.delhi@fci-demo.test"), any());
    }

    /**
     * SRS-G-10, BR-CYC-010: a member without an office on record has no cycle to be locked, so the
     * approval goes ahead and the month is excluded from the regular run.
     */
    @Test
    void SRS_G_10_aMemberWithoutAnOfficeHasNoLockedMonthAtApproval() {
        when(employees.locationOf(MEMBER)).thenReturn(Optional.empty());
        OffCyclePayment c =
                OffCyclePayment.capture(
                        MEMBER, null, "OTHER", "202609", "202610", BigDecimal.TEN, "ref");
        ReflectionTestUtils.setField(c, "id", 903L);
        c.submitted(71L);
        when(cases.findByApprovalId(71L)).thenReturn(Optional.of(c));
        service.applyDecision(71L, true, "checker");
        verify(org, never()).cycleUnitOf(any());
        verify(cases).save(c);
        assertThat(c.getExcludedFromYymm()).isNotNull();
    }

    @Test
    void salaries_theComputationIsUsedAsIs() {
        PendingSalary.Result r = Salaries.payable(pending, MEMBER, YearMonth.of(2026, 9));
        assertThat(r.net()).isEqualByComparingTo("10");
    }
}
