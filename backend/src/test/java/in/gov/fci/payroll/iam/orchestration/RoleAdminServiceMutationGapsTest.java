package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.iam.business.model.AppPermission;
import in.gov.fci.payroll.iam.business.model.AppRole;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mutation gaps of {@link RoleAdminService}: A-02 screen checks (SRS-22-8, SRS-22-23), the proposal
 * answer, the audit line of an approved role and the 404 of a grant for an unknown role.
 */
class RoleAdminServiceMutationGapsTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private final AccessRepositories.Roles roles = mock(AccessRepositories.Roles.class);
    private final AccessRepositories.Permissions permissions =
            mock(AccessRepositories.Permissions.class);
    private final AccessRepositories.RolePermissions grants =
            mock(AccessRepositories.RolePermissions.class);
    private final AccessContextService contexts = mock(AccessContextService.class);
    private final AccessPolicy policy = mock(AccessPolicy.class);
    private final AuditService audit = mock(AuditService.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final ObjectMapper mapper = JsonMapper.builder().build();

    private final RoleAdminService service =
            new RoleAdminService(
                    roles,
                    permissions,
                    grants,
                    contexts,
                    policy,
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

    @BeforeEach
    void wire() {
        when(contexts.today()).thenReturn(TODAY);
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(21L, ApprovalStatus.PENDING_APPROVAL, null));
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

    private ApprovalDecided approved(String entity, String payload) {
        return new ApprovalDecided(
                21L,
                "A-02",
                entity,
                "key",
                ApprovalStatus.APPROVED,
                null,
                payload,
                "checker.hq@x",
                Instant.parse("2026-11-06T07:00:00Z"));
    }

    @Test
    void SRS_22_8_theMatrixNeedsA02ViewAndAnEmptyMatrixIsEmpty() {
        when(grants.findAll()).thenReturn(List.of());
        when(roles.findAllByOrderByCode()).thenReturn(List.of());
        when(permissions.findAllByOrderByScreenCodeAscActionCodeAsc()).thenReturn(List.of());

        RoleAdminService.Matrix matrix = service.matrix(admin);

        verify(policy).require(admin, "A-02", "VIEW");
        assertThat(matrix.roles()).isEmpty();
        assertThat(matrix.permissions()).isEmpty();
        assertThat(matrix.grants()).isEmpty();
    }

    @Test
    void SRS_22_8_aRoleProposalChecksA02CreateAndAnswersWithTheApprovalAndItsStatus() {
        RoleAdminService.Proposal proposal =
                service.proposeRole(
                        admin,
                        "AUDITOR",
                        "Auditor",
                        null,
                        false,
                        null,
                        AppRole.BaseRole.MAKER,
                        "New role",
                        null);

        verify(policy).require(admin, "A-02", "CREATE");
        assertThat(proposal).isEqualTo(new RoleAdminService.Proposal(21L, "PENDING_APPROVAL"));
    }

    @Test
    void SRS_22_8_aGrantProposalChecksA02EditAndAnswersWithTheApprovalAndItsStatus() {
        AppRole role = role(5L, "PAYROLL_AUDITOR");
        when(roles.findById(5L)).thenReturn(Optional.of(role));
        AppPermission permission = mock(AppPermission.class);
        when(permission.getId()).thenReturn(7L);
        when(permission.key()).thenReturn("C-13:VIEW");
        when(permissions.findAllByOrderByScreenCodeAscActionCodeAsc())
                .thenReturn(List.of(permission));

        RoleAdminService.Proposal proposal =
                service.proposeGrant(admin, 5L, 7L, true, "Audit need", null);

        verify(policy).require(admin, "A-02", "EDIT");
        assertThat(proposal).isNotNull();
        assertThat(proposal).isEqualTo(new RoleAdminService.Proposal(21L, "PENDING_APPROVAL"));
    }

    @Test
    void SRS_22_23_anApprovedRoleIsCreatedAndAuditedByTheCheckerAsA02() {
        String payload =
                "{\"code\":\"AUDITOR\",\"name\":\"Auditor\",\"description\":\"Reads runs\","
                        + "\"approver\":true,\"roleType\":\"MODULE\",\"baseRole\":\"MAKER\"}";

        service.on(approved("app_role", payload));

        verify(roles).save(any(AppRole.class));
        verify(audit)
                .record(
                        "checker.hq@x",
                        AuditService.Entry.change(
                                        "app_role",
                                        "AUDITOR",
                                        "CREATE",
                                        null,
                                        "PROVISIONAL",
                                        "Role approved")
                                .module("A-02"));
    }

    @Test
    void SRS_22_23_anApprovedGrantForAnUnknownRoleFailsAsNotFoundAndChangesNothing() {
        String payload = "{\"roleId\":99,\"permissionId\":7,\"granted\":true}";
        when(roles.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.on(approved("app_role_permission", payload)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
        org.mockito.Mockito.verifyNoInteractions(audit);
    }
}
