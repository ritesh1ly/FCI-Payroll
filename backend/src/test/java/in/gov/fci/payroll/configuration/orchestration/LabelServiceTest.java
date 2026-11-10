package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.model.LabelMaster;
import in.gov.fci.payroll.configuration.orchestration.LabelService.LabelQuery;
import in.gov.fci.payroll.configuration.orchestration.LabelService.ProposedLabel;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * M-26 decisions the label screen cannot show on its own (SRS-G-39, SRS-G-40): a local name only
 * for a master value whose Global label is localisable, Active and in force, at the proposer's own
 * region or office; a label slot (key, language, scope, office) held by its Active or pending
 * version up to that version's date and by nothing else; an approval that supersedes only the same
 * slot's earlier version; a withdrawal that end-dates only a label still Active; the upload's
 * limits; the search's literal text.
 */
class LabelServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
    private static final long OFFICE = 30L;
    private static final long REGION = 20L;

    private final ConfigurationRepositories.Labels labels =
            mock(ConfigurationRepositories.Labels.class);
    private final ReferenceData reference = mock(ReferenceData.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final Authorization policy = mock(Authorization.class);
    private final SystemParameters settings = mock(SystemParameters.class);
    private final AuditService audit = mock(AuditService.class);

    private final LabelService service =
            new LabelService(
                    labels,
                    reference,
                    org,
                    approvals,
                    policy,
                    settings,
                    audit,
                    Clock.fixed(Instant.parse("2026-10-23T06:00:00Z"), ZoneOffset.UTC));

    private final AccessContext office =
            new AccessContext(
                    1L,
                    "maker.office@x",
                    "Maker",
                    "s",
                    "P",
                    OFFICE,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(OFFICE));

    private final AccessContext hq =
            new AccessContext(
                    2L,
                    "admin.hq@x",
                    "Admin",
                    "s",
                    "P",
                    1L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(1L));

    private long nextId = 100L;
    private int nextVersion = 1;

    @BeforeEach
    void wire() {
        when(settings.today()).thenReturn(TODAY);
        when(reference.contains(LabelService.LANGUAGES, "EN")).thenReturn(true);
        when(reference.contains(LabelService.LANGUAGES, "HI")).thenReturn(true);
        OrgLocation region = mock(OrgLocation.class);
        when(region.getId()).thenReturn(REGION);
        when(org.ancestorOrSelf(OFFICE, LocationType.RO)).thenReturn(Optional.of(region));
        when(labels.save(any())).thenAnswer(i -> i.getArgument(0));
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(9L, ApprovalStatus.PENDING_APPROVAL, null));
    }

    // ---------------------------------------------------------------- fixtures

    /** A stored label version (with an id, as the database gives it) in the given state. */
    private LabelMaster stored(
            String key,
            LabelMaster.Type type,
            String language,
            LabelMaster.Scope scope,
            Long location,
            boolean localisable,
            LocalDate from,
            LabelMaster.Status status) {
        boolean value = type == LabelMaster.Type.MASTER_VALUE;
        LabelMaster l =
                LabelMaster.propose(
                        new LabelMaster.Particulars(
                                key,
                                type,
                                value ? "REFERENCE_VALUE" : null,
                                value ? "GRADE_A" : null,
                                language,
                                scope,
                                location,
                                "Text " + key,
                                localisable,
                                from),
                        1,
                        "seed",
                        "seeded",
                        Instant.parse("2026-01-01T00:00:00Z"));
        ReflectionTestUtils.setField(l, "labelId", nextId++);
        ReflectionTestUtils.setField(l, "versionNo", nextVersion++);
        ReflectionTestUtils.setField(l, "labelStatus", status.name());
        return l;
    }

    /** The Global English label of a localisable master value, Active since January. */
    private LabelMaster localisableGlobal(String key) {
        return stored(
                key,
                LabelMaster.Type.MASTER_VALUE,
                "EN",
                LabelMaster.Scope.GLOBAL,
                null,
                true,
                LocalDate.of(2026, 1, 1),
                LabelMaster.Status.ACTIVE);
    }

    private static ProposedLabel local(String key, String scope, LocalDate from) {
        return new ProposedLabel(
                key,
                "MASTER_VALUE",
                "REFERENCE_VALUE",
                "GRADE_A",
                "HI",
                scope,
                "Sthaniya naam",
                false,
                from,
                "Local usage");
    }

    private static ProposedLabel global(String key, String language, LocalDate from) {
        return new ProposedLabel(
                key, "UI_LABEL", null, null, language, "GLOBAL", "Text", false, from, "New text");
    }

    private static ErrorCode code(Throwable t) {
        return ((FciException) t).getErrorCode();
    }

    private static void refused(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).satisfies(e -> assertThat(code(e)).isEqualTo(expected));
    }

    private Submission submitted(AccessContext by) {
        ArgumentCaptor<Submission> submission = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(by), submission.capture(), isNull());
        return submission.getValue();
    }

    private static ApprovalDecided decided(
            long approval, String entity, String key, ApprovalStatus outcome, String reason) {
        return new ApprovalDecided(
                approval,
                LabelService.SCREEN,
                entity,
                key,
                outcome,
                reason,
                null,
                "checker.ro@x",
                Instant.parse("2026-10-23T07:00:00Z"));
    }

    // ---------------------------------------------------------------- local names

    @Test
    void SRS_G_39_aLocalNameNeedsTheGlobalLabelOfALocalisableMasterValueActiveAndInForce() {
        String key = "GRADE.A";
        LabelMaster pending = localisableGlobal(key);
        ReflectionTestUtils.setField(pending, "labelStatus", "PENDING_APPROVAL");
        LabelMaster future = localisableGlobal(key);
        ReflectionTestUtils.setField(future, "effectiveFrom", TODAY.plusDays(1));
        LabelMaster regional =
                stored(
                        key,
                        LabelMaster.Type.MASTER_VALUE,
                        "EN",
                        LabelMaster.Scope.REGION,
                        REGION,
                        true,
                        LocalDate.of(2026, 1, 1),
                        LabelMaster.Status.ACTIVE);
        LabelMaster fixed = localisableGlobal(key);
        ReflectionTestUtils.setField(fixed, "isLocalisable", false);
        // A migrated UI label flagged localisable is still no master value.
        LabelMaster uiLabel =
                stored(
                        key,
                        LabelMaster.Type.UI_LABEL,
                        "EN",
                        LabelMaster.Scope.GLOBAL,
                        null,
                        true,
                        LocalDate.of(2026, 1, 1),
                        LabelMaster.Status.ACTIVE);
        for (LabelMaster notEnough : List.of(pending, future, regional, fixed, uiLabel)) {
            when(labels.findByLabelKeyOrderByVersionNoDesc(key)).thenReturn(List.of(notEnough));
            refused(
                    () -> service.propose(office, local(key, "OFFICE", TODAY), null),
                    ApiErrorCode.LABEL_SCOPE_NOT_ALLOWED);
        }
        verify(approvals, never()).submit(any(), any(), any());

        List<LabelMaster> versions =
                new ArrayList<>(List.of(pending, future, regional, fixed, uiLabel));
        versions.add(localisableGlobal(key));
        when(labels.findByLabelKeyOrderByVersionNoDesc(key)).thenReturn(versions);
        LabelService.LabelView proposed =
                service.propose(office, local(key, "OFFICE", TODAY), null);

        assertThat(proposed.scope()).isEqualTo("OFFICE");
        assertThat(proposed.scopeLocationId()).isEqualTo(OFFICE);
        assertThat(proposed.localisable())
                .as("a local name is a row of a localisable value")
                .isTrue();
        assertThat(proposed.versionNo()).as("after every version of the key").isEqualTo(7);
        Submission submission = submitted(office);
        assertThat(submission.workflowAction()).isEqualTo("M26_LOCAL");
        assertThat(submission.locationId())
                .as("approved at the proposer's office")
                .isEqualTo(OFFICE);
        assertThat(submission.entityKey()).isEqualTo(key + "|HI|OFFICE|" + OFFICE);
    }

    @Test
    void SRS_G_39_aRegionalNameIsTheProposersOwnRegionAndNeedsOne() {
        String key = "GRADE.B";
        when(labels.findByLabelKeyOrderByVersionNoDesc(key))
                .thenReturn(List.of(localisableGlobal(key)));
        LabelService.LabelView regional =
                service.propose(office, local(key, "REGION", TODAY), null);
        assertThat(regional.scopeLocationId()).isEqualTo(REGION);

        // An office outside any Regional Office has no region to name a label for.
        when(org.ancestorOrSelf(OFFICE, LocationType.RO)).thenReturn(Optional.empty());
        refused(
                () -> service.propose(office, local(key, "REGION", TODAY), null),
                ApiErrorCode.LABEL_SCOPE_NOT_ALLOWED);
    }

    // ---------------------------------------------------------------- the slot and its date

    @Test
    void SRS_G_39_aSlotIsHeldOnlyByItsActiveOrPendingVersionAndOnlyUpToThatVersionsDate() {
        String key = "MENU.PAYSLIP";
        LocalDate d = TODAY.plusDays(10);
        LabelMaster active =
                stored(
                        key,
                        LabelMaster.Type.UI_LABEL,
                        "EN",
                        LabelMaster.Scope.GLOBAL,
                        null,
                        false,
                        d,
                        LabelMaster.Status.ACTIVE);
        when(labels.findByLabelKeyOrderByVersionNoDesc(key)).thenReturn(List.of(active));
        refused(
                () -> service.propose(office, global(key, "EN", d), null),
                ApiErrorCode.LABEL_TAKEN);
        refused(
                () -> service.propose(office, global(key, "EN", d.minusDays(1)), null),
                ApiErrorCode.LABEL_TAKEN);

        LabelMaster pending =
                stored(
                        key,
                        LabelMaster.Type.UI_LABEL,
                        "EN",
                        LabelMaster.Scope.GLOBAL,
                        null,
                        false,
                        d,
                        LabelMaster.Status.PENDING_APPROVAL);
        when(labels.findByLabelKeyOrderByVersionNoDesc(key)).thenReturn(List.of(pending));
        refused(
                () -> service.propose(office, global(key, "EN", d), null),
                ApiErrorCode.LABEL_TAKEN);
        verify(approvals, never()).submit(any(), any(), any());

        // Neither another language nor an ended version holds the date.
        List<LabelMaster> others = new ArrayList<>();
        others.add(
                stored(
                        key,
                        LabelMaster.Type.UI_LABEL,
                        "HI",
                        LabelMaster.Scope.GLOBAL,
                        null,
                        false,
                        d,
                        LabelMaster.Status.ACTIVE));
        for (LabelMaster.Status ended :
                List.of(
                        LabelMaster.Status.REJECTED,
                        LabelMaster.Status.SUPERSEDED,
                        LabelMaster.Status.WITHDRAWN)) {
            others.add(
                    stored(
                            key,
                            LabelMaster.Type.UI_LABEL,
                            "EN",
                            LabelMaster.Scope.GLOBAL,
                            null,
                            false,
                            d,
                            ended));
        }
        when(labels.findByLabelKeyOrderByVersionNoDesc(key)).thenReturn(others);
        LabelService.LabelView proposed = service.propose(office, global(key, "EN", d), null);
        assertThat(proposed.versionNo()).as("after the withdrawn v6, the highest").isEqualTo(7);
        Submission submission = submitted(office);
        assertThat(submission.workflowAction()).isEqualTo("M26_CREATE");
        assertThat(submission.locationId())
                .as("a Global label proposed away from headquarters is routed nationally")
                .isNull();
    }

    @Test
    void SRS_G_39_aLocalNameOfAnotherOfficeDoesNotHoldThisOfficesDate() {
        String key = "GRADE.C";
        LabelMaster neighbour =
                stored(
                        key,
                        LabelMaster.Type.MASTER_VALUE,
                        "HI",
                        LabelMaster.Scope.OFFICE,
                        31L,
                        false,
                        TODAY,
                        LabelMaster.Status.ACTIVE);
        when(labels.findByLabelKeyOrderByVersionNoDesc(key))
                .thenReturn(List.of(localisableGlobal(key), neighbour));
        assertThat(service.propose(office, local(key, "OFFICE", TODAY), null).scopeLocationId())
                .isEqualTo(OFFICE);
    }

    @Test
    void SRS_G_39_aLanguageOutsideTheLanguageListIsRefused() {
        refused(
                () -> service.propose(office, global("MENU.X", "TA", TODAY), null),
                ApiErrorCode.MASTER_UNKNOWN_VALUE);
        refused(
                () ->
                        LabelService.validate(
                                new ProposedLabel(
                                        "MENU.X", null, null, null, "EN", null, "Text", false,
                                        TODAY, "r")),
                ApiErrorCode.LABEL_INVALID);
    }

    // ---------------------------------------------------------------- decisions

    @Test
    void SRS_G_39_anApprovedVersionSupersedesOnlyTheSameSlotsEarlierVersion() {
        String key = "GRADE.D";
        LocalDate from = TODAY.plusDays(5);
        LabelMaster proposed =
                stored(
                        key,
                        LabelMaster.Type.MASTER_VALUE,
                        "HI",
                        LabelMaster.Scope.OFFICE,
                        OFFICE,
                        true,
                        from,
                        LabelMaster.Status.PENDING_APPROVAL);
        LabelMaster earlier =
                stored(
                        key,
                        LabelMaster.Type.MASTER_VALUE,
                        "HI",
                        LabelMaster.Scope.OFFICE,
                        OFFICE,
                        true,
                        TODAY.minusDays(30),
                        LabelMaster.Status.ACTIVE);
        LabelMaster sameDay =
                stored(
                        key,
                        LabelMaster.Type.MASTER_VALUE,
                        "HI",
                        LabelMaster.Scope.OFFICE,
                        OFFICE,
                        true,
                        from,
                        LabelMaster.Status.ACTIVE);
        LabelMaster otherOffice =
                stored(
                        key,
                        LabelMaster.Type.MASTER_VALUE,
                        "HI",
                        LabelMaster.Scope.OFFICE,
                        31L,
                        true,
                        TODAY.minusDays(30),
                        LabelMaster.Status.ACTIVE);
        LabelMaster otherScope =
                stored(
                        key,
                        LabelMaster.Type.MASTER_VALUE,
                        "HI",
                        LabelMaster.Scope.REGION,
                        REGION,
                        true,
                        TODAY.minusDays(30),
                        LabelMaster.Status.ACTIVE);
        LabelMaster english =
                stored(
                        key,
                        LabelMaster.Type.MASTER_VALUE,
                        "EN",
                        LabelMaster.Scope.OFFICE,
                        OFFICE,
                        true,
                        TODAY.minusDays(30),
                        LabelMaster.Status.ACTIVE);
        LabelMaster ended =
                stored(
                        key,
                        LabelMaster.Type.MASTER_VALUE,
                        "HI",
                        LabelMaster.Scope.OFFICE,
                        OFFICE,
                        true,
                        TODAY.minusDays(60),
                        LabelMaster.Status.SUPERSEDED);
        when(labels.findByApprovalId(9L)).thenReturn(Optional.of(proposed));
        when(labels.findByLabelKeyOrderByVersionNoDesc(key))
                .thenReturn(
                        List.of(
                                proposed,
                                earlier,
                                sameDay,
                                otherOffice,
                                otherScope,
                                english,
                                ended));

        service.on(decided(9L, LabelService.ENTITY, "x", ApprovalStatus.APPROVED, " "));

        assertThat(proposed.status()).isEqualTo(LabelMaster.Status.ACTIVE);
        assertThat(earlier.status()).isEqualTo(LabelMaster.Status.SUPERSEDED);
        assertThat(earlier.getEffectiveTo()).isEqualTo(from.minusDays(1));
        for (LabelMaster untouched : List.of(sameDay, otherOffice, otherScope, english)) {
            assertThat(untouched.status()).isEqualTo(LabelMaster.Status.ACTIVE);
            assertThat(untouched.getEffectiveTo()).isNull();
        }
        assertThat(ended.getEffectiveTo()).isNull();
        // A label of a reference value refreshes the reference lists; a blank reason reads
        // "Approved" in the audit trail.
        verify(reference).changed("checker.ro@x");
        ArgumentCaptor<List<AuditService.Entry>> entries = auditEntries();
        assertThat(entries.getAllValues().stream().flatMap(List::stream))
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e.reason()).isEqualTo("Approved"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<List<AuditService.Entry>> auditEntries() {
        ArgumentCaptor<List<AuditService.Entry>> entries =
                (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(audit, atLeastOnce()).recordAll(eq("checker.ro@x"), entries.capture());
        return entries;
    }

    @Test
    void SRS_G_39_aRejectedVersionIsRecordedWithTheCheckersReasonAndSupersedesNothing() {
        String key = "MENU.HOME";
        LabelMaster proposed =
                stored(
                        key,
                        LabelMaster.Type.UI_LABEL,
                        "EN",
                        LabelMaster.Scope.GLOBAL,
                        null,
                        false,
                        TODAY,
                        LabelMaster.Status.PENDING_APPROVAL);
        when(labels.findByApprovalId(9L)).thenReturn(Optional.of(proposed));

        service.on(decided(9L, LabelService.ENTITY, "x", ApprovalStatus.REJECTED, "Wrong term"));

        assertThat(proposed.status()).isEqualTo(LabelMaster.Status.REJECTED);
        verify(labels, never()).findByLabelKeyOrderByVersionNoDesc(any());
        verify(reference, never()).changed(any());
        assertThat(auditEntries().getValue())
                .allSatisfy(e -> assertThat(e.reason()).isEqualTo("Wrong term"));
    }

    @Test
    void SRS_G_40_aWithdrawalEndDatesOnlyALabelStillActiveAndNeverBeforeItsFirstDay() {
        LabelMaster startsToday =
                stored(
                        "GRADE.E",
                        LabelMaster.Type.MASTER_VALUE,
                        "HI",
                        LabelMaster.Scope.OFFICE,
                        OFFICE,
                        true,
                        TODAY,
                        LabelMaster.Status.ACTIVE);
        when(labels.findById(startsToday.getLabelId())).thenReturn(Optional.of(startsToday));
        String id = String.valueOf(startsToday.getLabelId());

        // Rejected: nothing changes.
        service.on(decided(10L, LabelService.WITHDRAWAL, id, ApprovalStatus.REJECTED, "No"));
        assertThat(startsToday.status()).isEqualTo(LabelMaster.Status.ACTIVE);

        // Approved: withdrawn; it cannot end before the day it started.
        service.on(decided(10L, LabelService.WITHDRAWAL, id, ApprovalStatus.APPROVED, null));
        assertThat(startsToday.status()).isEqualTo(LabelMaster.Status.WITHDRAWN);
        assertThat(startsToday.getEffectiveTo()).isEqualTo(TODAY);
        verify(reference).changed("checker.ro@x");

        // A label no longer Active (superseded meanwhile) is left as it is.
        LabelMaster superseded =
                stored(
                        "GRADE.F",
                        LabelMaster.Type.UI_LABEL,
                        "EN",
                        LabelMaster.Scope.OFFICE,
                        OFFICE,
                        false,
                        TODAY.minusDays(9),
                        LabelMaster.Status.SUPERSEDED);
        when(labels.findById(superseded.getLabelId())).thenReturn(Optional.of(superseded));
        service.on(
                decided(
                        11L,
                        LabelService.WITHDRAWAL,
                        String.valueOf(superseded.getLabelId()),
                        ApprovalStatus.APPROVED,
                        null));
        assertThat(superseded.status()).isEqualTo(LabelMaster.Status.SUPERSEDED);
        assertThat(superseded.getEffectiveTo()).isNull();
    }

    @Test
    void SRS_G_40_aWithdrawalIsDecidedAtHeadquartersWhereverItIsAsked() {
        LabelMaster local =
                stored(
                        "GRADE.G",
                        LabelMaster.Type.MASTER_VALUE,
                        "HI",
                        LabelMaster.Scope.OFFICE,
                        OFFICE,
                        true,
                        TODAY.minusDays(9),
                        LabelMaster.Status.ACTIVE);
        when(labels.findById(local.getLabelId())).thenReturn(Optional.of(local));
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        service.withdraw(hq, local.getLabelId(), "Name retired", null);
        assertThat(submitted(hq).locationId()).isEqualTo(1L);
        // Asked from an office: no office on the item, so the national route decides it.
        service.withdraw(office, local.getLabelId(), "Name retired", null);
        assertThat(submitted(office).locationId()).isNull();
        assertThat(local.status())
                .as("nothing changes before the decision")
                .isEqualTo(LabelMaster.Status.ACTIVE);
    }

    // ---------------------------------------------------------------- search, export, upload

    @Test
    void SRS_G_39_aSearchWithoutCriteriaListsEverythingAndItsTextIsTakenLiterally() {
        service.search(office, null);
        service.search(office, new LabelQuery("  ", " ", "", null));
        verify(labels, times(2))
                .search(null, null, null, null, PageRequest.of(0, LabelService.MAX_ROWS));

        service.search(office, new LabelQuery(" 50%_Off ", " HI ", "GLOBAL", "ACTIVE"));
        verify(labels)
                .search(
                        "%50" + (char) 92 + "%" + (char) 92 + "_off%",
                        "HI",
                        "GLOBAL",
                        "ACTIVE",
                        PageRequest.of(0, LabelService.MAX_ROWS));
    }

    @Test
    void SRS_G_39_theResolvedLabelIsEnglishWhenNoLanguageIsAskedAndEmptyWhenNoneExists() {
        LabelMaster english =
                stored(
                        "MENU.TAX",
                        LabelMaster.Type.UI_LABEL,
                        "EN",
                        LabelMaster.Scope.GLOBAL,
                        null,
                        false,
                        TODAY.minusDays(1),
                        LabelMaster.Status.ACTIVE);
        when(labels.findByLabelKeyOrderByVersionNoDesc("MENU.TAX")).thenReturn(List.of(english));
        LabelService.Resolved shown = service.resolve(office, "MENU.TAX", null);
        assertThat(shown.text()).isEqualTo("Text MENU.TAX");
        assertThat(shown.language()).isEqualTo("EN");
        assertThat(shown.labelId()).isEqualTo(english.getLabelId());

        LabelService.Resolved none = service.resolve(office, "MENU.NONE", "HI");
        assertThat(none.key()).isEqualTo("MENU.NONE");
        assertThat(none.text()).isNull();
        assertThat(none.labelId()).isNull();
    }

    @Test
    void SRS_G_39_theBulkDownloadLeavesOutRejectedVersions() {
        when(labels.search(any(), any(), any(), any(), any()))
                .thenReturn(
                        List.of(
                                stored(
                                        "A",
                                        LabelMaster.Type.UI_LABEL,
                                        "EN",
                                        LabelMaster.Scope.GLOBAL,
                                        null,
                                        false,
                                        TODAY,
                                        LabelMaster.Status.ACTIVE),
                                stored(
                                        "A",
                                        LabelMaster.Type.UI_LABEL,
                                        "EN",
                                        LabelMaster.Scope.GLOBAL,
                                        null,
                                        false,
                                        TODAY.plusDays(1),
                                        LabelMaster.Status.REJECTED),
                                stored(
                                        "B",
                                        LabelMaster.Type.UI_LABEL,
                                        "EN",
                                        LabelMaster.Scope.GLOBAL,
                                        null,
                                        false,
                                        TODAY,
                                        LabelMaster.Status.PENDING_APPROVAL)));
        assertThat(service.exportSize(office, "EN", "GLOBAL")).isEqualTo(2);
    }

    /**
     * SRS-G-39: an upload needs at least one row and its reason. The source states no row limit
     * (BMAD TR-005): the built cap ({@link LabelService#MAX_ROWS}) is not a business rule and waits
     * for FCI (CLR-139); it is exercised here only as the behaviour as built.
     */
    @Test
    void SRS_G_39_anUploadNeedsRowsAndItsReason() {
        ProposedLabel row = global("MENU.UP", "EN", TODAY);
        for (List<ProposedLabel> rows :
                Arrays.asList(
                        null,
                        List.<ProposedLabel>of(),
                        // CLR-139: the built cap, pending FCI; not a source rule.
                        Collections.nCopies(LabelService.MAX_ROWS + 1, row))) {
            refused(
                    () -> service.upload(office, rows, "Bulk", null),
                    CommonErrorCode.VALIDATION_FAILED);
        }
        for (String reason : Arrays.asList(null, "", "  ")) {
            refused(
                    () -> service.upload(office, List.of(row), reason, null),
                    ApiErrorCode.REASON_REQUIRED);
        }
        verify(approvals, never()).submit(any(), any(), any());
        verify(audit, never()).recordAll(any(), anyList());
    }

    @Test
    void SRS_G_39_aGlobalMasterValueLabelKeepsItsLocalisableMark() {
        // chk_label_master_local: a Global label of a master value may be marked localisable, so
        // that Regional and Office names may follow it; a Global UI label never is.
        LabelService.LabelView value =
                service.propose(
                        hq,
                        new ProposedLabel(
                                "REFVAL.GRADE_B",
                                "MASTER_VALUE",
                                "REFERENCE_VALUE",
                                "GRADE_B",
                                "EN",
                                "GLOBAL",
                                "Grade B",
                                true,
                                TODAY.plusDays(1),
                                "New value"),
                        null);
        assertThat(value.localisable()).isTrue();
        LabelService.LabelView plain =
                service.propose(
                        hq,
                        new ProposedLabel(
                                "MENU.Y",
                                "UI_LABEL",
                                null,
                                null,
                                "EN",
                                "GLOBAL",
                                "Text",
                                true,
                                TODAY.plusDays(1),
                                "New text"),
                        null);
        assertThat(plain.localisable()).isFalse();
    }
}
