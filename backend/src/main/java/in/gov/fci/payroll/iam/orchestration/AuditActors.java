package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.audit.api.ActorDirectory;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.iam.business.model.AppRole;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * X-04 Maker and Checker (name and Employee No., "taken from the signed-in users"): the display
 * name of each user and the EIS number of its employee record, for the audit module.
 */
@Component
public class AuditActors implements ActorDirectory {

    private final AccessRepositories.Users users;
    private final EmployeeDirectory employees;
    private final AccessRepositories.Roles roles;
    private final Clock clock;

    public AuditActors(
            AccessRepositories.Users users,
            EmployeeDirectory employees,
            AccessRepositories.Roles roles,
            Clock clock) {
        this.users = users;
        this.employees = employees;
        this.roles = roles;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Actor> actors(Collection<String> loginIds) {
        Map<String, Actor> result = new LinkedHashMap<>();
        if (loginIds == null || loginIds.isEmpty()) {
            return result;
        }
        for (AppUser user : users.findByLoginIdIn(loginIds)) {
            result.put(
                    user.getLoginId(),
                    new Actor(
                            user.getLoginId(),
                            user.getDisplayName(),
                            employees.eisOf(user.getEmployeeId()).orElse(null)));
        }
        return result;
    }

    /**
     * The holders today of the roles of a group: maker roles, checker and approver roles, or the
     * administrator roles. Read through the approved role assignments (R-RBAC).
     */
    @Override
    @Transactional(readOnly = true)
    public Set<String> loginsOf(String userGroup) {
        List<String> codes =
                roles.findAllByOrderByCode().stream()
                        .map(AppRole::getCode)
                        .filter(code -> inGroup(code, userGroup))
                        .toList();
        return codes.isEmpty()
                ? Set.of()
                : new TreeSet<>(users.findLoginsHoldingRoles(codes, LocalDate.now(clock)));
    }

    private static boolean inGroup(String roleCode, String userGroup) {
        return switch (userGroup) {
            case "MAKER" -> roleCode.contains("MAKER");
            case "CHECKER" -> roleCode.contains("CHECKER") || roleCode.contains("APPROVER");
            case "ADMINISTRATOR" -> roleCode.contains("ADMINISTRATOR");
            default -> false;
        };
    }
}
