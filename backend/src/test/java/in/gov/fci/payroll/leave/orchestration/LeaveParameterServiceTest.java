package in.gov.fci.payroll.leave.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.leave.business.LeaveErrorCode;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;

/**
 * M-20 leave pay parameters (plan 19; SRS-LV-14; D19-2, D19-3, D19-10): Headquarters proposes; the
 * effective date is required; pay codes must be active M-07 components; one version waits at a
 * time; a new version starts after every approved one, on a date no other version holds; the
 * register shows each version's status.
 */
class LeaveParameterServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final in.gov.fci.payroll.configuration.api.Circulars circulars =
            mock(in.gov.fci.payroll.configuration.api.Circulars.class);
    private final LeaveParameterService service =
            new LeaveParameterService(jdbc, policy, approvals, statuses, circulars);

    private final AccessContext hq =
            new AccessContext(
                    1L, "ra@x", "R", "s", "P", 1L, null, List.of(), Map.of(), Set.of(), Set.of(),
                    Set.of());

    private static LeaveParameterService.NewVersion codes(String text, LocalDate from) {
        return new LeaveParameterService.NewVersion(
                "ENCASH_PAY_ELEMENTS", " ", null, text, from, " Order 7 ");
    }

    private ErrorCode refusal(LeaveParameterService.NewVersion v) {
        return ((FciException) catchThrowable(() -> service.propose(hq, v))).getErrorCode();
    }

    private static Map<String, Object> existing(LocalDate from, Long approval, int no) {
        Map<String, Object> m = new HashMap<>();
        m.put("EFFECTIVE_FROM", Date.valueOf(from));
        m.put("APPROVAL_ID", approval);
        m.put("VERSION_NO", no);
        return m;
    }

    private void active(long count) {
        when(jdbc.queryForObject(
                        contains("FROM pay_component"), eq(Long.class), any(Object[].class)))
                .thenReturn(count);
    }

    @Test
    void SRS_LV_14_onlyHeadquartersProposesWithAnEffectiveDate() {
        assertThat(refusal(codes("101+111", FROM))).isEqualTo(LeaveErrorCode.HQ_ONLY);
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        assertThat(refusal(codes("101+111", null))).isEqualTo(LeaveErrorCode.PARAMETER);
        assertThat(
                        refusal(
                                new LeaveParameterService.NewVersion(
                                        "NO_SUCH", null, null, "x", FROM, "r")))
                .isEqualTo(LeaveErrorCode.PARAMETER);
    }

    @Test
    void D19_2_payCodesMustBeActiveComponentsOfTheCatalogue() {
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        active(0);
        assertThat(refusal(codes(" 101+111 ", FROM))).isEqualTo(LeaveErrorCode.PARAMETER);
    }

    @Test
    void D19_10_oneVersionWaitsAtATimeAndANewOneStartsAfterTheLatestApprovedOnAFreeDate() {
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        active(1);
        when(jdbc.queryForList(
                        contains("FROM leave_pay_parameter WHERE parameter_code"),
                        eq("ENCASH_PAY_ELEMENTS"),
                        isNull(),
                        isNull()))
                .thenReturn(
                        List.of(
                                existing(FROM.minusMonths(6), 11L, 1),
                                existing(FROM.minusMonths(1), 12L, 2),
                                existing(FROM.minusMonths(3), null, 3)));
        when(statuses.of(anyList()))
                .thenReturn(
                        Map.of(11L, ApprovalStatus.APPROVED, 12L, ApprovalStatus.PENDING_APPROVAL));
        assertThat(refusal(codes("101", FROM)))
                .as("one waits")
                .isEqualTo(LeaveErrorCode.PARAMETER_PENDING);

        when(statuses.of(anyList()))
                .thenReturn(Map.of(11L, ApprovalStatus.APPROVED, 12L, ApprovalStatus.APPROVED));
        assertThat(refusal(codes("101", FROM.minusMonths(2))))
                .as("before the latest approved")
                .isEqualTo(LeaveErrorCode.PARAMETER_DATE);
        assertThat(refusal(codes("101", FROM.minusMonths(1))))
                .as("on its date")
                .isEqualTo(LeaveErrorCode.PARAMETER_DATE);
        assertThat(refusal(codes("101", FROM.minusMonths(3))))
                .as("a date another version holds")
                .isEqualTo(LeaveErrorCode.PARAMETER_DATE);

        when(statuses.of(anyList()))
                .thenReturn(Map.of(11L, ApprovalStatus.REJECTED, 12L, ApprovalStatus.APPROVED));
        SubmittedApproval item = mock(SubmittedApproval.class);
        when(item.id()).thenReturn(40L);
        when(approvals.submit(any(), any(), any())).thenReturn(item);
        doAnswer(
                        call -> {
                            KeyHolder k = call.getArgument(1);
                            k.getKeyList().add(Map.of("leave_pay_parameter_id", 9L));
                            return 1;
                        })
                .when(jdbc)
                .update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        LeaveParameterService.Version v = service.propose(hq, codes("101+111", FROM));
        assertThat(v.versionNo()).isEqualTo(4);
        assertThat(v.leaveType()).isNull();
        assertThat(v.status()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void SRS_LV_14_aLeaveTypeParameterIsKeyedPerTypeAndNamedWithIt() {
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        when(jdbc.queryForObject(
                        contains("FROM leave_type_reference"), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);
        when(jdbc.queryForList(
                        contains("FROM leave_pay_parameter WHERE parameter_code"),
                        eq("PAY_EFFECT"),
                        eq("LWP"),
                        eq("LWP")))
                .thenReturn(List.of());
        when(statuses.of(anyList())).thenReturn(Map.of());
        SubmittedApproval item = mock(SubmittedApproval.class);
        when(approvals.submit(any(), any(), any())).thenReturn(item);
        doAnswer(
                        call -> {
                            ((KeyHolder) call.getArgument(1)).getKeyList().add(Map.of("id", 3L));
                            return 1;
                        })
                .when(jdbc)
                .update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        LeaveParameterService.Version v =
                service.propose(
                        hq,
                        new LeaveParameterService.NewVersion(
                                "PAY_EFFECT", " LWP ", null, "FULL_DEDUCTION", FROM, "Order"));
        assertThat(v.leaveType()).isEqualTo("LWP");
        assertThat(v.versionNo()).isEqualTo(1);
    }

    @Test
    void SRS_LV_14_theRegisterShowsActiveSupersededPendingAndDraftVersions() {
        when(jdbc.queryForObject(
                        contains("SELECT COUNT(*) FROM leave_pay_parameter"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(4L);
        Map<String, Object> active = new HashMap<>(row(1L, "Y", null, null));
        Map<String, Object> superseded = row(2L, "Y", FROM, 21L);
        Map<String, Object> pending = row(3L, "N", null, 22L);
        Map<String, Object> draft = row(4L, "N", null, null);
        when(jdbc.queryForList(contains("ORDER BY p.parameter_code"), any(Object[].class)))
                .thenReturn(List.of(active, superseded, pending, draft));
        when(statuses.of(anyList())).thenReturn(Map.of(22L, ApprovalStatus.PENDING_APPROVAL));
        var page = service.versions(hq, " LWP_DIVISOR ", new PageSpec(0, 20));
        assertThat(page.items())
                .extracting(LeaveParameterService.Version::status)
                .containsExactly("ACTIVE", "SUPERSEDED", "PENDING_APPROVAL", "DRAFT");
        assertThat(service.versions(hq, " ", new PageSpec(0, 20)).total()).isEqualTo(4L);
        assertThat(LeaveParameterService.toLocalDate(null)).isNull();
        active.put("CIRCULAR_NUMBER", "HQ/LV/2026/1");
        assertThat(
                        service.versions(hq, null, new PageSpec(0, 20))
                                .items()
                                .getFirst()
                                .circularNumber())
                .isEqualTo("HQ/LV/2026/1");
    }

    @Test
    void SRS_LV_14_aVersionNamesAnActiveCircularWhereAnOrderApplies() {
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        org.mockito.Mockito.doThrow(
                        new FciException(
                                in.gov.fci.payroll.platform.error.CommonErrorCode.NOT_FOUND))
                .when(circulars)
                .requireActive(9L);
        assertThat(
                        refusal(
                                new LeaveParameterService.NewVersion(
                                        "LWP_DIVISOR", null, BigDecimal.TEN, null, FROM, "r", 9L)))
                .isEqualTo(in.gov.fci.payroll.platform.error.CommonErrorCode.NOT_FOUND);
        org.mockito.Mockito.verifyNoInteractions(approvals);
    }

    private static Map<String, Object> row(long id, String confirmed, LocalDate to, Long approval) {
        Map<String, Object> m = new HashMap<>();
        m.put("LEAVE_PAY_PARAMETER_ID", id);
        m.put("PARAMETER_CODE", "LWP_DIVISOR");
        m.put("VERSION_NO", (int) id);
        m.put("EFFECTIVE_FROM", Date.valueOf(FROM.minusYears(1)));
        m.put("EFFECTIVE_TO", to == null ? null : Date.valueOf(to));
        m.put("IS_CONFIRMED", confirmed);
        m.put("APPROVAL_ID", approval);
        m.put("NUMERIC_VALUE", BigDecimal.TEN);
        return m;
    }

    /**
     * SRS-LV-14 (branch gap): a numeric parameter without a text (absent or blank) is valid; an
     * older approved version listed after a newer one never moves the latest approved date back.
     */
    @Test
    void D19_10_aNumericVersionWithoutTextStartsAfterTheNewestApprovedWhateverTheOrder() {
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        assertThat(
                        refusal(
                                new LeaveParameterService.NewVersion(
                                        "ENCASH_DIVISOR",
                                        null,
                                        BigDecimal.TEN,
                                        null,
                                        null,
                                        "Order")))
                .as("no text, no date")
                .isEqualTo(LeaveErrorCode.PARAMETER);
        when(jdbc.queryForList(
                        contains("FROM leave_pay_parameter WHERE parameter_code"),
                        eq("ENCASH_DIVISOR"),
                        isNull(),
                        isNull()))
                .thenReturn(
                        List.of(
                                existing(FROM.minusMonths(1), 12L, 2),
                                existing(FROM.minusMonths(6), 11L, 1)));
        when(statuses.of(anyList()))
                .thenReturn(Map.of(11L, ApprovalStatus.APPROVED, 12L, ApprovalStatus.APPROVED));
        FciException early =
                (FciException)
                        catchThrowable(
                                () ->
                                        service.propose(
                                                hq,
                                                new LeaveParameterService.NewVersion(
                                                        "ENCASH_DIVISOR",
                                                        null,
                                                        BigDecimal.TEN,
                                                        " ",
                                                        FROM.minusMonths(2),
                                                        "Order")));
        assertThat(early.getErrorCode()).isEqualTo(LeaveErrorCode.PARAMETER_DATE);
        assertThat(early.getMessageArgs()[1]).isEqualTo(FROM.minusMonths(1).toString());
        SubmittedApproval item = mock(SubmittedApproval.class);
        when(item.id()).thenReturn(41L);
        when(approvals.submit(any(), any(), any())).thenReturn(item);
        doAnswer(
                        call -> {
                            ((KeyHolder) call.getArgument(1)).getKeyList().add(Map.of("id", 8L));
                            return 1;
                        })
                .when(jdbc)
                .update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        LeaveParameterService.Version v =
                service.propose(
                        hq,
                        new LeaveParameterService.NewVersion(
                                "ENCASH_DIVISOR", null, BigDecimal.TEN, " ", FROM, "Order"));
        assertThat(v.text()).isNull();
        assertThat(v.versionNo()).isEqualTo(3);
    }
}
