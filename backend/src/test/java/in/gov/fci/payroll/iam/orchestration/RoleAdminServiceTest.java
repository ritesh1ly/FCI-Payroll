package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.iam.business.model.AppPermission;
import in.gov.fci.payroll.iam.business.model.AppRole;
import in.gov.fci.payroll.iam.business.model.AppRolePermission;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A-02 roles and the permission matrix (SRS-22-8, SRS-22-23, SRS-22-19): a role proposal needs a
 * valid code, a name and a base role unless it is read-only; a grant proposal needs a reason, a
 * known role and permission, and a real change; nothing changes before an approval, and the
 * approval re-checks the state: a role code taken meanwhile fails it, a grant already in force is
 * not applied twice, a protected role is never changed.
 */
class RoleAdminServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
    private static final long ROLE_ID = 5L;
    private static final long PERMISSION_ID = 7L;

    private final AccessRepositories.Roles roles = mock(AccessRepositories.Roles.class);
    private final AccessRepositories.Permissions permissions =
            mock(AccessRepositories.Permissions.class);
    private final AccessRepositories.RolePermissions grants =
            mock(AccessRepositories.RolePermissions.class);
    private final AccessContextService contexts = mock(AccessContextService.class);
    private final AuditService audit = mock(AuditService.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final ObjectMapper mapper = JsonMapper.builder().build();

    private final RoleAdminService service =
            new RoleAdminService(
                    roles,
                    permissions,
                    grants,
                    contexts,
                    mock(AccessPolicy.class),
                    audit,
                    approvals,
                    mock(AdminScope.class),
                    mapper);

    private final AccessContext admin =
            new AccessContext(
                    1L,
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

    private AppRole role;
    private AppPermission permission;

    @BeforeEach
    void wire() {
        when(contexts.today()).thenReturn(TODAY);
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(9L, ApprovalStatus.PENDING_APPROVAL, null));
        role = role(ROLE_ID, "PAYROLL_AUDITOR");
        when(roles.findById(ROLE_ID)).thenReturn(Optional.of(role));
        permission = mock(AppPermission.class);
        when(permission.getId()).thenReturn(PERMISSION_ID);
        when(permission.key()).thenReturn("C-13:VIEW");
        when(permissions.findAllByOrderByScreenCodeAscActionCodeAsc())
                .thenReturn(List.of(permission));
    }

    private static AppRole role(long id, String code) {
        AppRole r =
                AppRole.create(
                        code,
                        "Role " + code,
                        null,
                        false,
                        AppRole.RoleType.MODULE,
                        AppRole.BaseRole.CHECKER,
                        LocalDate.of(2026, 1, 1));
        ReflectionTestUtils.setField(r, "id", id);
        return r;
    }

    private static AppRolePermission grant(LocalDate from, LocalDate to) {
        AppRolePermission g = new AppRolePermission(ROLE_ID, PERMISSION_ID, from);
        if (to != null) {
            g.endBefore(to.plusDays(1));
        }
        return g;
    }

    private void granted(AppRolePermission g) {
        when(grants.findById(new AppRolePermission.Key(ROLE_ID, PERMISSION_ID)))
                .thenReturn(Optional.ofNullable(g));
    }

    private static ErrorCode code(Throwable t) {
        return ((FciException) t).getErrorCode();
    }

    private static void refused(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).satisfies(e -> assertThat(code(e)).isEqualTo(expected));
    }

    private JsonNode lastPayload() {
        ArgumentCaptor<Submission> submission = ArgumentCaptor.forClass(Submission.class);
        verify(approvals, atLeastOnce()).submit(eq(admin), submission.capture(), any());
        return mapper.readTree(submission.getValue().payload());
    }

    private ApprovalDecided decided(String entity, ApprovalStatus outcome, String payload) {
        return new ApprovalDecided(
                9L,
                "A-02",
                entity,
                "key",
                outcome,
                null,
                payload,
                "checker.hq@x",
                Instant.parse("2026-10-23T07:00:00Z"));
    }

    // ---------------------------------------------------------------- role proposals

    @Test
    void SRS_22_8_aRoleProposalNeedsAValidCodeANameAndABaseRoleUnlessItIsReadOnly() {
        for (String bad : Arrays.asList(null, "", "AB", "1ROLE", "ROLE-X", "R".repeat(31))) {
            refused(
                    () ->
                            service.proposeRole(
                                    admin,
                                    bad,
                                    "Name",
                                    null,
                                    false,
                                    null,
                                    AppRole.BaseRole.MAKER,
                                    "New role",
                                    null),
                    CommonErrorCode.VALIDATION_FAILED);
        }
        for (String name : Arrays.asList(null, "", "   ")) {
            refused(
                    () ->
                            service.proposeRole(
                                    admin,
                                    "AUDITOR",
                                    name,
                                    null,
                                    false,
                                    null,
                                    AppRole.BaseRole.MAKER,
                                    "New role",
                                    null),
                    CommonErrorCode.VALIDATION_FAILED);
        }
        // A module role (the default type) and a functional one each need a base role.
        for (AppRole.RoleType type : Arrays.asList(null, AppRole.RoleType.FUNCTIONAL)) {
            refused(
                    () ->
                            service.proposeRole(
                                    admin,
                                    "AUDITOR",
                                    "Auditor",
                                    null,
                                    false,
                                    type,
                                    null,
                                    "New role",
                                    null),
                    CommonErrorCode.VALIDATION_FAILED);
        }
        verify(approvals, never()).submit(any(), any(), any());
    }

    /**
     * X-01 (SRS l.1133, SRS-22-19, BR-APPR-006; US-GAP-122): "New Role, Edit matrix ... are for the
     * HQ Administrator only" - an administrator acting below Headquarters proposes neither a role
     * nor a grant, and nothing is submitted.
     */
    @Test
    void SRS_22_19_onlyTheHqAdministratorProposesARoleOrAMatrixChange() {
        AdminScope scope = mock(AdminScope.class);
        org.mockito.Mockito.doThrow(new FciException(ApiErrorCode.ADMIN_HQ_ONLY))
                .when(scope)
                .requireNational(eq(admin), any());
        RoleAdminService belowHq =
                new RoleAdminService(
                        roles,
                        permissions,
                        grants,
                        contexts,
                        mock(AccessPolicy.class),
                        audit,
                        approvals,
                        scope,
                        mapper);
        refused(
                () ->
                        belowHq.proposeRole(
                                admin,
                                "AUDITOR",
                                "Auditor",
                                null,
                                false,
                                null,
                                AppRole.BaseRole.MAKER,
                                "New role",
                                null),
                ApiErrorCode.ADMIN_HQ_ONLY);
        refused(
                () -> belowHq.proposeGrant(admin, ROLE_ID, PERMISSION_ID, true, "Grant", null),
                ApiErrorCode.ADMIN_HQ_ONLY);
        verify(approvals, never()).submit(any(), any(), any());
    }

    @Test
    void SRS_22_8_aRoleProposalNeedsItsReasonAndACodeNotYetTaken() {
        for (String reason : Arrays.asList(null, "", "  ")) {
            refused(
                    () ->
                            service.proposeRole(
                                    admin,
                                    "AUDITOR",
                                    "Auditor",
                                    null,
                                    false,
                                    null,
                                    AppRole.BaseRole.MAKER,
                                    reason,
                                    null),
                    ApiErrorCode.REASON_REQUIRED);
        }
        when(roles.findByCode("AUDITOR")).thenReturn(Optional.of(role(6L, "AUDITOR")));
        refused(
                () ->
                        service.proposeRole(
                                admin,
                                " auditor ",
                                "Auditor",
                                null,
                                false,
                                null,
                                AppRole.BaseRole.MAKER,
                                "New role",
                                null),
                CommonErrorCode.CONFLICT);
        verify(approvals, never()).submit(any(), any(), any());
    }

    @Test
    void SRS_22_8_theProposalCarriesTheNormalisedCodeAndABaseRoleOnlyWhereItApplies() {
        RoleAdminService.Proposal module =
                service.proposeRole(
                        admin,
                        " payroll_reviewer ",
                        " Reviewer ",
                        "Reads runs",
                        true,
                        null,
                        AppRole.BaseRole.CHECKER,
                        " Audit need ",
                        null);
        assertThat(module).isEqualTo(new RoleAdminService.Proposal(9L, "PENDING_APPROVAL"));
        JsonNode p = lastPayload();
        assertThat(p.get("code").asString()).isEqualTo("PAYROLL_REVIEWER");
        assertThat(p.get("name").asString()).isEqualTo("Reviewer");
        assertThat(p.get("roleType").asString()).isEqualTo("MODULE");
        assertThat(p.get("baseRole").asString()).isEqualTo("CHECKER");
        assertThat(p.get("approver").asBoolean()).isTrue();

        // A read-only role has no base role, even when one is sent.
        service.proposeRole(
                admin,
                "READER",
                "Reader",
                null,
                false,
                AppRole.RoleType.READ_ONLY,
                AppRole.BaseRole.MAKER,
                "Audit need",
                null);
        assertThat(lastPayload().get("baseRole").isNull()).isTrue();
    }

    // ---------------------------------------------------------------- grant proposals

    @Test
    void SRS_22_8_aGrantProposalNeedsItsReasonAKnownRoleAndAKnownPermission() {
        for (String reason : Arrays.asList(null, "", "  ")) {
            refused(
                    () -> service.proposeGrant(admin, ROLE_ID, PERMISSION_ID, true, reason, null),
                    ApiErrorCode.REASON_REQUIRED);
        }
        refused(
                () -> service.proposeGrant(admin, 99L, PERMISSION_ID, true, "Need", null),
                CommonErrorCode.NOT_FOUND);
        when(roles.findById(1L)).thenReturn(Optional.of(role(1L, "SYSTEM_ADMINISTRATOR")));
        refused(
                () -> service.proposeGrant(admin, 1L, PERMISSION_ID, true, "Need", null),
                ApiErrorCode.PROTECTED_ROLE);
        refused(
                () -> service.proposeGrant(admin, ROLE_ID, 99L, true, "Need", null),
                CommonErrorCode.NOT_FOUND);
        verify(approvals, never()).submit(any(), any(), any());
    }

    @Test
    void SRS_22_8_aGrantProposalMustChangeTheMatrixAsItStandsToday() {
        // Withdrawing what was never granted, or what has already ended.
        granted(null);
        refused(
                () ->
                        service.proposeGrant(
                                admin, ROLE_ID, PERMISSION_ID, false, "Not needed", null),
                ApiErrorCode.INVALID_STATUS_CHANGE);
        granted(grant(LocalDate.of(2026, 1, 1), TODAY.minusDays(1)));
        refused(
                () ->
                        service.proposeGrant(
                                admin, ROLE_ID, PERMISSION_ID, false, "Not needed", null),
                ApiErrorCode.INVALID_STATUS_CHANGE);
        // Granting what is in force.
        granted(grant(LocalDate.of(2026, 1, 1), null));
        refused(
                () -> service.proposeGrant(admin, ROLE_ID, PERMISSION_ID, true, "Needed", null),
                ApiErrorCode.INVALID_STATUS_CHANGE);
        verify(approvals, never()).submit(any(), any(), any());

        // Granting again what has ended is a change.
        granted(grant(LocalDate.of(2026, 1, 1), TODAY.minusDays(1)));
        service.proposeGrant(admin, ROLE_ID, PERMISSION_ID, true, "Needed again", null);
        ArgumentCaptor<Submission> submission = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(admin), submission.capture(), any());
        assertThat(submission.getValue().entityKey()).isEqualTo("PAYROLL_AUDITOR|C-13:VIEW");
        assertThat(mapper.readTree(submission.getValue().payload()).get("granted").asBoolean())
                .isTrue();
    }

    // ---------------------------------------------------------------- decisions

    @Test
    void SRS_22_23_onlyAnApprovedDecisionCarryingItsProposalChangesAnything() {
        String payload = "{\"roleId\":5,\"permissionId\":7,\"granted\":true}";
        service.on(decided(RoleAdminService.GRANT, ApprovalStatus.REJECTED, payload));
        service.on(decided(RoleAdminService.GRANT, ApprovalStatus.RETURNED, payload));
        service.on(decided(RoleAdminService.GRANT, ApprovalStatus.APPROVED, null));
        service.on(decided("access_group", ApprovalStatus.APPROVED, payload));
        verify(grants, never()).save(any());
        verify(roles, never()).save(any());
        verify(audit, never()).record(any(), any());
    }

    @Test
    void SRS_22_23_aCorruptedStoredProposalFailsTheApprovalAndAppliesNothing() {
        assertThatThrownBy(
                        () ->
                                service.on(
                                        decided(
                                                RoleAdminService.GRANT,
                                                ApprovalStatus.APPROVED,
                                                "{\"roleId\":5,")))
                .isInstanceOf(IllegalStateException.class)
                // The refusal is the unreadable proposal itself, not any other state failure.
                .hasCauseInstanceOf(JacksonException.class);
        verify(grants, never()).save(any());
        verify(roles, never()).save(any());
        verify(audit, never()).record(any(), any());
        verify(contexts, never()).evictAll();
    }

    @Test
    void SRS_22_23_aRoleCodeTakenBeforeTheApprovalFailsIt() {
        when(roles.findByCode("AUDITOR")).thenReturn(Optional.of(role(6L, "AUDITOR")));
        refused(
                () ->
                        service.on(
                                decided(
                                        RoleAdminService.ROLE,
                                        ApprovalStatus.APPROVED,
                                        "{\"code\":\"AUDITOR\",\"name\":\"Auditor\","
                                                + "\"roleType\":\"READ_ONLY\"}")),
                CommonErrorCode.CONFLICT);
        verify(roles, never()).save(any());
    }

    @Test
    void SRS_22_23_anApprovedRoleIsCreatedProvisionalWithOnlyTheFieldsItWasGiven() {
        when(roles.save(any())).thenAnswer(i -> i.getArgument(0));
        service.on(
                decided(
                        RoleAdminService.ROLE,
                        ApprovalStatus.APPROVED,
                        "{\"code\":\"READER\",\"name\":\"Reader\",\"description\":null,"
                            + "\"approver\":false,\"roleType\":\"READ_ONLY\",\"baseRole\":null}"));
        service.on(
                decided(
                        RoleAdminService.ROLE,
                        ApprovalStatus.APPROVED,
                        "{\"code\":\"REVIEWER\",\"name\":\"Reviewer\",\"description\":\"Reads\","
                                + "\"approver\":true,\"roleType\":\"MODULE\","
                                + "\"baseRole\":\"CHECKER\"}"));
        ArgumentCaptor<AppRole> saved = ArgumentCaptor.forClass(AppRole.class);
        verify(roles, times(2)).save(saved.capture());
        AppRole reader = saved.getAllValues().get(0);
        assertThat(reader.getStatus()).isEqualTo(AppRole.Status.PROVISIONAL);
        assertThat(reader.getDescription()).isNull();
        assertThat(reader.getBaseRole()).isNull();
        assertThat(reader.isApprover()).isFalse();
        AppRole reviewer = saved.getAllValues().get(1);
        assertThat(reviewer.getDescription()).isEqualTo("Reads");
        assertThat(reviewer.getBaseRole()).isEqualTo(AppRole.BaseRole.CHECKER);
        assertThat(reviewer.isApprover()).isTrue();
        assertThat(reviewer.getEffectiveFrom()).isEqualTo(TODAY);
    }

    @Test
    void SRS_22_23_theApprovalNeverChangesAProtectedRoleNorAppliesAGrantTwice() {
        when(roles.findById(1L)).thenReturn(Optional.of(role(1L, "SYSTEM_ADMINISTRATOR")));
        refused(
                () ->
                        service.on(
                                decided(
                                        RoleAdminService.GRANT,
                                        ApprovalStatus.APPROVED,
                                        "{\"roleId\":1,\"permissionId\":7,\"granted\":true}")),
                ApiErrorCode.PROTECTED_ROLE);
        // Granted meanwhile by an earlier approval of the same change.
        granted(grant(LocalDate.of(2026, 1, 1), null));
        service.on(
                decided(
                        RoleAdminService.GRANT,
                        ApprovalStatus.APPROVED,
                        "{\"roleId\":5,\"permissionId\":7,\"granted\":true}"));
        verify(grants, never()).save(any());
        verify(contexts, never()).evictAll();
        verify(audit, never()).record(any(), any());
    }

    @Test
    void SRS_22_23_anApprovedGrantStartsTodayAndAnApprovedWithdrawalEndsYesterday() {
        String grantIt = "{\"roleId\":5,\"permissionId\":7,\"granted\":true}";
        // Never granted: a new row from today.
        granted(null);
        service.on(decided(RoleAdminService.GRANT, ApprovalStatus.APPROVED, grantIt));
        ArgumentCaptor<AppRolePermission> saved = ArgumentCaptor.forClass(AppRolePermission.class);
        verify(grants).save(saved.capture());
        assertThat(saved.getValue().getEffectiveFrom()).isEqualTo(TODAY);
        assertThat(saved.getValue().getEffectiveTo()).isNull();

        // Ended before: the same row is granted again from today.
        AppRolePermission ended = grant(LocalDate.of(2026, 1, 1), TODAY.minusDays(10));
        granted(ended);
        service.on(decided(RoleAdminService.GRANT, ApprovalStatus.APPROVED, grantIt));
        assertThat(ended.isActiveOn(TODAY)).isTrue();
        assertThat(ended.getEffectiveFrom()).isEqualTo(TODAY);

        // In force: a withdrawal end-dates it, never deletes it.
        AppRolePermission active = grant(LocalDate.of(2026, 1, 1), null);
        granted(active);
        service.on(
                decided(
                        RoleAdminService.GRANT,
                        ApprovalStatus.APPROVED,
                        "{\"roleId\":5,\"permissionId\":7,\"granted\":false}"));
        assertThat(active.getEffectiveTo()).isEqualTo(TODAY.minusDays(1));
        assertThat(active.isActiveOn(TODAY)).isFalse();
        // Every applied change refreshes the sessions' rights and is audited.
        verify(contexts, times(3)).evictAll();
        verify(audit, times(3)).record(eq("checker.hq@x"), any());
    }

    // ---------------------------------------------------------------- the matrix

    @Test
    void SRS_22_8_theMatrixListsOnlyGrantsInForceAndMarksTheProtectedRole() {
        AppRole legacy = role(8L, "LEGACY_ROLE");
        ReflectionTestUtils.setField(legacy, "roleType", null);
        ReflectionTestUtils.setField(legacy, "baseRole", null);
        when(roles.findAllByOrderByCode())
                .thenReturn(List.of(legacy, role(1L, "SYSTEM_ADMINISTRATOR")));
        when(grants.findAll())
                .thenReturn(
                        List.of(
                                grant(LocalDate.of(2026, 1, 1), null),
                                grant(LocalDate.of(2026, 1, 1), TODAY.minusDays(1)),
                                grant(TODAY.plusDays(1), null)));

        RoleAdminService.Matrix matrix = service.matrix(admin);

        assertThat(matrix.grants())
                .containsExactly(new RoleAdminService.Grant(ROLE_ID, PERMISSION_ID));
        assertThat(matrix.roles().get(0).roleType()).isNull();
        assertThat(matrix.roles().get(0).baseRole()).isNull();
        assertThat(matrix.roles().get(0).protectedRole()).isFalse();
        assertThat(matrix.roles().get(1).protectedRole()).isTrue();
        assertThat(matrix.permissions())
                .containsExactly(
                        new RoleAdminService.PermissionView(PERMISSION_ID, null, null, null));
    }
}
