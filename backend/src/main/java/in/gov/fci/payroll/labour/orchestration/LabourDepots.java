package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The depots of a session's offices for the labour screens' dropdowns (M-02, M-06, M-09), from the
 * cached organisation (no register read). Headquarters sees every depot.
 */
@Component
class LabourDepots {

    private final OrgDirectory org;

    LabourDepots(OrgDirectory org) {
        this.org = org;
    }

    /** The active depots of the session's offices on the day, by code. */
    List<OrgLocation> of(AccessContext context, boolean headquarters, LocalDate day) {
        Collection<Long> scope = OfficeScope.ids(context);
        return org.snapshot().locations().values().stream()
                .filter(l -> "DEPOT".equals(l.getType().name()) && l.isActiveOn(day))
                .filter(
                        l ->
                                headquarters
                                        || scope.contains(l.getId())
                                        || scope.contains(l.getParentId()))
                .sorted(Comparator.comparing(OrgLocation::getCode))
                .toList();
    }
}
