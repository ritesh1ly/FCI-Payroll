package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.configuration.orchestration.ReferenceListService.Change;
import in.gov.fci.payroll.configuration.orchestration.ReferenceListService.ListHead;
import in.gov.fci.payroll.configuration.orchestration.ReferenceListService.PendingChange;
import in.gov.fci.payroll.configuration.orchestration.ReferenceListService.Proposal;
import in.gov.fci.payroll.configuration.orchestration.ReferenceListService.ValueState;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-16 Reference &amp; Combo List Administration (SRS-16-15: the Administrator at Headquarters
 * maintains the lookup values; a change takes effect only after the Checker approves). The checks a
 * proposal passes before it reaches the checker, and what it carries.
 */
class ReferenceListServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
    private static final String LIST = "LEAVE_REASON";
    private static final long LIST_ID = 3L;
    private static final long VALUE_ID = 11L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ReferenceData reference = mock(ReferenceData.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final Authorization policy = mock(Authorization.class);
    private final JsonMapper json = JsonMapper.builder().build();

    private final ReferenceListService service =
            new ReferenceListService(
                    jdbc,
                    reference,
                    approvals,
                    statuses,
                    policy,
                    json,
                    Clock.fixed(Instant.parse("2026-10-23T06:00:00Z"), ZoneOffset.UTC));

    private final AccessContext admin =
            new AccessContext(
                    4L,
                    "sysadmin.hq@x",
                    "Administrator",
                    "s",
                    "P",
                    1L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(1L));

    /** The list exists (an open list unless {@code system}); nothing is pending; HQ; today. */
    private void listExists(boolean system) {
        when(policy.isAtHeadquarters(admin)).thenReturn(true);
        when(reference.today()).thenReturn(TODAY);
        when(jdbc.query(
                        startsWith("SELECT list_id"),
                        ArgumentMatchers.<RowMapper<ListHead>>any(),
                        eq(LIST)))
                .thenReturn(List.of(new ListHead(LIST_ID, system)));
        when(jdbc.query(
                        startsWith("SELECT value_id"),
                        ArgumentMatchers.<RowMapper<ValueState>>any(),
                        eq(LIST_ID),
                        anyString()))
                .thenReturn(List.of());
        when(statuses.pending(ReferenceListService.ENTITY)).thenReturn(List.of());
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(77L, ApprovalStatus.PENDING_APPROVAL, null));
    }

    /** The value {@code code} exists with these English label, order and dates. */
    private void valueExists(String code, Integer order, LocalDate from, LocalDate to, String hi) {
        when(jdbc.query(
                        startsWith("SELECT value_id"),
                        ArgumentMatchers.<RowMapper<ValueState>>any(),
                        eq(LIST_ID),
                        eq(code)))
                .thenReturn(List.of(new ValueState(VALUE_ID, "Medical", order, from, to)));
        when(jdbc.query(
                        startsWith("SELECT label_text"),
                        ArgumentMatchers.<RowMapper<String>>any(),
                        eq(ReferenceData.LABEL_ENTITY),
                        eq(String.valueOf(VALUE_ID))))
                .thenReturn(hi == null ? List.of() : List.of(hi));
    }

    private void pending(String list, String code) {
        String payload =
                json.writeValueAsString(
                        new Change("EDIT", list, code, "x", null, null, TODAY, null, "r", "maker"));
        when(statuses.pending(ReferenceListService.ENTITY))
                .thenReturn(
                        List.of(
                                new ApprovalStatuses.Pending(
                                        55L, list + "." + code, payload, "maker", Instant.now())));
    }

    private static Proposal create(String code, LocalDate from, LocalDate to) {
        return new Proposal(code, " Study leave ", "  ", 40, from, to, " New value ");
    }

    private static Proposal edit(String en, String hi, Integer order, LocalDate to) {
        return new Proposal(null, en, hi, order, null, to, "Correction");
    }

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOf(FciException.class)
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(code);
    }

    private Submission submitted() {
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(admin), sent.capture(), any());
        return sent.getValue();
    }

    /** SRS l.1450, SC-04-0067: the text shown in the dropdown is unique in its list. */
    @Test
    void SRS_l1450_aTextAlreadyInTheListIsRefusedWithoutRegardToCase() {
        listExists(false);
        when(jdbc.queryForObject(
                        startsWith("SELECT COUNT(*) FROM reference_value"),
                        eq(Integer.class),
                        any(),
                        any(),
                        any()))
                .thenReturn(1);
        refused(
                () -> service.proposeNew(admin, LIST, create("STUDY", TODAY, null), null),
                ApiErrorCode.REFERENCE_LABEL_TAKEN);
        verifyNoInteractions(approvals);
    }

    /** SRS l.1450: a text waiting for its approval is taken too. */
    @Test
    void SRS_l1450_aTextInAPendingChangeOfAnotherValueIsTaken() {
        listExists(false);
        pending(LIST, "OTHER");
        refused(
                () ->
                        service.proposeNew(
                                admin,
                                LIST,
                                new Proposal("STUDY", "X", null, 1, TODAY, null, "r"),
                                null),
                ApiErrorCode.REFERENCE_LABEL_TAKEN);
        verifyNoInteractions(approvals);
    }

    @Test
    void SRS_16_15_onlyTheAdministratorAtHeadquartersProposes() {
        when(policy.isAtHeadquarters(admin)).thenReturn(false);
        refused(
                () -> service.proposeNew(admin, LIST, create("STUDY", TODAY, null), null),
                CommonErrorCode.ACCESS_DENIED);
        refused(
                () -> service.proposeChange(admin, LIST, "MEDICAL", edit("x", null, 1, null), null),
                CommonErrorCode.ACCESS_DENIED);
        verifyNoInteractions(approvals);
    }

    @Test
    void SRS_16_15_aListOutsideTheCatalogueIsRefused() {
        listExists(false);
        when(jdbc.query(
                        startsWith("SELECT list_id"),
                        ArgumentMatchers.<RowMapper<ListHead>>any(),
                        eq("NO_SUCH_LIST")))
                .thenReturn(List.of());
        for (String list : new String[] {null, "leave reason", "NO_SUCH_LIST"}) {
            refused(
                    () -> service.proposeNew(admin, list, create("STUDY", TODAY, null), null),
                    ApiErrorCode.MASTER_NOT_IN_CATALOGUE);
        }
    }

    @Test
    void SRS_16_15_aNewCodeIsOneTo40CapitalsDigitsOrUnderscores() {
        listExists(false);
        refused(
                () -> service.proposeNew(admin, LIST, null, null),
                CommonErrorCode.VALIDATION_FAILED);
        for (String code : new String[] {null, "study", "STUDY LEAVE", "S".repeat(41)}) {
            refused(
                    () -> service.proposeNew(admin, LIST, create(code, TODAY, null), null),
                    CommonErrorCode.VALIDATION_FAILED);
        }
        verifyNoInteractions(approvals);
    }

    @Test
    void SRS_16_15_aNewValueStartsTodayOrLaterAndEndsNoEarlierThanItStarts() {
        listExists(false);
        for (Proposal bad :
                List.of(
                        create("STUDY", null, null),
                        create("STUDY", TODAY.minusDays(1), null),
                        create("STUDY", TODAY.plusDays(5), TODAY.plusDays(4)))) {
            refused(
                    () -> service.proposeNew(admin, LIST, bad, null),
                    CommonErrorCode.VALIDATION_FAILED);
        }
        // A value in use for exactly one day is a valid value.
        service.proposeNew(
                admin, LIST, create("STUDY", TODAY.plusDays(5), TODAY.plusDays(5)), null);
        assertThat(submitted().effectiveDate()).isEqualTo(TODAY.plusDays(5));
    }

    @Test
    void SRS_16_15_aCodeAlreadyInTheListIsNeverGivenAgain() {
        listExists(false);
        valueExists("MEDICAL", 10, LocalDate.of(2026, 4, 1), null, null);
        refused(
                () -> service.proposeNew(admin, LIST, create("MEDICAL", TODAY, null), null),
                ApiErrorCode.REFERENCE_CODE_TAKEN);
    }

    @Test
    void SRS_16_15_aSecondProposalForOneValueWaitsForTheFirstDecision() {
        listExists(false);
        pending(LIST, "STUDY");
        refused(
                () -> service.proposeNew(admin, LIST, create("STUDY", TODAY, null), null),
                ApiErrorCode.REFERENCE_CHANGE_PENDING);
    }

    @Test
    void SRS_16_15_aPendingChangeOfAnotherValueOrListDoesNotHoldThisOne() {
        listExists(false);
        pending(LIST, "MEDICAL");
        service.proposeNew(admin, LIST, create("STUDY", TODAY, null), null);
        pending("OTHER_LIST", "STUDY");
        service.proposeNew(admin, LIST, create("STUDY", TODAY, null), null);
        verify(approvals, times(2)).submit(eq(admin), any(), any());
    }

    @Test
    void SRS_16_15_aNewValueCarriesTrimmedTextAndABlankHindiLabelIsNone() {
        listExists(false);

        PendingChange proposed =
                service.proposeNew(admin, LIST, create("STUDY", TODAY, null), null);

        assertThat(proposed.labelEn()).isEqualTo("Study leave");
        assertThat(proposed.labelHi()).isNull();
        assertThat(proposed.approvalId()).isEqualTo(77L);
        Submission sent = submitted();
        // The approval names the record by its key, so X-04 finds the submission and the decision.
        assertThat(sent.entityKey()).isEqualTo("LEAVE_REASON.STUDY");
        assertThat(sent.reason()).isEqualTo("New value");
        assertThat(sent.effectiveDate()).isEqualTo(TODAY);
    }

    @Test
    void SRS_16_15_aSystemListTakesNoNewCode() {
        listExists(true);
        refused(
                () -> service.proposeNew(admin, LIST, create("STUDY", TODAY, null), null),
                ApiErrorCode.REFERENCE_SYSTEM_LIST);
    }

    @Test
    void SRS_16_15_onlyAValueOfTheListCanBeChanged() {
        listExists(false);
        refused(
                () -> service.proposeChange(admin, LIST, "UNKNOWN", edit("x", null, 1, null), null),
                ApiErrorCode.MASTER_NOT_IN_CATALOGUE);
    }

    // SC-04-0064: a value in use is never removed, only end-dated.
    @Test
    void SRS_16_15_aValueEndsNoEarlierThanTodayNorBeforeItsFirstDay() {
        listExists(false);
        valueExists("MEDICAL", 10, LocalDate.of(2026, 4, 1), null, null);
        refused(
                () ->
                        service.proposeChange(
                                admin,
                                LIST,
                                "MEDICAL",
                                edit("Medical", null, 10, TODAY.minusDays(1)),
                                null),
                CommonErrorCode.VALIDATION_FAILED);
        // A value that starts later cannot end before it starts.
        valueExists("FUTURE", 10, TODAY.plusDays(30), null, null);
        refused(
                () ->
                        service.proposeChange(
                                admin,
                                LIST,
                                "FUTURE",
                                edit("Medical", null, 10, TODAY.plusDays(29)),
                                null),
                CommonErrorCode.VALIDATION_FAILED);
        // Ending today is allowed: the value is not offered from tomorrow.
        PendingChange ended =
                service.proposeChange(
                        admin, LIST, "MEDICAL", edit("Medical", null, 10, TODAY), null);
        assertThat(ended.effectiveTo()).isEqualTo(TODAY);
        assertThat(submitted().entityKey()).isEqualTo("LEAVE_REASON.MEDICAL");
    }

    @Test
    void SRS_16_15_aChangeThatChangesNothingIsRefused() {
        listExists(false);
        valueExists("MEDICAL", 10, LocalDate.of(2026, 4, 1), TODAY.plusDays(60), "चिकित्सा");
        refused(
                () ->
                        service.proposeChange(
                                admin,
                                LIST,
                                "MEDICAL",
                                edit(" Medical ", " चिकित्सा ", 10, TODAY.plusDays(60)),
                                null),
                CommonErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(approvals);
    }

    @Test
    void SRS_16_15_aNewOrderAloneIsAChangeDescribedAsLabelOrOrder() {
        listExists(false);
        valueExists("MEDICAL", 10, LocalDate.of(2026, 4, 1), TODAY.plusDays(60), null);

        PendingChange moved =
                service.proposeChange(
                        admin,
                        LIST,
                        "MEDICAL",
                        edit("Medical", null, 20, TODAY.plusDays(60)),
                        null);

        assertThat(moved.sortOrder()).isEqualTo(20);
        // The first day never changes; an EDIT is dated the day it is proposed.
        assertThat(moved.effectiveFrom()).isEqualTo(LocalDate.of(2026, 4, 1));
        Submission sent = submitted();
        assertThat(sent.entityKey()).isEqualTo("LEAVE_REASON.MEDICAL");
        assertThat(sent.effectiveDate()).isEqualTo(TODAY);
    }

    @Test
    void SRS_16_15_aNewLabelIsDescribedByTheLabelWhenTheEndStaysTheSame() {
        listExists(false);
        valueExists("MEDICAL", 10, LocalDate.of(2026, 4, 1), TODAY.plusDays(60), null);

        service.proposeChange(
                admin, LIST, "MEDICAL", edit("Medical leave", null, 10, TODAY.plusDays(60)), null);

        assertThat(submitted().entityKey()).isEqualTo("LEAVE_REASON.MEDICAL");
    }

    @Test
    void SRS_16_15_aNewHindiLabelAloneIsAChange() {
        listExists(false);
        valueExists("MEDICAL", 10, LocalDate.of(2026, 4, 1), null, null);

        PendingChange relabelled =
                service.proposeChange(
                        admin, LIST, "MEDICAL", edit("Medical", " चिकित्सा ", 10, null), null);

        assertThat(relabelled.labelHi()).isEqualTo("चिकित्सा");
        assertThat(submitted().entityKey()).isEqualTo("LEAVE_REASON.MEDICAL");
    }

    /** SRS-16-15: a stored M-16 change that cannot be read is refused with its own message. */
    @Test
    void SRS_16_15_anUnreadableStoredChangeIsRefused() {
        assertThatThrownBy(() -> service.read("{bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unreadable M-16 change")
                .hasCauseInstanceOf(tools.jackson.core.JacksonException.class);
        assertThatThrownBy(() -> service.read("not json at all"))
                .isInstanceOf(IllegalStateException.class);
    }
}
