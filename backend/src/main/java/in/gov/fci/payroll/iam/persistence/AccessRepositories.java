package in.gov.fci.payroll.iam.persistence;

import in.gov.fci.payroll.iam.business.model.AccessGroup;
import in.gov.fci.payroll.iam.business.model.AccessGroupScope;
import in.gov.fci.payroll.iam.business.model.AppMenuGroup;
import in.gov.fci.payroll.iam.business.model.AppMenuItem;
import in.gov.fci.payroll.iam.business.model.AppPermission;
import in.gov.fci.payroll.iam.business.model.AppRole;
import in.gov.fci.payroll.iam.business.model.AppRolePermission;
import in.gov.fci.payroll.iam.business.model.AppScreen;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.AppUserAccessGroup;
import in.gov.fci.payroll.iam.business.model.AppUserRole;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Target DDL S27: users, roles, permissions, access groups, scopes and screen access. */
public final class AccessRepositories {

    private AccessRepositories() {}

    public interface Users extends Repository<AppUser, Long> {
        Optional<AppUser> findById(Long id);

        Optional<AppUser> findByLoginId(String loginId);

        /** The users behind audit rows (X-04 Maker and Checker). */
        List<AppUser> findByLoginIdIn(java.util.Collection<String> loginIds);

        /** E-mail addresses are stored lower-case; not unique in the schema, so callers check. */
        List<AppUser> findByRegisteredEmail(String registeredEmail);

        /** The accounts whose registered mobile is {@code mobile} (sign-in by mobile, OD-41). */
        List<AppUser> findByRegisteredMobile(String mobile);

        /**
         * The accounts linked to the employee numbered {@code eis} (sign-in by Employee ID, OD-37).
         * An employee number is unique only within a zone (uk_employee_emp_num), so several may
         * come back: sign-in then accepts none.
         */
        @org.springframework.data.jpa.repository.Query(
                value =
                        "SELECT u.* FROM app_user u JOIN employee e ON e.employee_id ="
                                + " u.employee_id WHERE e.emp_num = :eis",
                nativeQuery = true)
        List<AppUser> findByEmployeeNumber(
                @org.springframework.data.repository.query.Param("eis") String eis);

        boolean existsByLoginId(String loginId);

        /**
         * OD-130: the row, locked until the transaction ends: two refused calls of one user at once
         * are counted one after the other.
         */
        @org.springframework.data.jpa.repository.Lock(
                jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
        @Query("select u from AppUser u where u.id = :id")
        Optional<AppUser> lockById(@org.springframework.data.repository.query.Param("id") Long id);

        /**
         * OD-130: these rows, locked in id order: two administrators' offences at once neither
         * deadlock nor both pass the last-administrator check.
         */
        @org.springframework.data.jpa.repository.Lock(
                jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
        @Query("select u from AppUser u where u.id in :ids order by u.id")
        List<AppUser> lockAll(
                @org.springframework.data.repository.query.Param("ids") Collection<Long> ids);

        /**
         * OD-130: the holders of a role through an approved, unrevoked, effective assignment on an
         * approved group, whatever the account's status (the caller reads it under the lock).
         */
        @Query(
                value =
                        "SELECT DISTINCT a.user_id FROM app_user_access_group a JOIN access_group g"
                                + " ON g.access_group_id = a.access_group_id JOIN app_role r ON"
                                + " r.role_id = g.role_id JOIN approval_queue q ON q.approval_id ="
                                + " a.approval_id WHERE r.role_code = :role AND q.status ="
                                + " 'APPROVED' AND a.revoked_at IS NULL AND a.effective_from <="
                                + " :today AND (a.effective_to IS NULL OR a.effective_to >="
                                + " :today)",
                nativeQuery = true)
        List<Long> findHoldersOfRole(
                @org.springframework.data.repository.query.Param("role") String role,
                @org.springframework.data.repository.query.Param("today") LocalDate today);

        /**
         * X-04 "All users": the logins holding any of these roles, as {@link #findHoldersOfRole}.
         */
        @Query(
                value =
                        "SELECT DISTINCT u.login_id FROM app_user u JOIN app_user_access_group a"
                                + " ON a.user_id = u.user_id JOIN access_group g"
                                + " ON g.access_group_id = a.access_group_id JOIN app_role r ON"
                                + " r.role_id = g.role_id JOIN approval_queue q ON q.approval_id ="
                                + " a.approval_id WHERE r.role_code IN (:roles) AND q.status ="
                                + " 'APPROVED' AND a.revoked_at IS NULL AND a.effective_from <="
                                + " :today AND (a.effective_to IS NULL OR a.effective_to >="
                                + " :today)",
                nativeQuery = true)
        List<String> findLoginsHoldingRoles(
                @org.springframework.data.repository.query.Param("roles")
                        java.util.Collection<String> roles,
                @org.springframework.data.repository.query.Param("today") LocalDate today);

        /** The accounts of an employee (staff user and / or eSeva). */
        List<AppUser> findByEmployeeId(Long employeeId);

        /** The accounts of these employees (a scoped administrator's users). */
        List<AppUser> findByEmployeeIdIn(java.util.Collection<Long> employeeIds);

        /**
         * The accounts of the employees working at {@code locations} (a scoped administrator's
         * users), the scope resolved in the database: one bind per office, never one per employee
         * (deep profile 2026-10-27: a zone is about 19,000 employees, and Oracle takes at most
         * 1,000 values in an IN list).
         */
        @Query(
                "select u from AppUser u where exists (select 1 from Employee e where e.id ="
                        + " u.employeeId and e.locationId in :locations)")
        List<AppUser> findAtLocations(@Param("locations") java.util.Collection<Long> locations);

        List<AppUser> findAllById(Iterable<Long> ids);

        AppUser save(AppUser user);

        /**
         * Saves and flushes, so the returned row carries the version the change gave (SRS-15-29:
         * the caller's next If-Match names it).
         */
        AppUser saveAndFlush(AppUser user);

        @Query(
                "select u from AppUser u where (:text is null"
                        + " or lower(u.loginId) like :text escape '\\'"
                        + " or lower(u.displayName) like :text escape '\\')"
                        + " and (:status is null or u.status = :status)")
        Page<AppUser> search(
                @Param("text") String text,
                @Param("status") AppUser.Status status,
                Pageable pageable);

        /**
         * As {@link #search}, limited to the accounts of the employees at {@code locations} (a
         * scoped administrator; resolved in the database).
         */
        @Query(
                "select u from AppUser u where exists (select 1 from Employee e where e.id ="
                    + " u.employeeId and e.locationId in :locations) and (:text is null or"
                    + " lower(u.loginId) like :text escape '\\' or lower(u.displayName) like :text"
                    + " escape '\\') and (:status is null or u.status = :status)")
        Page<AppUser> searchAmong(
                @Param("text") String text,
                @Param("status") AppUser.Status status,
                @Param("locations") java.util.Collection<Long> locations,
                Pageable pageable);

        /**
         * The Active User Report (SRS-G-50): as {@link #search}; with {@code before}, only users
         * whose last sign-in (or, never signed in, the account's creation) is earlier.
         */
        @Query(
                "select u from AppUser u where (:text is null"
                        + " or lower(u.loginId) like :text escape '\\'"
                        + " or lower(u.displayName) like :text escape '\\')"
                        + " and (cast(:before as timestamp) is null"
                        + " or coalesce(u.lastLoginAt, u.createdAt) < :before)"
                        + " order by u.loginId")
        Page<AppUser> report(
                @Param("text") String text,
                @Param("before") java.time.Instant before,
                Pageable pageable);

        /**
         * As {@link #report}, limited to the accounts of the employees at {@code locations} (a
         * scoped administrator; resolved in the database).
         */
        @Query(
                "select u from AppUser u where exists (select 1 from Employee e where e.id ="
                    + " u.employeeId and e.locationId in :locations) and (:text is null or"
                    + " lower(u.loginId) like :text escape '\\' or lower(u.displayName) like :text"
                    + " escape '\\') and (cast(:before as timestamp) is null or"
                    + " coalesce(u.lastLoginAt, u.createdAt) < :before) order by u.loginId")
        Page<AppUser> reportAmong(
                @Param("text") String text,
                @Param("before") java.time.Instant before,
                @Param("locations") java.util.Collection<Long> locations,
                Pageable pageable);
    }

    public interface Roles extends Repository<AppRole, Long> {
        List<AppRole> findAllByOrderByCode();

        Optional<AppRole> findById(Long id);

        Optional<AppRole> findByCode(String code);

        List<AppRole> findAllById(Iterable<Long> ids);

        AppRole save(AppRole role);
    }

    public interface Permissions extends Repository<AppPermission, Long> {
        List<AppPermission> findAllByOrderByScreenCodeAscActionCodeAsc();

        Optional<AppPermission> findByScreenCodeAndActionCode(String screen, String action);
    }

    public interface RolePermissions extends Repository<AppRolePermission, AppRolePermission.Key> {
        List<AppRolePermission> findAll();

        @Query("select rp from AppRolePermission rp where rp.key.roleId in :roleIds")
        List<AppRolePermission> findByRoleIds(@Param("roleIds") Collection<Long> roleIds);

        Optional<AppRolePermission> findById(AppRolePermission.Key key);

        AppRolePermission save(AppRolePermission grant);
    }

    public interface UserRoles extends Repository<AppUserRole, AppUserRole.Key> {
        @Query("select r from AppUserRole r where r.key.userId = :userId")
        List<AppUserRole> findByUserId(@Param("userId") Long userId);

        Optional<AppUserRole> findById(AppUserRole.Key key);

        AppUserRole save(AppUserRole role);
    }

    public interface Groups extends Repository<AccessGroup, Long> {
        List<AccessGroup> findAllByOrderByCode();

        Optional<AccessGroup> findById(Long id);

        List<AccessGroup> findAllById(Iterable<Long> ids);

        boolean existsByCode(String code);

        /** The group with this code (codes are unique, SRS-G-29). */
        Optional<AccessGroup> findByCode(String code);

        AccessGroup save(AccessGroup group);
    }

    public interface GroupScopes extends Repository<AccessGroupScope, Long> {
        List<AccessGroupScope> findByAccessGroupIdIn(Collection<Long> groupIds);

        AccessGroupScope save(AccessGroupScope scope);
    }

    public interface Assignments extends Repository<AppUserAccessGroup, Long> {
        Optional<AppUserAccessGroup> findById(Long id);

        List<AppUserAccessGroup> findByUserId(Long userId);

        /** Every assignment of these users (reports over many users at once). */
        List<AppUserAccessGroup> findByUserIdIn(Collection<Long> userIds);

        /**
         * Users with an assignment not revoked (dates, approval and group are checked by the
         * caller).
         */
        @org.springframework.data.jpa.repository.Query(
                "select distinct a.userId from AppUserAccessGroup a where a.revokedAt is null")
        List<Long> findUserIdsWithOpenAssignments();

        Optional<AppUserAccessGroup> findByApprovalId(Long approvalId);

        Page<AppUserAccessGroup> findAllByOrderByIdDesc(Pageable pageable);

        Page<AppUserAccessGroup> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

        Page<AppUserAccessGroup> findByUserIdInOrderByIdDesc(
                java.util.Collection<Long> userIds, Pageable pageable);

        AppUserAccessGroup save(AppUserAccessGroup assignment);
    }

    /** The screen registry (V012). */
    public interface Screens extends Repository<AppScreen, String> {
        List<AppScreen> findAllByOrderByDisplayOrder();

        Optional<AppScreen> findById(String code);
    }

    /** The navigation groups of the sidebar, as Wireframes v6.1 draws them (V078, OD-121). */
    public interface MenuGroups extends Repository<AppMenuGroup, String> {
        List<AppMenuGroup> findAllByOrderByGroupOrder();
    }

    /** The entries of the navigation groups (V078). */
    public interface MenuItems extends Repository<AppMenuItem, String> {
        List<AppMenuItem> findAllByOrderByGroupCodeAscItemOrderAsc();
    }
}
