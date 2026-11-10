package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.iam.business.model.AppRole;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.workflow.api.RoleEquivalence;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The workflow's role mapping (MODULE role to its functional base role), from {@code app_role}. */
@Component
public class RoleCatalogueEquivalence implements RoleEquivalence {

    private final AccessRepositories.Roles roles;

    public RoleCatalogueEquivalence(AccessRepositories.Roles roles) {
        this.roles = roles;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean actsAs(String heldRole, String levelRole) {
        if (heldRole == null || levelRole == null) {
            return false;
        }
        if (heldRole.equals(levelRole)) {
            return true;
        }
        Optional<AppRole> held = roles.findByCode(heldRole);
        Optional<AppRole> level = roles.findByCode(levelRole);
        return held.isPresent()
                && level.isPresent()
                && held.get().getRoleType() == AppRole.RoleType.MODULE
                && level.get().getRoleType() == AppRole.RoleType.FUNCTIONAL
                && held.get().getBaseRole() != null
                && held.get().getBaseRole() == level.get().getBaseRole();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveRole(String roleCode, java.time.LocalDate day) {
        return roleCode != null
                && roles.findByCode(roleCode).filter(r -> r.isActiveOn(day)).isPresent();
    }
}
