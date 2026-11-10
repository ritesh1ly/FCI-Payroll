package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.employee.business.FlagRules;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * The M-01 allowance flag proposal's refusals (plan 16 phase 16d-2; SRS-1-11): an unknown employee
 * (none named), no flag code, and a version of the same flag already from that date; a version of
 * another flag on that date does not block.
 */
class EmployeeFlagServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);

    private static final EmployeeFlagService.Document DOC =
            new EmployeeFlagService.Document(
                    "OTHER", null, FROM, "Establishment", "order.pdf", new byte[] {1});

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final in.gov.fci.payroll.document.api.DocumentStore documents =
            mock(in.gov.fci.payroll.document.api.DocumentStore.class);
    private final EmployeeFlagService service =
            new EmployeeFlagService(
                    jdbc,
                    mock(Authorization.class),
                    approvals,
                    documents,
                    new ObjectMapper(),
                    Clock.fixed(Instant.parse("2026-10-26T06:00:00Z"), ZoneOffset.UTC));

    private static AccessContext hr() {
        return new AccessContext(
                4L, "hr@x", "HR", "s", "P", 300L, null, List.of(), Map.of(), Set.of(), Set.of(),
                Set.of());
    }

    @SuppressWarnings("unchecked")
    EmployeeFlagServiceTest() throws Exception {
        when(jdbc.queryForList(anyString(), eq(""))).thenReturn(List.of());
        when(jdbc.queryForList(anyString(), eq("100041")))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "EMPLOYEE_ID", 7L,
                                        "EMP_NUM", "100041",
                                        "LOCATION_ID", 300L)));
        ResultSet cca = mock(ResultSet.class);
        when(cca.getString(1)).thenReturn("CCA");
        when(cca.getString(2)).thenReturn("City Compensatory Allowance");
        doAnswer(
                        i -> {
                            ((RowCallbackHandler) i.getArgument(1)).processRow(cca);
                            return null;
                        })
                .when(jdbc)
                .query(
                        startsWith("SELECT v.value_code"),
                        any(RowCallbackHandler.class),
                        any(Object[].class));
        when(jdbc.queryForList(anyString(), eq(String.class), any(), any())).thenReturn(List.of());
        when(jdbc.query(startsWith("SELECT flag_code"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(
                        List.of(
                                new FlagRules.Version("NPA", "Y", FROM, null),
                                new FlagRules.Version("CCA", "Y", FROM, null)));
    }

    // SC-16-0141: the other flags (special and personal pay) are flags of the same catalogue, dated
    // and approved.
    @Test
    void SRS_1_11_anUnknownEmployeeOrFlagOrASameDateVersionIsRefused() {
        EmployeeFlagService.Proposal cca =
                new EmployeeFlagService.Proposal("CCA", "N", FROM, "Order 12");
        assertThatThrownBy(() -> service.propose(hr(), null, cca, DOC))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
        assertThatThrownBy(
                        () ->
                                service.propose(
                                        hr(),
                                        "100041",
                                        new EmployeeFlagService.Proposal(
                                                null, "N", FROM, "Order 12"),
                                        DOC))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("UNKNOWN_FLAG"));
        assertThatThrownBy(() -> service.propose(hr(), " 100041 ", cca, DOC))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.M01_FLAG);
                            assertThat(e.getMessageArgs()).containsExactly("SAME_DATE");
                        });
    }

    // US-GAP-39: an end date before the effective date is refused before anything is stored.
    @Test
    void BR_EMPM_004_anEndDateBeforeTheEffectiveDateIsRefused() {
        assertThatThrownBy(
                        () ->
                                service.propose(
                                        hr(),
                                        "100041",
                                        new EmployeeFlagService.Proposal(
                                                "CCA",
                                                "N",
                                                FROM.plusMonths(1),
                                                FROM.plusMonths(1).minusDays(1),
                                                "Order 14"),
                                        DOC))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("END_BEFORE_START"));
    }

    @Test
    void SRS_1_26_aProposalWithoutItsSupportingDocumentIsRefused() {
        EmployeeFlagService.Proposal change =
                new EmployeeFlagService.Proposal("CCA", "N", FROM.plusMonths(1), "Order 13");
        for (EmployeeFlagService.Document missing :
                new EmployeeFlagService.Document[] {
                    null,
                    new EmployeeFlagService.Document("OTHER", null, FROM, "E", "a.pdf", null),
                    new EmployeeFlagService.Document("OTHER", null, FROM, "E", "a.pdf", new byte[0])
                }) {
            assertThatThrownBy(() -> service.propose(hr(), "100041", change, missing))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e -> {
                                assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.M01_FLAG);
                                assertThat(e.getMessageArgs()).containsExactly("DOCUMENT_MISSING");
                            });
        }
    }

    private void storesTheOrder() {
        when(documents.attach(eq("hr@x"), any()))
                .thenAnswer(
                        i ->
                                new in.gov.fci.payroll.document.api.DocumentStore.AttachmentView(
                                        55L,
                                        EmployeeFlagService.ENTITY,
                                        ((in.gov.fci.payroll.document.api.DocumentStore
                                                                .NewAttachment)
                                                        i.getArgument(1))
                                                .entityKey(),
                                        "OTHER",
                                        "Other document",
                                        null,
                                        FROM,
                                        "Establishment",
                                        "order.pdf",
                                        "application/pdf",
                                        1L,
                                        "abc",
                                        "ACTIVE",
                                        "hr@x",
                                        Instant.EPOCH));
        when(approvals.submit(any(), any(Submission.class), (OtpProof) eq(null)))
                .thenReturn(new SubmittedApproval(77L, ApprovalStatus.PENDING_APPROVAL, null));
    }

    // US-GAP-39: a flag set for a period names its end date in the reason the Checker reads.
    @Test
    void BR_EMPM_004_aFlagSetForAPeriodGoesToTheCheckerWithItsEndDate() {
        storesTheOrder();
        service.propose(
                hr(),
                "100041",
                new EmployeeFlagService.Proposal(
                        "CCA", "N", FROM.plusMonths(1), FROM.plusMonths(3), " Order 15 "),
                DOC);
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(any(), sent.capture(), (OtpProof) eq(null));
        assertThat(sent.getValue().reason())
                .isEqualTo("Allowance flag CCA Y -> N from 2026-11-01 to 2027-01-01: Order 15");
        assertThat(sent.getValue().payload()).contains("\"effectiveTo\":\"2027-01-01\"");
        assertThat(sent.getValue().locationId()).isEqualTo(300L);
    }

    @Test
    void SRS_1_11_anOpenEndedFlagGoesToTheCheckerWithoutAnEndDate() {
        storesTheOrder();
        service.propose(
                hr(),
                "100041",
                new EmployeeFlagService.Proposal("CCA", "N", FROM.plusMonths(1), "Order 13"),
                DOC);
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(any(), sent.capture(), (OtpProof) eq(null));
        assertThat(sent.getValue().reason())
                .isEqualTo("Allowance flag CCA Y -> N from 2026-11-01: Order 13");
        assertThat(sent.getValue().payload()).contains("\"effectiveTo\":\"\"");
    }
}
