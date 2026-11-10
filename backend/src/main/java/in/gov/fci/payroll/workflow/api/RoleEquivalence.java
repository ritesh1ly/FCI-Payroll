package in.gov.fci.payroll.workflow.api;

/**
 * Whether a held role may act at an approval level of another role (implemented by the iam module,
 * which owns the role catalogue). Target DDL v4.0: module roles (Super User, Rule Administrator …)
 * map to one of the seven functional roles through {@code app_role.base_role_code}, as
 * configuration.
 */
public interface RoleEquivalence {

    /**
     * True if {@code heldRole} is {@code levelRole}, or a MODULE role whose base role is that of
     * the FUNCTIONAL role {@code levelRole}.
     */
    boolean actsAs(String heldRole, String levelRole);

    /**
     * BR-APPR-002 / SRS-22-16: true if {@code roleCode} is a role of the catalogue in force on
     * {@code day} (an approval level may name only such a role).
     */
    boolean isActiveRole(String roleCode, java.time.LocalDate day);
}
