package in.gov.fci.payroll.allowance.orchestration;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The offices whose claims a user sees on I-03 / I-16: every office at Headquarters; else the
 * user's cycle unit with the offices below it where the screen's right holds there, or the user's
 * own office with the offices below it (a Labour Dealing Hand of a depot).
 */
@Component
public class ClaimScope {

    private final OrgDirectory org;
    private final Authorization policy;

    public ClaimScope(OrgDirectory org, Authorization policy) {
        this.org = org;
        this.policy = policy;
    }

    /** The offices in scope, or empty for every office (Headquarters). */
    public Optional<Set<Long>> offices(AccessContext context, String screen, String action) {
        if (context.locationId() == null) {
            throw new FciException(CommonErrorCode.ACCESS_DENIED);
        }
        if (policy.isAtHeadquarters(context)) {
            policy.requireAt(context, screen, action, context.locationId());
            return Optional.empty();
        }
        Optional<OrgLocation> unit = org.cycleUnitOf(context.locationId());
        if (unit.isPresent() && policy.canAt(context, screen, action, unit.get().getId())) {
            return Optional.of(org.subtree(unit.get().getId()));
        }
        policy.requireAt(context, screen, action, context.locationId());
        return Optional.of(org.subtree(context.locationId()));
    }
}
