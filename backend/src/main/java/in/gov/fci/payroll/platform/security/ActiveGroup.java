package in.gov.fci.payroll.platform.security;

import java.util.Set;

/**
 * An access group the user may use today (assignment current and approved, group active and
 * approved), with the locations its scope covers.
 *
 * @param coverage every location the group's scope reaches (subtree, zone or everything)
 * @param zoneIds zones of ZONE-scoped rows (data scope is the whole zone)
 * @param accessMode what the group confers on the screens its role permits ({@code access_mode})
 */
public record ActiveGroup(
        Long id,
        String code,
        String name,
        Long roleId,
        String roleCode,
        String roleName,
        ScopeType scopeType,
        boolean consolidated,
        Set<Long> coverage,
        Set<Long> zoneIds,
        AccessMode accessMode) {}
