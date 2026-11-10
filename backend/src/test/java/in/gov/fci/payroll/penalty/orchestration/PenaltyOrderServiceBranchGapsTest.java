package in.gov.fci.payroll.penalty.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.NetPayFloors;
import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.penalty.business.PenaltyErrorCode;
import in.gov.fci.payroll.penalty.orchestration.PenaltyOrderService.Copy;
import in.gov.fci.payroll.penalty.orchestration.PenaltyOrderService.Request;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * I-06 edge cases (SRS-14-1, SRS-14-7, SRS-14-10, SRS-14-12, SRS-20-27): a submission without its
 * copy is audited as blocked even when the order or its member is missing (key "-"); without an
 * M-14 floor in force a recovery that leaves no net pay is below the floor; the Checker's request
 * says when the ceiling lengthened the instalments.
 */
class PenaltyOrderServiceBranchGapsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final RuleEngine rules = mock(RuleEngine.class);
    private final NetPayFloors floors = mock(NetPayFloors.class);
    private final ReferenceData references = mock(ReferenceData.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccessContext context = mock(AccessContext.class);
    private final PenaltyOrderService service =
            new PenaltyOrderService(
                    jdbc,
                    employees,
                    org,
                    mock(PayrollRecords.class),
                    rules,
                    floors,
                    references,
                    mock(Authorization.class),
                    approvals,
                    documents,
                    JsonMapper.builder().build(),
                    Clock.fixed(Instant.parse("2026-04-10T05:00:00Z"), ZoneOffset.UTC),
                    audit);

    PenaltyOrderServiceBranchGapsTest() {
        when(context.loginId()).thenReturn("penalty.maker@x");
        when(employees.memberByEis("E1"))
                .thenReturn(
                        Optional.of(new EmployeeDirectory.Member(2L, "E1", "B", 5L, "IN_SERVICE")));
        when(references.contains(PenaltyOrderService.NATURES, "RECOVERY")).thenReturn(true);
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(3L);
        when(org.cycleUnitOf(5L)).thenReturn(Optional.of(unit));
        when(jdbc.queryForObject(
                        contains("SELECT current_basic"),
                        eq(BigDecimal.class),
                        any(Object[].class)))
                .thenReturn(new BigDecimal("50000"));
        when(jdbc.queryForList(
                        contains("PENALTY_RECOVERY"), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(List.of(new BigDecimal("401")));
        when(rules.resolve(eq("LOAN.DEDUCTION.CEILING"), any(), any()))
                .thenReturn(
                        Optional.of(
                                new RuleEngine.Resolved(
                                        "LOAN.DEDUCTION.CEILING",
                                        "RATE",
                                        1L,
                                        1,
                                        LocalDate.of(2026, 1, 1),
                                        null,
                                        new BigDecimal("50"),
                                        null,
                                        null,
                                        null,
                                        false)));
    }

    private void pay(String gross, String deductions, String net) {
        when(jdbc.queryForList(contains("FROM payroll_result"), any(Object[].class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "GROSS_EARNINGS", new BigDecimal(gross),
                                        "TOTAL_DEDUCTIONS", new BigDecimal(deductions),
                                        "NET_PAY", new BigDecimal(net))));
    }

    private static Request request(String amount, int instalments) {
        return new Request(
                "E1",
                "RECOVERY",
                "O-1",
                LocalDate.of(2026, 4, 1),
                new BigDecimal(amount),
                instalments,
                "202604");
    }

    @Test
    void SRS_20_27_aSubmissionWithoutItsCopyIsAuditedAsBlockedEvenWithoutTheOrderOrItsMember() {
        for (Request r :
                new Request[] {
                    null,
                    new Request(
                            null,
                            "RECOVERY",
                            "O-1",
                            LocalDate.of(2026, 4, 1),
                            BigDecimal.TEN,
                            1,
                            "202604")
                }) {
            assertThatThrownBy(() -> service.submit(context, r, null))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            ex ->
                                    assertThat(ex.getErrorCode())
                                            .isEqualTo(PenaltyErrorCode.ORDER_COPY));
        }
        verify(audit, times(2))
                .submissionBlocked(
                        eq("penalty.maker@x"),
                        eq(PenaltyOrderService.ENTITY),
                        eq("-"),
                        anyString(),
                        eq("order copy"));
    }

    @Test
    void SRS_14_10_withoutAFloorInForceARecoveryLeavingNoNetPayIsBelowTheFloor() {
        pay("60000", "10000", "1000");
        when(floors.inForce(eq("STAFF"), any())).thenReturn(Optional.empty());
        PenaltyOrderService.Preview nothingLeft = service.preview(context, request("1000", 1));
        assertThat(nothingLeft.floor()).isNull();
        assertThat(nothingLeft.netAfter()).isEqualByComparingTo("0");
        assertThat(nothingLeft.belowFloor()).isTrue();
        PenaltyOrderService.Preview negative = service.preview(context, request("1200", 1));
        assertThat(negative.belowFloor()).isTrue();
        PenaltyOrderService.Preview positive = service.preview(context, request("999", 1));
        assertThat(positive.belowFloor()).isFalse();
    }

    @Test
    void SRS_14_7_theCheckersRequestSaysWhenTheCeilingLengthenedTheInstalments() {
        pay("60000", "10000", "50000");
        when(floors.inForce(eq("STAFF"), any())).thenReturn(Optional.empty());
        when(jdbc.queryForObject(contains("penalty_ref_seq"), eq(Long.class))).thenReturn(12L);
        DocumentStore.AttachmentView attached =
                new DocumentStore.AttachmentView(
                        77L,
                        PenaltyOrderService.ENTITY,
                        "PROVISIONAL",
                        "ORDER",
                        "Order",
                        "O-1",
                        LocalDate.of(2026, 4, 1),
                        "DGM",
                        "o.pdf",
                        "application/pdf",
                        3L,
                        "abc",
                        "ACTIVE",
                        "penalty.maker@x",
                        Instant.parse("2026-04-10T05:00:00Z"));
        when(documents.attach(anyString(), any())).thenReturn(attached);
        when(approvals.submit(any(), any(), isNull()))
                .thenReturn(new SubmittedApproval(9L, ApprovalStatus.PENDING_APPROVAL, null));
        Copy copy =
                new Copy(
                        "ORDER",
                        "O-1",
                        LocalDate.of(2026, 4, 1),
                        "DGM",
                        "o.pdf",
                        new byte[] {1, 2, 3});
        service.submit(context, request("60000", 1), copy);
        ArgumentCaptor<Submission> submitted = ArgumentCaptor.captor();
        verify(approvals).submit(eq(context), submitted.capture(), isNull());
        assertThat(submitted.getValue().reason())
                .startsWith("PEN-2026-00012: RECOVERY O-1 of 60000")
                .contains(" (lengthened for the ceiling)");
        assertThat(submitted.getValue().actionCode()).isEqualTo("CREATE");
    }

    /** SRS-14-10: the ceiling and the floor are tested on computed pay; none yet is refused. */
    @Test
    void SRS_14_10_aMemberWithNoComputedPayYetCannotHaveARecoveryTested() {
        when(jdbc.queryForList(contains("FROM payroll_result"), any(Object[].class)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.preview(context, request("1000", 1)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> {
                            assertThat(ex.getErrorCode()).isEqualTo(PenaltyErrorCode.ORDER);
                            assertThat(ex.getMessageArgs())
                                    .containsExactly(
                                            "the member has no computed pay yet to test the"
                                                    + " recovery on");
                        });
    }

    /** SRS-14-1: a nature with no M-16 recovery head in force is not configured; no pay is read. */
    @Test
    void SRS_14_1_aRecoveryWithoutAConfiguredHeadIsNotConfiguredAndReadsNoPay() {
        when(jdbc.queryForList(
                        contains("PENALTY_RECOVERY"), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.preview(context, request("1000", 1)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(PenaltyErrorCode.NOT_CONFIGURED));
        verify(jdbc, never()).queryForList(contains("FROM payroll_result"), any(Object[].class));
    }
}
