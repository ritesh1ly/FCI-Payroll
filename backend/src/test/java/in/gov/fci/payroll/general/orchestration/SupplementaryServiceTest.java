package in.gov.fci.payroll.general.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.general.api.SupplementaryPayments;
import in.gov.fci.payroll.general.business.GeneralErrorCode;
import in.gov.fci.payroll.general.business.StatutoryDeductions;
import in.gov.fci.payroll.general.business.model.SupplementaryPayment;
import in.gov.fci.payroll.general.persistence.GeneralRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.payroll.api.PendingSalary;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The I-23 checks of {@link SupplementaryService} that only the producer API reaches (pay codes,
 * separated members, the order) and the D-5 deduction gate. The screen flow is {@code
 * SupplementaryIT} / {@code SupplementaryApprovalIT}.
 */
class SupplementaryServiceTest {

    private static final Long MEMBER = 7L;
    private static final Long OFFICE = 40L;

    private final GeneralRepositories.SupplementaryPayments payments =
            mock(GeneralRepositories.SupplementaryPayments.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final PayrollRecords payroll = mock(PayrollRecords.class);
    private final PayConfiguration configuration = mock(PayConfiguration.class);
    private final StatutoryDeductions deductions = mock(StatutoryDeductions.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final AccessContext maker = mock(AccessContext.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private final AuditService audit = mock(AuditService.class);

    private final SupplementaryService service =
            new SupplementaryService(
                    mock(in.gov.fci.payroll.platform.export.ExportReader.class),
                    payments,
                    employees,
                    org,
                    payroll,
                    mock(PendingSalary.class),
                    configuration,
                    deductions,
                    documents,
                    approvals,
                    statuses,
                    mock(Authorization.class),
                    audit,
                    jdbc,
                    Clock.fixed(Instant.parse("2026-10-21T05:30:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void locked() {
        when(maker.loginId()).thenReturn("maker.delhi@fci-demo.test");
        member("IN_SERVICE");
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(OFFICE);
        when(org.cycleUnitOf(OFFICE)).thenReturn(Optional.of(unit));
        when(payroll.cycle(OFFICE, "202607"))
                .thenReturn(Optional.of(new PayrollRecords.Cycle(3L, OFFICE, "202607", "LOCKED")));
        when(documents.attach(anyString(), any()))
                .thenReturn(
                        new DocumentStore.AttachmentView(
                                11L, null, null, null, null, null, null, null, null, null, 0L, null,
                                null, null, null));
        when(payments.saveAndFlush(any()))
                .thenAnswer(
                        call -> {
                            SupplementaryPayment p = call.getArgument(0);
                            ReflectionTestUtils.setField(p, "id", 500L);
                            return p;
                        });
        when(payments.findMatching(anyLong(), anyString(), any(), anyString(), any()))
                .thenReturn(List.of());
        when(statuses.of(any())).thenReturn(Map.of());
    }

    private void member(String employmentStatus) {
        when(employees.member(MEMBER))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.Member(
                                        MEMBER, "106004", "Member", OFFICE, employmentStatus)));
        when(employees.locationOf(MEMBER)).thenReturn(Optional.of(OFFICE));
    }

    private static SupplementaryPayments.NewPayment payment(
            String type, Integer payCode, String amount, boolean withOrder) {
        return new SupplementaryPayments.NewPayment(
                MEMBER,
                "202607",
                type,
                payCode,
                new BigDecimal(amount),
                LocalDate.of(2026, 8, 10),
                null,
                null,
                withOrder
                        ? new SupplementaryPayments.Order(
                                "SANCTION_ORDER",
                                "R/1",
                                LocalDate.of(2026, 8, 12),
                                "AGM",
                                "o.pdf",
                                new byte[] {1})
                        : null);
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        FciException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }

    // SRS-14-3 (l.2964): pay codes 366, 396, 397 and 504 are never deducted on I-23; each attempt
    // is blocked before anything is stored and logged (BMAD TR-030).
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {366, 396, 397, 504})
    void SRS_14_3_anExcludedPayCodeIsBlockedBeforeAnythingIsStoredAndLogged(int code) {
        when(configuration.component(code))
                .thenReturn(
                        Optional.of(
                                new PayConfiguration.Component(9L, code, "EXCLUDED", true, false)));
        assertCode(
                () -> service.raise(maker, payment("RECOVERY", code, "-100", true)),
                GeneralErrorCode.SUPPLEMENTARY_EXCLUDED_CODE);
        verify(documents, never()).attach(anyString(), any());
        verify(payments, never()).saveAndFlush(any());
        ArgumentCaptor<AuditService.Entry> logged = ArgumentCaptor.captor();
        verify(audit).recordIndependently(eq("maker.delhi@fci-demo.test"), logged.capture());
        assertThat(logged.getValue().entityName()).isEqualTo("supplementary_payment");
        assertThat(logged.getValue().entityKey()).isEqualTo(String.valueOf(MEMBER));
        assertThat(logged.getValue().fieldName()).isEqualTo("pay_code");
        assertThat(logged.getValue().newValue()).isEqualTo(String.valueOf(code));
        assertThat(logged.getValue().moduleCode()).isEqualTo("I-23");
    }

    // A pay code that is not excluded is not logged as blocked.
    @Test
    void SRS_14_3_aPayCodeNotExcludedIsNotLoggedAsBlocked() {
        when(configuration.component(201))
                .thenReturn(
                        Optional.of(new PayConfiguration.Component(9L, 201, "OTA", false, false)));
        try {
            service.raise(maker, payment("RECOVERY", 201, "-100", true));
        } catch (FciException ignored) {
            // a later check may refuse it; only the exclusion log matters here
        }
        verify(audit, never()).recordIndependently(anyString(), any());
    }

    /**
     * SRS l.786 / BR-CYC-018 (US-GAP-113): a head corrected in bulk on I-19 (configured on the M-16
     * list MDP_PERMITTED_COMPONENT, in force on India's date) is refused on I-23 with the pointer
     * to I-19, before anything is stored; a head not on the list passes this check.
     */
    @Test
    void BR_CYC_018_aHeadCorrectedOnI19IsRefusedOnI23WithThePointer() {
        when(configuration.component(201))
                .thenReturn(
                        Optional.of(new PayConfiguration.Component(12L, 201, "PT", false, false)));
        java.sql.Date today = java.sql.Date.valueOf(LocalDate.of(2026, 10, 21));
        when(jdbc.queryForObject(
                        contains("MDP_PERMITTED_COMPONENT"),
                        eq(Integer.class),
                        eq("201"),
                        eq(today),
                        eq(today)))
                .thenReturn(1);
        assertThatThrownBy(() -> service.raise(maker, payment("RECOVERY", 201, "-100", true)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> {
                            assertThat(ex.getErrorCode())
                                    .isEqualTo(GeneralErrorCode.SUPPLEMENTARY_USE_I19);
                            assertThat(ex.getMessageArgs()).containsExactly(201);
                        });
        verify(documents, never()).attach(anyString(), any());
        verify(payments, never()).saveAndFlush(any());
    }

    @Test
    void anUnknownPayCodeOrTypeOrAMissingOrderIsRefused() {
        when(configuration.component(anyInt())).thenReturn(Optional.empty());
        assertCode(
                () -> service.raise(maker, payment("OTA", 999, "100", true)),
                GeneralErrorCode.SUPPLEMENTARY_INPUT);
        assertCode(
                () -> service.raise(maker, payment("BONUS", null, "100", true)),
                GeneralErrorCode.SUPPLEMENTARY_INPUT);
        assertCode(
                () -> service.raise(maker, payment("OTA", null, "100", false)),
                GeneralErrorCode.SUPPLEMENTARY_INPUT);
        when(employees.member(MEMBER)).thenReturn(Optional.empty());
        assertCode(
                () -> service.raise(maker, payment("OTA", null, "100", true)),
                CommonErrorCode.NOT_FOUND);
    }

    @Test
    void srsG14_aSeparatedMemberIsPaidOnlyWhatExistsBecauseTheyLeft() {
        member("SEPARATED");
        assertCode(
                () -> service.raise(maker, payment("OTA", null, "100", true)),
                GeneralErrorCode.SUPPLEMENTARY_INPUT);
        assertThat(service.raise(maker, payment("EMPLOYER_CPF_SEPARATED", null, "100", true)))
                .isEqualTo(500L);
    }

    // SRS-15-37: a supplementary payment only for a period already month-end locked.
    @Test
    void brCyc010_anOpenPeriodIsRefused() {
        when(payroll.cycle(OFFICE, "202607"))
                .thenReturn(
                        Optional.of(new PayrollRecords.Cycle(3L, OFFICE, "202607", "VERIFIED")));
        assertCode(
                () -> service.raise(maker, payment("OTA", null, "100", true)),
                GeneralErrorCode.SUPPLEMENTARY_NOT_LOCKED);
        when(payroll.cycle(OFFICE, "202607")).thenReturn(Optional.empty());
        assertCode(
                () -> service.raise(maker, payment("OTA", null, "100", true)),
                GeneralErrorCode.SUPPLEMENTARY_NOT_LOCKED);
    }

    @Test
    void chk_supp_locked_cycle_theOrderIsStoredFirstThenAdoptedByTheRow() {
        when(configuration.component(140))
                .thenReturn(
                        Optional.of(
                                new PayConfiguration.Component(
                                        14L, 140, "Night Shift", false, false)));
        Long id = service.raise(maker, payment("OTA", 140, "100", true));
        assertThat(id).isEqualTo(500L);
        verify(documents)
                .adopt(
                        eq("maker.delhi@fci-demo.test"),
                        eq("supplementary_payment"),
                        startsWith(DocumentStore.PROVISIONAL),
                        eq(11L),
                        eq("500"));
    }

    @Test
    void d5_aRecoverySkipsTheDeductionsEveryOtherTypeNeedsThem() {
        SupplementaryPayment recovery =
                SupplementaryPayment.record(
                        MEMBER,
                        3L,
                        "202607",
                        "RECOVERY",
                        null,
                        new BigDecimal("-100"),
                        LocalDate.of(2026, 8, 10),
                        null,
                        null,
                        11L,
                        false,
                        false);
        ReflectionTestUtils.setField(recovery, "id", 501L);
        when(payments.findById(501L)).thenReturn(Optional.of(recovery));
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(61L, ApprovalStatus.PENDING_APPROVAL, null));
        assertThat(service.submit(maker, 501L).status()).isEqualTo("PENDING_CHECKER");
        verify(deductions, never()).due(any());

        SupplementaryPayment ota =
                SupplementaryPayment.record(
                        MEMBER,
                        3L,
                        "202607",
                        "OTA",
                        null,
                        new BigDecimal("100"),
                        LocalDate.of(2026, 8, 10),
                        null,
                        null,
                        11L,
                        true,
                        false);
        ReflectionTestUtils.setField(ota, "id", 502L);
        when(payments.findById(502L)).thenReturn(Optional.of(ota));
        when(deductions.due(any())).thenReturn(Optional.empty());
        assertCode(() -> service.submit(maker, 502L), GeneralErrorCode.SUPPLEMENTARY_STATUTORY);
    }

    @Test
    void theScreenStatusAddsReturnedAndBlocked() {
        SupplementaryPayment p =
                SupplementaryPayment.record(
                        MEMBER,
                        3L,
                        "202607",
                        "OTA",
                        null,
                        BigDecimal.TEN,
                        LocalDate.of(2026, 8, 10),
                        null,
                        null,
                        11L,
                        true,
                        false);
        assertThat(SupplementaryService.derived(p, null)).isEqualTo("DRAFT");
        assertThat(SupplementaryService.derived(p, ApprovalStatus.RETURNED)).isEqualTo("RETURNED");
        p.duplicateChecked(true, null);
        assertThat(SupplementaryService.derived(p, ApprovalStatus.RETURNED)).isEqualTo("BLOCKED");
        p.duplicateChecked(false, null);
        p.submit(1L);
        assertThat(SupplementaryService.derived(p, null)).isEqualTo("PENDING_CHECKER");
        p.approve(2L);
        assertThat(SupplementaryService.derived(p, null)).isEqualTo("APPROVED");
    }

    /**
     * I-23 "Order copy - attach & store": the orders of a request in scope are listed; a request of
     * a member no longer in the directory shows its pay code but no member number or name.
     */
    @Test
    void SRS_G_13_theOrdersAreListedAndAnUnknownMemberShowsNoNumber() {
        SupplementaryPayment p = mock(SupplementaryPayment.class);
        when(p.getId()).thenReturn(501L);
        when(p.getEmployeeId()).thenReturn(99L);
        when(p.getComponentId()).thenReturn(3L);
        when(p.getStatus()).thenReturn(SupplementaryPayment.Status.RECORDED);
        when(p.getDuplicateCheck()).thenReturn(SupplementaryPayment.DuplicateCheck.PASSED);
        when(payments.findById(501L)).thenReturn(Optional.of(p));
        DocumentStore.AttachmentView order = mock(DocumentStore.AttachmentView.class);
        when(documents.active(SupplementaryService.ENTITY, "501")).thenReturn(List.of(order));
        assertThat(service.orders(maker, 501L)).containsExactly(order);

        PayConfiguration.Component component = mock(PayConfiguration.Component.class);
        when(component.legacyPayCode()).thenReturn(401);
        when(configuration.componentById(3L)).thenReturn(Optional.of(component));
        SupplementaryService.PaymentView view = service.get(maker, 501L);
        assertThat(view.payCode()).isEqualTo(401);
        assertThat(view.eis()).isNull();
        assertThat(view.memberName()).isNull();
        assertThat(view.reference()).isEqualTo("SUP-00000501");
    }

    /**
     * BR-CYC-018 (US-GAP-113): a head not on the I-19 list (a count of 0) passes; the M-07
     * component's taxability is carried onto the request (a taxable head stays taxable).
     */
    @Test
    void BR_CYC_018_aHeadNotOnTheI19ListPassesAndKeepsItsTaxability() {
        when(configuration.component(150))
                .thenReturn(
                        Optional.of(new PayConfiguration.Component(15L, 150, "OTA", false, true)));
        when(jdbc.queryForObject(
                        contains("MDP_PERMITTED_COMPONENT"),
                        eq(Integer.class),
                        eq("150"),
                        any(),
                        any()))
                .thenReturn(0);
        assertThat(service.raise(maker, payment("OTA", 150, "100", true))).isEqualTo(500L);
        ArgumentCaptor<SupplementaryPayment> saved = ArgumentCaptor.captor();
        verify(payments).saveAndFlush(saved.capture());
        assertThat(saved.getValue().isTaxable()).isTrue();
        assertThat(saved.getValue().getComponentId()).isEqualTo(15L);
    }

    /**
     * BR-CYC-010: a salary already paid in the regular run, or covered by an active I-20 case,
     * fails the duplicate check (no I-23 match named); a salary paid by neither goes on to the I-23
     * match and passes.
     */
    @Test
    void BR_CYC_010_aSalaryPaidInTheRegularRunOrOffCycleFailsTheDuplicateCheck() {
        ArgumentCaptor<SupplementaryPayment> saved = ArgumentCaptor.captor();

        when(payroll.paidInRegularRun(MEMBER, "202607")).thenReturn(true);
        service.raise(maker, payment("SALARY_WAGE", null, "100", true));

        when(payroll.paidInRegularRun(MEMBER, "202607")).thenReturn(false);
        when(jdbc.queryForObject(
                        contains("FROM off_cycle_payment"), eq(Integer.class), any(), any()))
                .thenReturn(1);
        service.raise(maker, payment("SALARY_ARREAR", null, "100", true));

        when(jdbc.queryForObject(
                        contains("FROM off_cycle_payment"), eq(Integer.class), any(), any()))
                .thenReturn(0);
        service.raise(maker, payment("SALARY_WAGE", null, "100", true));

        // No count read at all (no row): no I-20 case covers the month either.
        when(jdbc.queryForObject(
                        contains("FROM off_cycle_payment"), eq(Integer.class), any(), any()))
                .thenReturn(null);
        service.raise(maker, payment("SALARY_ARREAR", null, "100", true));

        verify(payments, times(4)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(SupplementaryPayment::getDuplicateCheck)
                .containsExactly(
                        SupplementaryPayment.DuplicateCheck.BLOCKED,
                        SupplementaryPayment.DuplicateCheck.BLOCKED,
                        SupplementaryPayment.DuplicateCheck.PASSED,
                        SupplementaryPayment.DuplicateCheck.PASSED);
        assertThat(saved.getAllValues())
                .extracting(SupplementaryPayment::getDuplicateOfPaymentId)
                .containsOnlyNulls();
    }
}
