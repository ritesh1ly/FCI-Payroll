package in.gov.fci.payroll.iam.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.iam.business.model.AppRole;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.AppUserAccessGroup;
import in.gov.fci.payroll.iam.orchestration.AccessAdminService;
import in.gov.fci.payroll.iam.orchestration.AccessContextService;
import in.gov.fci.payroll.iam.orchestration.RoleAdminService;
import in.gov.fci.payroll.iam.orchestration.UserAdminService;
import in.gov.fci.payroll.iam.orchestration.UserReportService;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.logging.Sensitive;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.AccessMode;
import in.gov.fci.payroll.platform.security.ScopeType;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.workflow.api.Otp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * System Administrator screens A-01 … A-04 (users, roles, access groups and assignments). Access is
 * role and access group only (SRS-22-8). Every endpoint is gated by {@code @authz.can} on its
 * screen and action; services re-check and apply the protected-role, self-assignment and
 * maker-checker rules.
 */
@RestController
@Profile("online")
@RequestMapping("/v1/admin")
public class AccessAdminController {

    public record NewUserRequest(
            @NotBlank @Size(max = 150) @PrintableAscii String loginId,
            @NotBlank @Size(max = 150) @PrintableAscii String displayName,
            @NotNull AppUser.Category category,
            @Size(max = 20) @PrintableAscii String employeeEis,
            @Size(max = 20) @PrintableAscii String mobile,
            @NotBlank @Size(max = 128) @Sensitive String temporaryPassword) {

        @Override
        public String toString() {
            return "NewUserRequest{loginId=" + loginId + "}";
        }
    }

    public record ContactRequest(
            @Size(max = 150) @PrintableAscii String displayName,
            @Size(max = 20) @PrintableAscii String mobile) {}

    public record StatusRequest(
            @NotNull AppUser.Status status,
            AppUser.DeactivationReason reason,
            @Size(max = 500) @PrintableAscii String note) {}

    public record TemporaryPasswordRequest(
            @NotBlank @Size(max = 128) @Sensitive String temporaryPassword) {

        @Override
        public String toString() {
            return "TemporaryPasswordRequest{}";
        }
    }

    public record NewRoleRequest(
            @NotBlank @Size(max = 30) @PrintableAscii String code,
            // app_role.role_name VARCHAR2(150) (target DDL v4.0)
            @NotBlank @Size(max = 150) @PrintableAscii String name,
            @Size(max = 500) @PrintableAscii String description,
            boolean approver,
            AppRole.RoleType roleType,
            AppRole.BaseRole baseRole,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    public record GrantRequest(
            @NotNull Long roleId,
            @NotNull Long permissionId,
            boolean granted,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    public record NewGroupRequest(
            // access_group.group_code VARCHAR2(40), group_name VARCHAR2(200) (target DDL v4.0)
            @NotBlank @Size(max = 40) @PrintableAscii String code,
            @NotBlank @Size(max = 200) @PrintableAscii String name,
            @NotNull Long roleId,
            @NotNull ScopeType scopeType,
            boolean consolidated,
            AccessMode accessMode,
            LocalDate effectiveFrom,
            @Size(max = 20) List<AccessAdminService.ScopeInput> scopes,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    public record EndGroupRequest(
            LocalDate lastDay, @Size(max = 500) @PrintableAscii String reason) {}

    public record NewAssignmentRequest(
            @NotNull Long userId,
            @NotNull Long groupId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    public record RevokeRequest(@NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    /** A-01 unlock: an administrator unlock always records why (target DDL v4.0). */
    public record UnlockRequest(@NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    /** A-01 terminate session: the reason is recorded (SRS-G-49). */
    public record TerminateRequest(@NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    /** A-01 suspension of a group of accounts during a security incident (SRS-G-49). */
    public record SuspendRequest(
            @NotNull @Size(min = 1, max = UserAdminService.MAX_SUSPEND) List<@NotNull Long> userIds,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    /** How many sessions an administrator ended. */
    public record Terminated(int sessions) {}

    /** A-04 revoke: the cause defaults to ADMIN (target DDL v4.0 revoke_cause). */
    public record RevokeAssignmentRequest(
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            AppUserAccessGroup.RevokeCause cause) {}

    private final AccessContextService contexts;
    private final UserAdminService users;
    private final RoleAdminService roles;
    private final AccessAdminService access;
    private final UserReportService reports;

    private final ExportStreaming exports;
    private final Paging paging;

    public AccessAdminController(
            ExportStreaming exports,
            AccessContextService contexts,
            UserAdminService users,
            RoleAdminService roles,
            AccessAdminService access,
            UserReportService reports,
            Paging paging) {
        this.paging = paging;
        this.exports = exports;
        this.contexts = contexts;
        this.users = users;
        this.roles = roles;
        this.access = access;
        this.reports = reports;
    }

    // ---------------------------------------------------------------- A-01 Users

    @GetMapping("/users")
    @PreAuthorize("@authz.gate('A-01','VIEW')")
    public ResponseEntity<UserAdminService.PageView<UserAdminService.UserRow>> users(
            @RequestParam(required = false) @Size(max = 60) String q,
            @RequestParam(required = false) AppUser.Status status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageSpec spec = paging.resolve(page, size);
        return noStore(users.list(contexts.current(), q, status, spec.page(), spec.size()));
    }

    @GetMapping("/users/{id}")
    @PreAuthorize("@authz.gate('A-01','VIEW')")
    public ResponseEntity<UserAdminService.UserRow> user(@PathVariable Long id) {
        return noStore(users.get(contexts.current(), id));
    }

    @PostMapping("/users")
    @PreAuthorize("@authz.gate('A-01','CREATE')")
    public ResponseEntity<UserAdminService.UserRow> createUser(
            @Valid @RequestBody NewUserRequest request) {
        UserAdminService.UserRow row =
                users.create(
                        contexts.current(),
                        new UserAdminService.NewUser(
                                request.loginId(),
                                request.displayName(),
                                request.category(),
                                request.employeeEis(),
                                request.mobile(),
                                request.temporaryPassword()));
        return ResponseEntity.status(HttpStatus.CREATED).body(row);
    }

    @PutMapping("/users/{id}/contact")
    @PreAuthorize("@authz.gate('A-01','EDIT')")
    public ResponseEntity<UserAdminService.UserRow> contact(
            @PathVariable Long id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ContactRequest request) {
        // SRS-15-29: If-Match carries the rowVersion the caller read ("7", W/"7" or 7).
        Long expected = ifMatch == null ? null : version(ifMatch);
        return noStore(
                users.updateContact(
                        contexts.current(), id, request.displayName(), request.mobile(), expected));
    }

    @PostMapping("/users/{id}/status")
    @PreAuthorize("@authz.gate('A-01','EDIT')")
    public ResponseEntity<UserAdminService.UserRow> status(
            @PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return noStore(
                users.changeStatus(
                        contexts.current(),
                        id,
                        request.status(),
                        request.reason(),
                        request.note()));
    }

    @PostMapping("/users/{id}/unlock")
    @PreAuthorize("@authz.gate('A-01','EDIT')")
    public ResponseEntity<UserAdminService.UserRow> unlock(
            @PathVariable Long id, @Valid @RequestBody UnlockRequest request) {
        return noStore(users.unlock(contexts.current(), id, request.reason()));
    }

    /** SRS-G-49: ends the user's open sessions now; the account stays as it is. */
    @PostMapping("/users/{id}/terminate-session")
    @PreAuthorize("@authz.gate('A-01','EDIT')")
    public ResponseEntity<Terminated> terminateSession(
            @PathVariable Long id, @Valid @RequestBody TerminateRequest request) {
        return noStore(
                new Terminated(users.terminateSessions(contexts.current(), id, request.reason())));
    }

    /** SRS-G-49: suspends a group of accounts with one reason (all in scope, or none). */
    @PostMapping("/users/suspend")
    @PreAuthorize("@authz.gate('A-01','EDIT')")
    public ResponseEntity<UserAdminService.SuspendOutcome> suspend(
            @Valid @RequestBody SuspendRequest request) {
        return noStore(users.suspend(contexts.current(), request.userIds(), request.reason()));
    }

    /** SRS-G-50: the user's login record (latest sign-ins). */
    @GetMapping("/users/{id}/logins")
    @PreAuthorize("@authz.gate('A-01','VIEW')")
    public ResponseEntity<Paged<UserAdminService.LoginRecord>> logins(
            @PathVariable Long id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(users.logins(contexts.current(), id), paging.resolve(page, size)));
    }

    /** OD-130: the user's refused calls and suspensions, for the Administrator's decision. */
    @GetMapping("/users/{id}/offences")
    @PreAuthorize("@authz.gate('A-01','VIEW')")
    public ResponseEntity<UserAdminService.PageView<AuditService.SecurityEventView>> offences(
            @PathVariable Long id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageSpec spec = paging.resolve(page, size);
        return noStore(users.offences(contexts.current(), id, spec.page(), spec.size()));
    }

    /** F-3 (SRS-G-50): the Active User Report, one page, inactive users flagged. */
    @GetMapping("/active-users")
    @PreAuthorize("@authz.gate('A-01','VIEW')")
    public ResponseEntity<UserReportService.ActiveUserReport> activeUsers(
            @RequestParam(required = false) @Size(max = 60) String q,
            @RequestParam(defaultValue = "false") boolean inactiveOnly,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageSpec spec = paging.resolve(page, size);
        return noStore(
                reports.activeUsers(contexts.current(), q, inactiveOnly, spec.page(), spec.size()));
    }

    /** F-3 as CSV or Excel (A-01 EXPORT). */
    @GetMapping("/active-users/export")
    @PreAuthorize("@authz.gate('A-01','EXPORT')")
    public ResponseEntity<StreamingResponseBody> exportActiveUsers(
            @RequestParam(required = false) @Size(max = 60) String q,
            @RequestParam(defaultValue = "false") boolean inactiveOnly,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(reports.exportSize(context, q, inactiveOnly));
        return exports.stream(
                "active-users", format, sink -> reports.export(context, q, inactiveOnly, sink));
    }

    /** F-7 (SRS-G-50, SRS-22-11): readiness of every payroll unit in scope. */
    @GetMapping("/readiness")
    @PreAuthorize("@authz.gate('A-01','VIEW')")
    public ResponseEntity<UserReportService.ReadinessReport> readiness() {
        return noStore(reports.readiness(contexts.current()));
    }

    @PostMapping("/users/{id}/password")
    @PreAuthorize("@authz.gate('A-01','EDIT')")
    public ResponseEntity<Void> resetPassword(
            @PathVariable Long id, @Valid @RequestBody TemporaryPasswordRequest request) {
        users.resetPassword(contexts.current(), id, request.temporaryPassword());
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- A-02 Roles & permissions

    /** X-01 Screen-Level Access Matrix (SRS-22-8; PENDING API-02-1): the grants in force, paged. */
    @GetMapping("/access-matrix")
    @PreAuthorize("@authz.gate('A-08','VIEW')")
    public ResponseEntity<Paged<RoleAdminService.ScreenAccess>> accessMatrix(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(roles.screenAccess(contexts.current()), paging.resolve(page, size)));
    }

    @GetMapping("/roles")
    @PreAuthorize("@authz.gate('A-02','VIEW')")
    public ResponseEntity<RoleAdminService.Matrix> matrix() {
        return noStore(roles.matrix(contexts.current()));
    }

    @PostMapping("/roles")
    @PreAuthorize("@authz.gate('A-02','CREATE')")
    public ResponseEntity<RoleAdminService.Proposal> createRole(
            @Valid @RequestBody NewRoleRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(
                        roles.proposeRole(
                                contexts.current(),
                                request.code(),
                                request.name(),
                                request.description(),
                                request.approver(),
                                request.roleType(),
                                request.baseRole(),
                                request.reason(),
                                Otp.proof(request.otp())));
    }

    @PutMapping("/roles/grants")
    @PreAuthorize("@authz.gate('A-02','EDIT')")
    public ResponseEntity<RoleAdminService.Proposal> grant(
            @Valid @RequestBody GrantRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(
                        roles.proposeGrant(
                                contexts.current(),
                                request.roleId(),
                                request.permissionId(),
                                request.granted(),
                                request.reason(),
                                Otp.proof(request.otp())));
    }

    // ---------------------------------------------------------------- A-03 Access groups

    @GetMapping("/groups")
    @PreAuthorize("@authz.gate('A-03','VIEW')")
    public ResponseEntity<List<AccessAdminService.GroupView>> groups() {
        return noStore(access.groups(contexts.current()));
    }

    @PostMapping("/groups")
    @PreAuthorize("@authz.gate('A-03','CREATE')")
    public ResponseEntity<AccessAdminService.GroupView> createGroup(
            @Valid @RequestBody NewGroupRequest request) {
        AccessAdminService.GroupView view =
                access.createGroup(
                        contexts.current(),
                        new AccessAdminService.NewGroup(
                                request.code(),
                                request.name(),
                                request.roleId(),
                                request.scopeType(),
                                request.consolidated(),
                                request.accessMode(),
                                request.effectiveFrom(),
                                request.scopes(),
                                request.reason()),
                        Otp.proof(request.otp()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view);
    }

    @PostMapping("/groups/{id}/end")
    @PreAuthorize("@authz.gate('A-03','EDIT')")
    public ResponseEntity<AccessAdminService.GroupView> endGroup(
            @PathVariable Long id, @Valid @RequestBody EndGroupRequest request) {
        return noStore(
                access.endGroup(contexts.current(), id, request.lastDay(), request.reason()));
    }

    // ---------------------------------------------------------------- A-04 Assignments

    @GetMapping("/assignments")
    @PreAuthorize("@authz.gate('A-04','VIEW')")
    public ResponseEntity<AccessAdminService.PageView<AccessAdminService.AssignmentView>>
            assignments(
                    @RequestParam(required = false) Long userId,
                    @RequestParam(required = false) Integer page,
                    @RequestParam(required = false) Integer size) {
        PageSpec spec = paging.resolve(page, size);
        return noStore(access.assignments(contexts.current(), userId, spec.page(), spec.size()));
    }

    @PostMapping("/assignments")
    @PreAuthorize("@authz.gate('A-04','CREATE')")
    public ResponseEntity<AccessAdminService.AssignmentView> propose(
            @Valid @RequestBody NewAssignmentRequest request) {
        AccessAdminService.AssignmentView view =
                access.propose(
                        contexts.current(),
                        new AccessAdminService.NewAssignment(
                                request.userId(),
                                request.groupId(),
                                request.effectiveFrom(),
                                request.effectiveTo(),
                                request.reason()),
                        Otp.proof(request.otp()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view);
    }

    @PostMapping("/assignments/{id}/revoke")
    @PreAuthorize("@authz.gate('A-04','DELETE')")
    public ResponseEntity<AccessAdminService.AssignmentView> revoke(
            @PathVariable Long id, @Valid @RequestBody RevokeAssignmentRequest request) {
        return noStore(access.revoke(contexts.current(), id, request.cause(), request.reason()));
    }

    /**
     * An If-Match version: digits, optionally quoted and weak ("7", W/"7"); anything else is 400.
     */
    static Long version(String ifMatch) {
        String v = ifMatch.strip();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        if (v.isEmpty() || v.length() > 18 || !v.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        return Long.valueOf(v);
    }
}
