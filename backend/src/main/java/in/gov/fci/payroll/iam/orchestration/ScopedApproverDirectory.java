package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.workflow.api.ApproverDirectory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * BR-APPR-007 for the workflow: someone other than the maker holds the level's role, through an
 * approved, current access group whose scope covers the office, on an ACTIVE account. Uses the same
 * scope rules as the sign-in ({@link ScopeResolver}), so "can approve" here and in the inbox agree.
 */
@Component
public class ScopedApproverDirectory implements ApproverDirectory {

    private final AccessRepositories.Assignments assignments;
    private final AccessRepositories.Users users;
    private final ScopeResolver scopes;
    private final CurrentAccess contexts;

    public ScopedApproverDirectory(
            AccessRepositories.Assignments assignments,
            AccessRepositories.Users users,
            ScopeResolver scopes,
            CurrentAccess contexts) {
        this.assignments = assignments;
        this.users = users;
        this.scopes = scopes;
        this.contexts = contexts;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasApprover(String roleCode, Long locationId, String makerLogin) {
        if (roleCode == null || locationId == null) {
            return false;
        }
        List<Long> open = assignments.findUserIdsWithOpenAssignments();
        Map<Long, List<ActiveGroup>> current = scopes.activeGroupsOf(open, contexts.today());
        for (Long userId : open) {
            if (!holds(current.get(userId), roleCode, locationId)) {
                continue;
            }
            AppUser user = users.findById(userId).orElse(null);
            if (user != null
                    && user.getStatus() == AppUser.Status.ACTIVE
                    && !user.getLoginId().equals(makerLogin)) {
                return true;
            }
        }
        return false;
    }

    /** Whether one of {@code groups} holds the role at the office. */
    private static boolean holds(List<ActiveGroup> groups, String roleCode, Long locationId) {
        return groups != null
                && groups.stream()
                        .anyMatch(
                                g ->
                                        g.roleCode().equals(roleCode)
                                                && g.coverage().contains(locationId));
    }

    /**
     * BR-APPR-007 for many levels at once (A-08, Wave A review): every active account's current
     * groups are read once, then each level is answered in memory with the same rule as {@link
     * #hasApprover} - the role held through a group covering the office, by someone other than the
     * maker.
     */
    @Override
    @Transactional(readOnly = true)
    public Staffing staffing() {
        Map<String, List<Holder>> byRole = new HashMap<>();
        List<Long> open = assignments.findUserIdsWithOpenAssignments();
        Map<Long, List<ActiveGroup>> current = scopes.activeGroupsOf(open, contexts.today());
        for (Long userId : open) {
            List<ActiveGroup> held = current.get(userId);
            if (held == null) {
                continue;
            }
            AppUser user = users.findById(userId).orElse(null);
            if (user == null || user.getStatus() != AppUser.Status.ACTIVE) {
                continue;
            }
            for (ActiveGroup group : held) {
                byRole.computeIfAbsent(group.roleCode(), r -> new ArrayList<>())
                        .add(new Holder(user.getLoginId(), group.coverage()));
            }
        }
        return (roleCode, locationId, makerLogin) ->
                roleCode != null
                        && locationId != null
                        && byRole.getOrDefault(roleCode, List.of()).stream()
                                .anyMatch(
                                        h ->
                                                !h.login().equals(makerLogin)
                                                        && h.coverage().contains(locationId));
    }

    /** One holder of a role: the account's login and the offices its group covers. */
    private record Holder(String login, Set<Long> coverage) {}

    @Override
    @Transactional(readOnly = true)
    public List<Long> holders(String roleCode, Long locationId) {
        if (roleCode == null || locationId == null) {
            return List.of();
        }
        // One read of every account's groups: a read per account took seconds inside the caller's
        // transaction (a cycle return waited 4.7 s on 247 demo accounts:
        // docs/engineering/PERFORMANCE_GUIDE.md).
        List<Long> holders = new ArrayList<>();
        List<Long> open = assignments.findUserIdsWithOpenAssignments();
        Map<Long, List<ActiveGroup>> current = scopes.activeGroupsOf(open, contexts.today());
        for (Long userId : open) {
            if (holds(current.get(userId), roleCode, locationId)) {
                AppUser user = users.findById(userId).orElse(null);
                if (user != null && user.getStatus() == AppUser.Status.ACTIVE) {
                    holders.add(userId);
                }
            }
        }
        return holders;
    }
}
